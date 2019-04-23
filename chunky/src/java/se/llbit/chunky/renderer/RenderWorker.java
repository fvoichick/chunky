/* Copyright (c) 2012-2019 Jesper Öqvist <jesper@llbit.se>
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

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Formatter;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;


import se.llbit.chunky.renderer.scene.Camera;
import se.llbit.chunky.renderer.scene.RayTracer;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.log.Log;
import se.llbit.math.QuickMath;
import org.apache.commons.math3.util.FastMath;
import se.llbit.math.Ray;
import se.llbit.math.Vector4;

import java.util.Random;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.distribution.BetaDistribution;
import org.apache.commons.math3.distribution.BinomialDistribution;



/**
 * Performs rendering work.
 *
 * @author Jesper Öqvist <jesper@llbit.se>
 */
public class RenderWorker extends Thread {

  /**
   * Sleep interval (in ns).
   */
  private static final int SLEEP_INTERVAL = 75000000;

  // map of n degrees-of-freedom for T-distribution to inverse CDF at 1-alpha (to avoid recomputing)
  // (alpha is fixed to get 95% confidence interval in the final calculation)
  private static Map<Integer, Double> tVals = new HashMap<>();

  public static Double getTval(int degs) {
    if (tVals.containsKey(degs)) {
      return tVals.get(degs);
    }
    TDistribution t= new TDistribution(degs);
    double d_sum = t.inverseCumulativeProbability(1-0.025);
    tVals.put(degs, d_sum);
    return d_sum;
  }

  private final int saveX = 72, saveY = 233;
  private Path pixel_file;

  protected final int id;
  protected final AbstractRenderManager manager;

  protected final WorkerState state;
  protected final RayTracer previewRayTracer;
  protected final RayTracer rayTracer;
  protected long jobTime = 0;

