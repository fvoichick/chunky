/* Copyright (c) 2019 Jesper Öqvist <jesper@llbit.se>
 *
 * This file is part of Chunky.
 *
 * Chunky is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Chunky is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Chunky.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.llbit.chunky.renderer;

import se.llbit.math.Vector4;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;


/**
 * Describes part of the canvas to be rendered by a render worker.
 */
public class RenderTile {
  public final int x0, x1, y0, y1;
  // The x and y of each element are the x/y of the pixel; the z is the value by which the priority queue is ordered.
  // the w value is a 'secondary' (well, maybe primary) value which ensures that the pixels with NO data get prioritized.
  public Queue<Vector4> pixelQueue;

  public static Comparator<Vector4> zComparator = new Comparator<Vector4>(){

		@Override
		public int compare(Vector4 p1, Vector4 p2) {
        // if at lest one of the vectors has had fewer than N samples,
        // there is not enough data for the variance metric to give sensible results.
        // prioritize the one with fewest samples instead.
        // TODO: make N some kind of global constant and skip calculating z when n_samlples < N
        if ( p1.w < 2 || p2.w < 2) {
          return (int) Math.ceil(p1.w - p2.w); // ceil so that less-than-one difference still matters.
        }
        // Otherwise, priorotize the one with smaller z (= sample count)
        return (int) Math.ceil(p2.z - p1.z);
      }
	};

  public RenderTile(int x0, int x1, int y0, int y1) {
    this.x0 = x0;
    this.x1 = x1;
    this.y0 = y0;
    this.y1 = y1;
    // the queue should always have all the pixels in it (or all pixels -1 while one is being updated)
    pixelQueue = new PriorityQueue<Vector4>( (x1-x0)*(y1-y0), zComparator);
    // initialize pixel queue with z=0 for all relevant pixels:
    for (int y = y0; y < y1; ++y) {
      for (int x = x0; x < x1; ++x) {
        pixelQueue.add(new Vector4(x, y, 0, 0));
      }
    }
  }

  @Override public String toString() {
    return String.format("[Tile: [%d %d]->[%d %d]]", x0, y0, x1, y1);
  }
}
