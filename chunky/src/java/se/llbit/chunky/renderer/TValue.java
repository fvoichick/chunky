package se.llbit.chunky.renderer;

import java.util.concurrent.atomic.AtomicIntegerArray;
import org.apache.commons.math3.distribution.TDistribution;

public enum TValue {
  INSTANCE;
  private final double[] tValues = new double[100_000];
  public double get(int n) {
    if (n >= 100_000)
      return get(99_999);
    double result = tValues[n];
    if (result == 0) {
      result = new TDistribution(n - 1).inverseCumulativeProbability(0.95);
      tValues[n] = result;
    }
    return result;
  }
}