  /**
   * Create a new render worker, slave to a given render manager.
   *
   * @param manager the parent render manager
   * @param id the ID for this worker
   * @param seed the random generator seed
   */
  public RenderWorker(AbstractRenderManager manager, int id, long seed) {
    super("3D Render Worker " + id);

    this.manager = manager;
    this.previewRayTracer = manager.getPreviewRayTracer();
    this.rayTracer = manager.getRayTracer();
    this.id = id;
    state = new WorkerState();
    state.random = new Random(seed);
    state.ray = new Ray();


    try{
      pixel_file = Files.createTempFile("pixel_"+saveX+"_"+saveY+"_", ".csv");
      System.out.println(pixel_file);
    }catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override public void run() {
    try {
      while (!isInterrupted()) {
        long jobStart = System.nanoTime();
        work(manager.getNextJob());
        jobTime += System.nanoTime() - jobStart;
        manager.jobDone();

        // Sleep to manage CPU utilization.
        if (jobTime > SLEEP_INTERVAL) {
          if (manager.cpuLoad < 100) {
            // sleep = jobTime * (1-utilization) / utilization
            double load = (100.0 - manager.cpuLoad) / manager.cpuLoad;
            sleep((long) ((jobTime / 1000000.0) * load));
          }
          jobTime = 0;
        }
      }
    } catch (InterruptedException ignored) {
      // Interrupted.
    } catch (Throwable e) {
      Log.error("Render worker " + id +
          " crashed with uncaught exception.", e);
    }
  }

  private double getMax(double candidates[]) {
    double max = 0;
    for(int i=0;i<candidates.length;++i)
      if(candidates[i] > max)
        max = candidates[i];
    return max;
  }

  /**
   * Perform the rendering work for a single tile.
   *
   * @param tile describes the tile to be rendered.
   */
  private void work(RenderTile tile) {
    Scene scene = manager.getBufferedScene();

    Random random = state.random;
    Ray ray = state.ray;

    int width = scene.canvasWidth();
    int height = scene.canvasHeight();

    double halfWidth = width / (2.0 * height);
    double invHeight = 1.0 / height;

    double[] samples = scene.getSampleBuffer();
    final Camera cam = scene.camera();

    if (scene.getMode() != RenderMode.PREVIEW) {

      // for debugging purposes - map of pixel coord to how many times it was processed this run
      Map<String, Integer> pixels = new HashMap<>();

      int tile_width = tile.x1 - tile.x0;
      int tile_height = tile.y1 - tile.y0;

      // The budget (how many rays we can cast) for this pass
      int budget = tile_height * tile_width * RenderConstants.SPP_PER_PASS;

      try (Writer writer = Files.newBufferedWriter(pixel_file, StandardCharsets.UTF_8,
                     StandardOpenOption.APPEND);
          Formatter formatter = new Formatter(writer)) {

        for (int p = 0; p < budget; ++p){

          Vector4 pixel = tile.pixelQueue.poll(); // pop top pixel off the queue
          //int x = tile.x0 + p % tile_width;
          //int y = tile.y0 + p / tile_width;
          int x = (int) pixel.x;
          int y = (int) pixel.y;

          //*
          String pkey = x+" "+y;
          pixels.putIfAbsent(pkey, 0);
          pixels.put(pkey, pixels.get(pkey) + 1);
          //*/

          int offset = y * width * 3 + x * 3;

          double sr = 0;
          double sg = 0;
          double sb = 0;
          double sr2 = 0, sg2 = 0, sb2 = 0;

          double oy = random.nextDouble();
          double ox = random.nextDouble();

          cam.calcViewRay(ray, random, (-halfWidth + (x + ox) * invHeight),
          (-.5 + (y + oy) * invHeight));

          scene.rayTrace(rayTracer, state);

          sr += ray.color.x;
          sg += ray.color.y;
          sb += ray.color.z;
          sr2 += ray.color.x * ray.color.x;
          sg2 += ray.color.y * ray.color.y;
          sb2 += ray.color.z * ray.color.z;

          int n_samples = scene.sampleCounts[offset/3] + 1;
          scene.sampleCounts[offset/3] = n_samples;

          double sinv = 1.0 / n_samples;

          double r_avg = (samples[offset + 0] * (n_samples-1) + sr) * sinv;
          double g_avg = (samples[offset + 1] * (n_samples-1) + sg) * sinv;
          double b_avg = (samples[offset + 2] * (n_samples-1) + sb) * sinv;
          samples[offset + 0] = r_avg;
          samples[offset + 1] = g_avg;
          samples[offset + 2] = b_avg;

          double r_avg_sq = (scene.squaredSamples[offset] * (n_samples-1) + sr2) * sinv;
          double g_avg_sq = (scene.squaredSamples[offset + 1] * (n_samples-1) + sg2) * sinv;
          double b_avg_sq = (scene.squaredSamples[offset + 2] * (n_samples-1) + sb2) * sinv;
          scene.squaredSamples[offset] = r_avg_sq;
          scene.squaredSamples[offset + 1] = g_avg_sq;
          scene.squaredSamples[offset + 2] = b_avg_sq;

          //"large" samples
          final double large_threshold = 1.5;

          if(sr >= large_threshold) {
            scene.largeAvg[offset + 0] = (scene.largeAvg[offset + 0]*scene.largeCounts[offset + 0] + sr)/(scene.largeCounts[offset + 0]+1);
            scene.largeCounts[offset + 0] += 1;
          }
          if(sg >= large_threshold) {
            scene.largeCounts[offset + 1] += 1;
          }
          if(sb >= large_threshold) {
            scene.largeCounts[offset + 2] += 1;
          }
          int large_r = scene.largeCounts[offset + 0];
          int large_g = scene.largeCounts[offset + 1];
          int large_b = scene.largeCounts[offset + 2];
          //double small_r_avg = (r_avg*n_samlpes - scene.largeAvg[offset + 0]*large_r)/(n_samlpes-scene.largeCounts[offset + 0])

          double max_interval = 1-FastMath.pow(0.05, sinv); // default max reasonable assumption, based on number of samples


          // compute confidence metrics:
          // Note: this metric only makes sense for n >= 2 samples (otherwise, it is 0 and/or NaN)
          if (scene.sampleCounts[offset/3] >= 2) {
            // compute d (for each of r, g, b) s.t. 95% confidence interval for the data is 2d wide
            double r_d = 2*FastMath.sqrt((r_avg_sq - r_avg*r_avg)/(n_samples));
            double g_d = 2*FastMath.sqrt((g_avg_sq - g_avg*g_avg)/(n_samples));
            double b_d = 2*FastMath.sqrt((b_avg_sq - b_avg*b_avg)/(n_samples));

            double r_l = r_avg-r_d;
            double r_u = r_avg+r_d;

            double g_l = g_avg-g_d;
            double g_u = g_avg+g_d;

            double b_l = b_avg-b_d;
            double b_u = b_avg+b_d;

            double[] p_l = new double[3], p_u = new double[3];
            double[] l = {r_l, g_l, b_l};
            scene.postProcessPixel(l,p_l);

            double[] u = {r_u, g_u, b_u};
            scene.postProcessPixel(u,p_u);

            r_l = QuickMath.max(p_l[0], 0);
            r_u = QuickMath.min(p_u[0], 1);

            g_l = QuickMath.max(p_l[1], 0);
            g_u = QuickMath.min(p_u[1], 1);

            b_l = QuickMath.max(p_l[2], 0);
            b_u = QuickMath.min(p_u[2], 1);


            // base computation off the maximum observed noise in the pixel:
            max_interval = getMax(new double[]{r_u-r_l, g_u-g_l, b_u-b_l, max_interval});
            scene.intervals[offset/3] = max_interval;

            double b_p_r = 0;
            double b_r_u = 0;
            double b_r_l = 0;
            double hit_u = 0;
            double hit_l = 0;
            double p_hit = ((double)large_r)/n_samples;  // probability of "hitting" emitter/generating large number
            if(large_r > 0 && x == saveX && y == saveY) {
              // compute binomial option:
              // for each channel, if we assume the data came from a "convenient" binomial distribution, what's the actual likelihood of seeing this data?
              BinomialDistribution bin_r = new BinomialDistribution(n_samples, ((double)large_r)/n_samples);
              b_p_r = bin_r.probability(large_r);

              // beta distribution credible interval (upper/lower limit belief about p(hit) )
              BetaDistribution bd= new BetaDistribution(large_r, n_samples - large_r);
              // Upper and lower bound on our belief of p(hit)
              hit_u = bd.inverseCumulativeProbability(0.975);
              hit_l = bd.inverseCumulativeProbability(0.025);
              // TODO: also factor in small average?..
              b_r_u = hit_u * scene.largeAvg[offset + 0];
              b_r_l = hit_l * scene.largeAvg[offset + 0];

            }

            // write pixel data to file:

            if (x == saveX && y == saveY) {
              int rgb = 0; // red
              switch (rgb) {
                case 0:
                  formatter.format("%.15f, %.15f, %.15f, %.15f, %.15f, %.15f, %.15f, %.15f%n", sr, r_avg, r_avg + r_d, r_avg - r_d, b_r_u, b_r_l, scene.largeAvg[offset + 0], p_hit);
                  break;
                case 1:
                  formatter.format("%.15f, %.15f, %.15f, %.15f%n", sg, g_avg, g_avg + g_d, g_avg - g_d);
                  break;
                case 2:
                  formatter.format("%.15f, %.15f, %.15f, %.15f%n", sb, b_avg, b_avg + b_d, b_avg - b_d);
              }
              formatter.flush();
            }
          }

          // put same pixel back into queue with new noise and sample count value:
          tile.pixelQueue.add(new Vector4(pixel.x, pixel.y, max_interval, n_samples));

          if (scene.shouldFinalizeBuffer()) {
            scene.finalizePixel(x, y);
          }
          if(p == 0) {
            //System.out.println(x+" "+y+" "+n_samples);
          }
        }
        formatter.close();
      }catch (IOException e) {
        e.printStackTrace();
      }
      System.out.println(pixels.size()); // TODO: output to a file.
    } else {
      // Preview rendering.
      Ray target = new Ray(ray);
      boolean hit = scene.traceTarget(target);
      int tx = (int) QuickMath.floor(target.o.x + target.d.x * Ray.OFFSET);
      int ty = (int) QuickMath.floor(target.o.y + target.d.y * Ray.OFFSET);
      int tz = (int) QuickMath.floor(target.o.z + target.d.z * Ray.OFFSET);

      for (int x = tile.x0; x < tile.x1; ++x)
      for (int y = tile.y0; y < tile.y1; ++y) {

        boolean firstFrame = scene.previewCount > 1;
        if (firstFrame) {
          if (((x + y) % 2) == 0) {
            continue;
          }
        } else {
          if (((x + y) % 2) != 0) {
            scene.finalizePixel(x, y);
            continue;
          }
        }

        // Draw the crosshairs.
        if (x == width / 2 && (y >= height / 2 - 5 && y <= height / 2 + 5) || y == height / 2 && (
              x >= width / 2 - 5 && x <= width / 2 + 5)) {
          samples[(y * width + x) * 3 + 0] = 0xFF;
          samples[(y * width + x) * 3 + 1] = 0xFF;
          samples[(y * width + x) * 3 + 2] = 0xFF;
          scene.finalizePixel(x, y);
          continue;
        }

        cam.calcViewRay(ray, random, (-halfWidth + (double) x * invHeight),
              (-.5 + (double) y * invHeight));

        scene.rayTrace(previewRayTracer, state);

        // Target highlighting.
        int rx = (int) QuickMath.floor(ray.o.x + ray.d.x * Ray.OFFSET);
        int ry = (int) QuickMath.floor(ray.o.y + ray.d.y * Ray.OFFSET);
        int rz = (int) QuickMath.floor(ray.o.z + ray.d.z * Ray.OFFSET);
        if (hit && tx == rx && ty == ry && tz == rz) {
          ray.color.x = 1 - ray.color.x;
          ray.color.y = 1 - ray.color.y;
          ray.color.z = 1 - ray.color.z;
          ray.color.w = 1;
        }

        samples[(y * width + x) * 3 + 0] = ray.color.x;
        samples[(y * width + x) * 3 + 1] = ray.color.y;
        samples[(y * width + x) * 3 + 2] = ray.color.z;

        scene.finalizePixel(x, y);

        if (firstFrame) {
          if (y % 2 == 0 && x < (width - 1)) {
            // Copy the current pixel to the next one.
            scene.copyPixel(y * width + x, 1);
          } else if (y % 2 != 0 && x > 0) {
            // Copy the next pixel to this pixel.
            scene.copyPixel(y * width + x, -1);
          }
        }
      }
    }
  }

}
