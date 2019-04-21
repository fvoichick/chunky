package se.llbit.chunky.renderer;

import java.util.Comparator;
import se.llbit.chunky.renderer.scene.Scene;

public class PointComparator implements Comparator<Integer> {

  private final Comparator<Integer> delegate;
  private final Scene scene;

  public PointComparator(Scene scene) {
    this.scene = scene;
    delegate = Comparator.comparingDouble(this::getMaxConfidenceWidth).reversed().thenComparingInt(offset -> scene.getCountBuffer()[offset]);
  }

  public int compare(Integer offsetA, Integer offsetB) {
    return delegate.compare(offsetA, offsetB);
  }

  private double getMaxConfidenceWidth(int pixelOffset) {
    assert pixelOffset % 3 == 0;
    double rd = getColorConfidenceWidth(pixelOffset);
    double gd = getColorConfidenceWidth(pixelOffset + 1);
    double bd = getColorConfidenceWidth(pixelOffset + 2);
    return Math.max(rd, Math.max(gd, bd));
  }

  public double getColorConfidenceWidth(int colorOffset) {
    int count = scene.getCountBuffer()[colorOffset];
    if (count < 2) {
      return Double.POSITIVE_INFINITY;
    }
    double samples = scene.getSampleBuffer()[colorOffset];
    double squared = scene.getSquaredSampleBuffer()[colorOffset];

    double mean = samples / count;
    double var = (count * squared - samples * samples) / (count * (count - 1));
    double t = TValue.INSTANCE.get(count);
    double d1 = t * Math.sqrt(var / count);
    double min = Math.max(mean - d1, 0.0), max = Math.min(mean + d1, 1.0);
    d1 = (max - min) / 2;
    double d2 = 1.0 - Math.pow(0.05, 1.0 / count);
    return Math.max(d1, d2);
  }
}
