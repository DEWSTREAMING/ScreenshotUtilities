package com.screenshotutilities.image;

/**
 * Zoom and position of a picture shown inside an area. The base rect is where the picture sits at fit (100%), the area is
 * where it may be drawn. The position is kept as the picture point in the middle of the area, in fractions of the picture,
 * so it survives the area changing size. Everything is clamped again on each use.
 */
public final class ZoomView {
    public static final double MIN_ZOOM = 1.0;
    public static final double MAX_ZOOM = 8.0;
    public static final double STEP = 1.25;
    /** Below this the zoom counts as fit, so rounding never leaves a nearly-1 zoom behind. */
    private static final double FIT_EPSILON = 1e-6;

    private double zoom = MIN_ZOOM;
    private double centerX = 0.5;
    private double centerY = 0.5;

    /** A plain int rect, left and top inclusive, right and bottom exclusive. */
    public record Box(int left, int top, int right, int bottom) {
        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        public boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        /** The overlap of two boxes, or an empty box at this one's corner. */
        public Box intersect(Box other) {
            int l = Math.max(left, other.left);
            int t = Math.max(top, other.top);
            int r = Math.min(right, other.right);
            int b = Math.min(bottom, other.bottom);
            return r > l && b > t ? new Box(l, t, r, b) : new Box(left, top, left, top);
        }
    }

    /** Where a scroll bar thumb sits along its track, in screen units. */
    public record Thumb(double start, double end) {
    }

    public double zoom() {
        return zoom;
    }

    public boolean isZoomed() {
        return zoom > MIN_ZOOM + FIT_EPSILON;
    }

    public int percent() {
        return (int) Math.round(zoom * 100);
    }

    public void reset() {
        zoom = MIN_ZOOM;
        centerX = 0.5;
        centerY = 0.5;
    }

    /** The picture's rect on screen. Can be larger than the area and start above or left of it. */
    public Box rect(Box base, Box area) {
        if (!isZoomed()) {
            reset();
            return base;
        }
        double[] exact = exactRect(base, area);
        int l = (int) Math.round(exact[0]);
        int t = (int) Math.round(exact[1]);
        return new Box(l, t, l + Math.max(1, (int) Math.round(exact[2])), t + Math.max(1, (int) Math.round(exact[3])));
    }

    /** Left, top, width and height before rounding, clamped. Zooming works from these so rounding never adds up. */
    private double[] exactRect(Box base, Box area) {
        if (!isZoomed()) {
            reset();
            return new double[] {base.left(), base.top(), base.width(), base.height()};
        }
        double w = base.width() * zoom;
        double h = base.height() * zoom;
        double left = clampStart(area.left(), area.width(), w, centerX);
        double top = clampStart(area.top(), area.height(), h, centerY);
        centerX = (area.left() + area.width() / 2.0 - left) / w;
        centerY = (area.top() + area.height() / 2.0 - top) / h;
        return new double[] {left, top, w, h};
    }

    /** The part of the picture that shows. */
    public Box visible(Box base, Box area) {
        return rect(base, area).intersect(area);
    }

    /**
     * Zooms by {@code STEP} per notch (fractional notches allowed), keeping the picture point under the mouse where it is
     * unless clamping has to move it.
     */
    public void zoomAt(double notches, double mouseX, double mouseY, Box base, Box area) {
        if (base.width() <= 0 || base.height() <= 0) {
            return;
        }
        double[] before = exactRect(base, area);
        double fx = (mouseX - before[0]) / before[2];
        double fy = (mouseY - before[1]) / before[3];
        double next = Math.clamp(zoom * Math.pow(STEP, notches), MIN_ZOOM, MAX_ZOOM);
        if (next == zoom) {
            return;
        }
        zoom = next;
        double w = base.width() * zoom;
        double h = base.height() * zoom;
        double left = mouseX - fx * w;
        double top = mouseY - fy * h;
        centerX = (area.left() + area.width() / 2.0 - left) / w;
        centerY = (area.top() + area.height() / 2.0 - top) / h;
        exactRect(base, area);
    }

    /** Moves the picture by a screen distance, the way a drag does. */
    public void panBy(double dx, double dy, Box base, Box area) {
        if (!isZoomed()) {
            return;
        }
        exactRect(base, area);
        centerX -= dx / (base.width() * zoom);
        centerY -= dy / (base.height() * zoom);
        exactRect(base, area);
    }

    public boolean canScrollX(Box base, Box area) {
        return isZoomed() && base.width() * zoom > area.width() + 0.5;
    }

    public boolean canScrollY(Box base, Box area) {
        return isZoomed() && base.height() * zoom > area.height() + 0.5;
    }

    /** The thumb on a track running along the area's width, from {@code trackStart} for {@code trackLength}. */
    public Thumb thumbX(Box base, Box area, double trackStart, double trackLength) {
        Box r = rect(base, area);
        return thumb(area.left() - r.left(), area.width(), r.width(), trackStart, trackLength);
    }

    public Thumb thumbY(Box base, Box area, double trackStart, double trackLength) {
        Box r = rect(base, area);
        return thumb(area.top() - r.top(), area.height(), r.height(), trackStart, trackLength);
    }

    /** Moves the view so the thumb follows a drag of {@code delta} along a track of {@code trackLength}. */
    public void dragThumbX(double delta, Box base, Box area, double trackLength) {
        Box r = rect(base, area);
        panBy(-thumbToPicture(delta, area.width(), r.width(), trackLength), 0, base, area);
    }

    public void dragThumbY(double delta, Box base, Box area, double trackLength) {
        Box r = rect(base, area);
        panBy(0, -thumbToPicture(delta, area.height(), r.height(), trackLength), base, area);
    }

    private static Thumb thumb(double scrolled, double view, double content, double trackStart, double trackLength) {
        if (content <= view) {
            return new Thumb(trackStart, trackStart + trackLength);
        }
        double size = Math.max(Math.min(8, trackLength), trackLength * view / content);
        double room = trackLength - size;
        double start = trackStart + room * Math.clamp(scrolled / (content - view), 0, 1);
        return new Thumb(start, start + size);
    }

    private static double thumbToPicture(double delta, double view, double content, double trackLength) {
        if (content <= view) {
            return 0;
        }
        double size = Math.max(Math.min(8, trackLength), trackLength * view / content);
        double room = trackLength - size;
        return room <= 0 ? 0 : delta * (content - view) / room;
    }

    /** Centred when the picture fits along this axis, otherwise kept so no empty space shows inside the area. */
    private static double clampStart(double areaStart, double areaSize, double size, double center) {
        if (size <= areaSize) {
            return areaStart + (areaSize - size) / 2.0;
        }
        double start = areaStart + areaSize / 2.0 - center * size;
        return Math.clamp(start, areaStart + areaSize - size, areaStart);
    }
}
