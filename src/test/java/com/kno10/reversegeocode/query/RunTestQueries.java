package com.kno10.reversegeocode.query;

import java.io.IOException;
import java.util.Locale;

/**
 * Simple class to perform a few test queries
 * 
 * @author Erich Schubert
 */
public class RunTestQueries {
	// Queries to run
	static float[][] data = new float[][] {//
	{ -73.9865812f, 40.7305991f }, // New York
			{ 11.5754815f, 48.1372719f }, // Munich
			{ 11.61406f, 48.06596f }, // Near Munich
			{ 0f, 0f }, // Deep sea
	};

	public static void main(String[] args) {
		try {
			ReverseGeocoder rgc = new ReverseGeocoder(args[0]);
			for (float[] f : data) {
				String[] l = rgc.lookup(f[0], f[1]);
				System.out.format(Locale.ROOT, "%8.4f %8.4f", f[0], f[1]);
				for (String s : l) {
					System.out.append("||").append(s);
				}
				System.out.append('\n');
			}
			for (int i = 0; i < 20; i++) {
				float f1 = (float) (Math.random() * 360. - 180);
				float f2 = (float) (Math.random() * 120. - 60);
				String[] l = rgc.lookup(f1, f2);
				if (l.length == 0) {
					--i;
					continue;
				}
				System.out.format(Locale.ROOT, "%8.4f %8.4f", f1, f2);
				for (String s : l) {
					System.out.append("||").append(s);
				}
				System.out.append('\n');
			}
			System.out.format(Locale.ROOT, "Number of entities: %d\n",
					rgc.numentries);
			rgc.close();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}
}
