package com.kno10.reversegeocode.builder;

/*
 * Copyright (C) 2015, Erich Schubert
 * Ludwig-Maximilians-Universität München
 * Lehr- und Forschungseinheit für Datenbanksysteme
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.stage.Stage;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gs.collections.api.bag.primitive.MutableIntBag;
import com.gs.collections.api.iterator.IntIterator;
import com.gs.collections.api.map.primitive.IntObjectMap;
import com.gs.collections.api.map.primitive.MutableIntObjectMap;
import com.gs.collections.impl.bag.mutable.primitive.IntHashBag;
import com.gs.collections.impl.list.mutable.primitive.FloatArrayList;
import com.gs.collections.impl.map.mutable.primitive.IntObjectHashMap;
import com.gs.collections.impl.set.mutable.UnifiedSet;

/**
 * Build and encode the lookup index.
 * 
 * This is currently implemented using JavaFX to facilitate the polygon drawing.
 * 
 * TODO: make parameters configurable.
 * 
 * @author Erich Schubert
 */
public class BuildLayeredIndex extends Application {
	/** Class logger */
	private static final Logger LOG = LoggerFactory
			.getLogger(BuildLayeredIndex.class);

	/** Input and output file names */
	File infile, oufile, imfile;

	/** Pattern for matching coordinates */
	Pattern coordPattern = Pattern
			.compile("(?<=\t)(-?\\d+(?:\\.\\d*)),(-?\\d+(?:\\.\\d*))(?=\t|$)");

	/** Pattern for recognizing the level */
	Pattern levelPattern = Pattern.compile("(?<=\t)(\\d+)(?=\t)");

	/** Minimum and maximum level */
	private static final int MIN_LEVEL = 2, MAX_LEVEL = 10;

	/** Entities read from the file */
	private ArrayList<UnifiedSet<Entity>> entities;

	/** Minimum size of objects to draw */
	double minsize;

	/** Viewport of the map */
	Viewport viewport;

	/**
	 * Constructor.
	 */
	public BuildLayeredIndex() {
		super();
	}

	@Override
	public void init() throws Exception {
		super.init();
		List<String> unnamed = getParameters().getUnnamed();
		this.infile = new File(unnamed.get(0));
		this.oufile = new File(unnamed.get(1));
		this.imfile = unnamed.size() > 2 ? new File(unnamed.get(2)) : null;

		this.entities = new ArrayList<>();
		for (int i = 0; i < MIN_LEVEL; i++) {
			entities.add(null);
		}
		for (int i = MIN_LEVEL; i <= MAX_LEVEL; i++) {
			entities.add(new UnifiedSet<>());
		}

		// Viewport on map
		double resolution = 0.01;
		this.viewport = new Viewport(360., 180., 180., 90., resolution);

		double pixel_minsize = 2; // Minimum number of pixels (BB)
		this.minsize = pixel_minsize * resolution;
	}

