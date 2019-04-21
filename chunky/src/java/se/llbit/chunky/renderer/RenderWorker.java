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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import se.llbit.chunky.renderer.scene.Camera;
import se.llbit.chunky.renderer.scene.RayTracer;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.log.Log;
import se.llbit.math.Ray;
import se.llbit.math.Vector4;

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
  }

  @Override
  public void run() {
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

    int[] counts = scene.getCountBuffer();
    double[] samples = scene.getSampleBuffer();
    double[] squaredSamples = scene.getSquaredSampleBuffer();
    final Camera cam = scene.camera();

    if (scene.getMode() != RenderMode.PREVIEW) {
      for (int i = 0, n = (tile.y1 - tile.y0) * (tile.x1 - tile.x0); i < n; i++) {
        int offset = scene.bestOffsets.remove();
        int x = (offset / 3) % width, y = (offset / 3) / width;
        double sr = 0, sg = 0, sb = 0;
        double sr2 = 0.0, sg2 = 0.0, sb2 = 0.0;

        for (int j = 0; j < RenderConstants.SPP_PER_PASS; ++j) {
          double oy = random.nextDouble();
          double ox = random.nextDouble();

          cam.calcViewRay(ray, random, (-halfWidth + (x + ox) * invHeight),
              (-.5 + (y + oy) * invHeight));

          scene.rayTrace(rayTracer, state);

          Vector4 color = ray.color;
          double r = color.x, g = color.y, b = color.z;
          sr += r;
          sg += g;
          sb += b;
          sr2 += r * r;
          sg2 += g * g;
          sb2 += b * b;
        }

        counts[offset] += RenderConstants.SPP_PER_PASS;
        counts[offset + 1] += RenderConstants.SPP_PER_PASS;
        counts[offset + 2] += RenderConstants.SPP_PER_PASS;
        samples[offset] += sr;
        samples[offset + 1] += sg;
        samples[offset + 2] += sb;
        squaredSamples[offset] += sr2;
        squaredSamples[offset + 1] += sg2;
        squaredSamples[offset + 2] += sb2;

        if (scene.shouldFinalizeBuffer()) {
          scene.finalizePixel(x, y);
        }
        if (i % 2000 == 0) {
          PointComparator comp = new PointComparator(scene);
          System.out.println("Conf:    " + IntStream.range(0, width * height * 3).mapToDouble(
              comp::getColorConfidenceWidth).summaryStatistics());
          System.out.println("Counts:  " + Arrays.stream(counts).summaryStatistics());
          System.out.println("Samples: " + Arrays.stream(samples).summaryStatistics());
          System.out.println("Ratio:   " + IntStream.range(0, width * height * 3).mapToDouble(o -> samples[o] / counts[o]).summaryStatistics());
          System.out.println();
        }

        scene.bestOffsets.add(offset);

      }

    }
  }

}
