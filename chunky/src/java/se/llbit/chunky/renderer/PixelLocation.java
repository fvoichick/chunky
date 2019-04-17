package se.llbit.chunky.renderer;

public final class PixelLocation {
  private final int x, y;

  public PixelLocation(int x, int y) {
    this.x = x;
    this.y = y;
  }

  public int getX() {
    return x;
  }

  public int getY() {
    return y;
  }
}
