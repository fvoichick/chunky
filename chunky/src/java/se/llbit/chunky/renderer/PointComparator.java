package se.llbit.chunky.renderer;

import java.util.Comparator;
import se.llbit.chunky.renderer.scene.Scene;

public class PointComparator implements Comparator<PixelLocation> {
  private final Scene scene;
  public PointComparator(Scene scene) {
    this.scene = scene;
  }

  @Override
  public int compare(PixelLocation a, PixelLocation b) {
    int ax = a.getX(), ay = a.getY(), bx = b.getX(), by = b.getY();
    int[] counts = scene.getCountBuffer();
    double[] samples = scene.getSampleBuffer();
    double[] squaredSamples = scene.getSquaredSampleBuffer();
  }
}