	@Override
	public void start(Stage stage) throws Exception {
		// Preallocate objects (will be reset and reused!)
		Matcher m = coordPattern.matcher(""), lm = levelPattern.matcher("");
		FloatArrayList points = new FloatArrayList();
		BoundingBox bb = new BoundingBox();

		int polycount = 0, lines = 0, ecounter = 0;
		// Everybody just "loves" such Java constructs...
		try (BufferedReader b = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(new FileInputStream(infile))))) {
			long start = System.currentTimeMillis();
			String line = null;
			while ((line = b.readLine()) != null) {
				++lines;
				points.clear();
				bb.reset();

				String meta = null;
				lm.reset(line);
				if (!lm.find()) {
					LOG.warn("Line was not matched: {}", line);
					continue;
				}
				// We keep metadata 0-terminated as seperator!
				meta = line.substring(0, lm.end()) + '\0';
				int level = Integer.parseInt(lm.group(1));
				assert (!lm.find());
				m.reset(line);
				while (m.find()) {
					assert (m.start() >= lm.end());
					float lon = Float.parseFloat(m.group(1));
					float lat = Float.parseFloat(m.group(2));
					points.add(lon);
					points.add(lat);
					bb.update(lon, lat);
				}
				if (points.size() == 0) {
					LOG.warn("Line was not matched: {}", line);
					continue;
				}
				if (bb.size() < minsize) {
					continue;
				}
				Entity ent = new Entity(meta);
				if (level >= entities.size()) {
					// Level not used.
					continue;
				}
				UnifiedSet<Entity> levdata = entities.get(level);
				if (levdata == null) {
					// Level not used.
					continue;
				}
				Entity exist = levdata.get(ent);
				if (exist != null) {
					exist.bb.update(bb);
					exist.polys.add(points.toArray());
					++polycount;
				} else {
					levdata.add(ent);
					ent.bb = new BoundingBox(bb);
					ent.polys = new LinkedList<>();
					ent.polys.add(points.toArray());
					++polycount;
					++ecounter;
				}
			}

			long end = System.currentTimeMillis();
			LOG.info("Parsing time: {} ms", end - start);
			LOG.info("Read {} lines, kept {} entities, {} polygons", //
					lines, ecounter, polycount);

			render(stage);
		} catch (IOException e) {
			LOG.error("IO Error", e);
		}
		Platform.exit();
	}

	/**
	 * Render the polygons onto the "winner" map.
	 * 
	 * @param stage
	 *            Empty JavaFX stage used for rendering
	 */
	public void render(Stage stage) {
		final int blocksize = 256;
		Group rootGroup = new Group();
		Scene scene = new Scene(rootGroup, blocksize, blocksize, Color.BLACK);
		WritableImage writableImage = null; // Buffer

		MutableIntObjectMap<String> meta = new IntObjectHashMap<>();
		meta.put(0, ""); // Note: deliberately not \0 terminated.
		int entnum = 1;

		int[][] winners = new int[viewport.height][viewport.width];
		int[][] winner = new int[viewport.height][viewport.width];
		byte[][] alphas = new byte[viewport.height][viewport.width];
		long start = System.currentTimeMillis();
		for (int lev = MIN_LEVEL; lev <= MAX_LEVEL; lev++) {
			if (entities.get(lev) == null) {
				continue;
			}
			LOG.info("Rendering level {}", lev);
			for (int y = 0; y < viewport.height; y++) {
				Arrays.fill(alphas[y], (byte) 0);
				Arrays.fill(winner[y], 0);
			}

			// Sort by size.
			ArrayList<Entity> order = new ArrayList<>(entities.get(lev));
			Collections.sort(order);

			Path path = new Path();
			ObservableList<PathElement> elems = path.getElements();
			for (Entity e : order) {
				if (e.polys.size() <= 0) {
					continue;
				}

				// Area to inspect
				int xmin = Math.max(0,
						(int) Math.floor(viewport.projLon(e.bb.lonmin)) - 1);
				int xmax = Math.min(viewport.width,
						(int) Math.ceil(viewport.projLon(e.bb.lonmax)) + 1);
				int ymin = Math.max(0,
						(int) Math.ceil(viewport.projLat(e.bb.latmin)) - 1);
				int ymax = Math.min(viewport.height,
						(int) Math.floor(viewport.projLat(e.bb.latmax)) + 1);
				// System.out.format("%d-%d %d-%d; ", xmin, xmax, ymin, ymax);
				for (int x1 = xmin; x1 < xmax; x1 += blocksize) {
					int x2 = Math.min(x1 + blocksize, xmax);
					for (int y1 = ymin; y1 < ymax; y1 += blocksize) {
						int y2 = Math.min(y1 + blocksize, ymax);

						// Implementation note: we are drawing upside down.
						elems.clear();
						for (float[] f : e.polys) {
							assert (f.length > 1);
							elems.add(new MoveTo(viewport.projLon(f[0]) - x1,
									viewport.projLat(f[1]) - y1));
							for (int i = 2, l = f.length; i < l; i += 2) {
								elems.add(new LineTo(viewport.projLon(f[i])
										- x1, viewport.projLat(f[i + 1]) - y1));
							}
						}
						path.setStroke(Color.TRANSPARENT);
						path.setFill(Color.WHITE);
						path.setFillRule(FillRule.EVEN_ODD);

						rootGroup.getChildren().add(path);
						writableImage = scene.snapshot(writableImage);
						rootGroup.getChildren().remove(path);

						transferPixels(writableImage, x1, x2, y1, y2, winner,
								entnum, alphas);
					}
				}
				// Note: we construct meta 0-terminated!
				meta.put(entnum, e.key);
				++entnum;
			}
			flatten(winners, winner, meta);
		}
		long end = System.currentTimeMillis();
		LOG.info("Rendering time: {} ms", end - start);

		buildIndex(meta, winners);
		if (imfile != null) {
			visualize(meta.size(), winners);
		}
	}

	/**
	 * Transfer pixels from the rendering buffer to the winner/alpha maps.
	 * 
	 * @param img
	 *            Rendering buffer
	 * @param x1
	 *            Left
	 * @param x2
	 *            Right
	 * @param y1
	 *            Bottom
	 * @param y2
	 *            Top
	 * @param winner
	 *            Output array
	 * @param c
	 *            Entity number
	 * @param alphas
	 *            Alpha buffer
	 */
	public void transferPixels(WritableImage img, int x1, int x2, int y1,
			int y2, int[][] winner, int c, byte[][] alphas) {
		PixelReader reader = img.getPixelReader();
		for (int y = y1, py = 0; y < y2; y++, py++) {
			for (int x = x1, px = 0; x < x2; x++, px++) {
				int col = reader.getArgb(px, py);
				int alpha = (col & 0xFF);
				// Always ignore cover less than 10%
				if (alpha < 0x19) {
					continue;
				}
				// Clip value range to positive bytes,
				alpha = alpha > 0x7F ? 0x7F : alpha;
				if (alpha == 0x7F || (alpha > 0 && alpha >= alphas[y][x])) {
					alphas[y][x] = (byte) alpha;
					winner[y][x] = c;
				}
			}
		}
	}

	/**
	 * Flatten multiple layers of "winners".
	 * 
	 * @param winners
	 *            Input layers
	 * @param winner
	 *            Output array
	 * @param ents
	 *            Entities
	 * @param meta
	 *            Reduce metadata
	 */
	private void flatten(int[][] winners, int[][] winner,
			MutableIntObjectMap<String> meta) {
		MutableIntObjectMap<MutableIntBag> parents = new IntObjectHashMap<>();
		for (int y = 0; y < viewport.height; y++) {
			for (int x = 0; x < viewport.width; x++) {
				int id = winner[y][x];
				if (id > 0) {
					parents.getIfAbsentPut(id, () -> {
						return new IntHashBag();
					}).add(winners[y][x]);
				}
			}
		}
		// Find the most frequent parent:
		parents.forEachKeyValue((i, b) -> {
			int best = -1, bcount = -1;
			for (IntIterator it = b.intIterator(); it.hasNext();) {
				int p = it.next(), c = b.occurrencesOf(p);
				if (c > bcount || (c == bcount && p < best)) {
					bcount = c;
					best = p;
				}
			}
			if (best > 0) {
				meta.put(i, meta.get(i) /* 0 terminated! *///
						+ meta.get(best) /* 0 terminated */);
			}
		});
		for (int y = 0; y < viewport.height; y++) {
			for (int x = 0; x < viewport.width; x++) {
				int id = winner[y][x];
				if (id > 0) {
					winners[y][x] = id;
				}
			}
		}
	}

	/**
	 * Build the output index file.
	 * 
	 * @param meta
	 *            Metadata
	 * @param winner
	 *            Winner array
	 */
	private void buildIndex(IntObjectMap<String> meta, int[][] winner) {
		int[] map = new int[meta.size()];
		// Scan pixels for used indexes.
		for (int y = 0; y < viewport.height; y++) {
			int[] row = winner[y];
			for (int x = 0; x < viewport.width; x++) {
				map[row[x]] = 1; // present
			}
		}
		// Enumerate used indexes.
		int c = 0;
		for (int i = 0; i < map.length; i++) {
			map[i] = (map[i] == 0) ? -1 : c++;
		}
		LOG.info("Number of used entities: {}", c);
		byte[] buffer = new byte[viewport.width * 4]; // Output buffer.

		if (c > 0xFFFF) {
			// In this case, you'll need to extend the file format below.
			throw new RuntimeException(
					"Current file version only allows 0xFFFF entities.");
		}

		try (DataOutputStream os = new DataOutputStream(//
				new FileOutputStream(oufile))) {
			// Part 1: HEADER
			// Write a "magic" header first.
			os.writeInt(0x6e0_6e0_00);
			// Write dimensions
			os.writeShort(viewport.width);
			os.writeShort(viewport.height);
			// Write coverage
			os.writeFloat((float) viewport.xcover);
			os.writeFloat((float) viewport.ycover);
			os.writeFloat((float) viewport.xshift);
			os.writeFloat((float) viewport.yshift);
			// Write the number of indexes
			os.writeShort(c);

			LOG.warn("Position of pixmap: {}", os.size());
			// Part 2: PIXMAP rows
			// Encode the rows
			byte[][] rows = new byte[viewport.height][];
			for (int y = 0; y < viewport.height; y++) {
				int len = encodeLine16(winner[y], map, buffer);
				rows[y] = Arrays.copyOf(buffer, len);
			}
			// Write the row header table
			for (int y = 0; y < viewport.height; y++) {
				os.writeShort(rows[y].length);
			}
			// Write the row header table
			for (byte[] row : rows) {
				os.write(row, 0, row.length);
			}
			rows = null;

			LOG.warn("Position of metadata: {}", os.size());
			// Part 3: METADATA
			byte[][] metadata = new byte[c][];
			int c2 = 0;
			for (int i = 0; i < map.length; i++) {
				if (map[i] <= -1) {
					continue;
				}
				metadata[c2++] = meta.get(i).getBytes("UTF-8");
			}
			assert (c2 == c);
			// Write the metadata header table
			for (byte[] row : metadata) {
				assert (row.length < 0x8000);
				os.writeShort(row.length);
			}
			// Write the metadata header table
			for (byte[] row : metadata) {
				os.write(row, 0, row.length);
			}
			metadata = null;
		} catch (IOException e) {
			LOG.error("IO error writing index.", e);
		}
	}

	/**
	 * Encode a line of the output image map.
	 * 
	 * @param winner
	 *            Image map
	 * @param map
	 *            Entity ID mapping
	 * @param buffer
	 *            Output buffer
	 * @return Length
	 */
	// TODO: develop even more compact RLEs for this use case.
	private int encodeLine16(int[] winner, int[] map, byte[] buffer) {
		int len = 0;
		// Perform a simple run-length encoding.
		for (int x = 0; x < winner.length;) {
			final int cur = winner[x++];
			int run = 1; // Run length - 1
			for (; run < 256 && x < winner.length && winner[x] == cur; ++x) {
				++run;
			}
			int val = map[cur];
			assert (val <= 0xFFFF);
			buffer[len++] = (byte) ((val >>> 8) & 0xFF);
			buffer[len++] = (byte) (val & 0xFF);
			buffer[len++] = (byte) (run - 1);
		}
		return len;
	}

	/**
	 * Visualize the map.
	 * 
	 * @param Maximum
	 *            color
	 * @param winner
	 *            Winners array
	 */
	public void visualize(int max, int[][] winner) {
		// Randomly assign colors for visualization:
		Random r = new Random();
		int[] cols = new int[max + 1];
		for (int i = 1; i < cols.length; i++) {
			cols[i] = r.nextInt(0x1000000) | 0xFF000000;
		}
		try {
			WritableImage writableImage = new WritableImage(viewport.width,
					viewport.height);
			PixelWriter writer = writableImage.getPixelWriter();
			for (int y = 0; y < viewport.height; y++) {
				// Note: visualization is drawn upside down.
				int[] row = winner[viewport.height - 1 - y];
				for (int x = 0; x < viewport.width; x++) {
					writer.setArgb(x, y, cols[row[x]]);
				}
			}
			ImageIO.write(SwingFXUtils.fromFXImage(writableImage, null), "png",
					imfile);
		} catch (IOException e) {
			LOG.error("IO error writing visualization.", e);
		}
	}

	/**
	 * An entity on the map.
	 * 
	 * @author Erich Schubert
	 */
	public static class Entity implements Comparable<Entity> {
		/** Index key (description) */
		final String key;

		/** Bounding box */
		BoundingBox bb;

		List<float[]> polys;

		public Entity(String key) {
			this.key = key;
		}

		@Override
		public int hashCode() {
			return key.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null || getClass() != obj.getClass()) {
				return false;
			}
			return key.equals(((Entity) obj).key);
		}

		/**
		 * Order descending by size.
		 */
		@Override
		public int compareTo(Entity o) {
			return Double.compare(o.bb.size(), bb.size());
		}
	}

	/**
	 * Launch, as JavaFX application.
	 * 
	 * @param args
	 *            Parameters
	 */
	public static void main(String[] args) {
		launch(args);
	}
}
