package com.screenshotutilities.image;

/**
 * A rectangle inside a full-size screenshot, measured in image pixels from the top-left corner.
 */
public record CropRect(int x, int y, int width, int height) {
    public static final int MIN_SIZE = 16;

    public enum Handle {
        TOP_LEFT(true, false, true, false),
        TOP(false, false, true, false),
        TOP_RIGHT(false, true, true, false),
        RIGHT(false, true, false, false),
        BOTTOM_RIGHT(false, true, false, true),
        BOTTOM(false, false, false, true),
        BOTTOM_LEFT(true, false, false, true),
        LEFT(true, false, false, false);

        private final boolean movesLeft;
        private final boolean movesRight;
        private final boolean movesTop;
        private final boolean movesBottom;

        Handle(boolean movesLeft, boolean movesRight, boolean movesTop, boolean movesBottom) {
            this.movesLeft = movesLeft;
            this.movesRight = movesRight;
            this.movesTop = movesTop;
            this.movesBottom = movesBottom;
        }
    }

    public CropRect {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid crop rect: " + x + "," + y + " " + width + "x" + height);
        }
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean isFullImage(int imageWidth, int imageHeight) {
        return x == 0 && y == 0 && width == imageWidth && height == imageHeight;
    }

    public String sizeText() {
        return width + " x " + height;
    }

    /** Clamps this box so it fits within {@code imageWidth x imageHeight} and is at least {@link #MIN_SIZE} on each axis. */
    public CropRect clamp(int imageWidth, int imageHeight) {
        checkImageSize(imageWidth, imageHeight);
        int minW = Math.min(MIN_SIZE, imageWidth);
        int minH = Math.min(MIN_SIZE, imageHeight);
        int w = Math.clamp(width, minW, imageWidth);
        int h = Math.clamp(height, minH, imageHeight);
        int nx = Math.clamp(x, 0, imageWidth - w);
        int ny = Math.clamp(y, 0, imageHeight - h);
        return new CropRect(nx, ny, w, h);
    }

    /**
     * Builds a clamped box dragged from {@code (anchorX, anchorY)} to {@code (currentX, currentY)} in image coordinates.
     * The anchor corner stays put unless the box hits the image edge while enforcing the minimum size.
     */
    public static CropRect fromDrag(int anchorX, int anchorY, int currentX, int currentY, int imageWidth, int imageHeight) {
        checkImageSize(imageWidth, imageHeight);
        int minW = Math.min(MIN_SIZE, imageWidth);
        int minH = Math.min(MIN_SIZE, imageHeight);
        int ax = Math.clamp(anchorX, 0, imageWidth);
        int ay = Math.clamp(anchorY, 0, imageHeight);
        int cx = Math.clamp(currentX, 0, imageWidth);
        int cy = Math.clamp(currentY, 0, imageHeight);

        int left;
        int right;
        if (cx >= ax) {
            left = ax;
            right = Math.max(left + minW, cx);
            if (right > imageWidth) {
                right = imageWidth;
                left = Math.max(0, right - minW);
            }
        } else {
            right = ax;
            left = Math.min(right - minW, cx);
            if (left < 0) {
                left = 0;
                right = Math.min(imageWidth, left + minW);
            }
        }

        int top;
        int bottom;
        if (cy >= ay) {
            top = ay;
            bottom = Math.max(top + minH, cy);
            if (bottom > imageHeight) {
                bottom = imageHeight;
                top = Math.max(0, bottom - minH);
            }
        } else {
            bottom = ay;
            top = Math.min(bottom - minH, cy);
            if (top < 0) {
                top = 0;
                bottom = Math.min(imageHeight, top + minH);
            }
        }

        return new CropRect(left, top, right - left, bottom - top);
    }

    /** Moves the box by {@code (dx, dy)} image pixels without changing its size, stopping at the image edges. */
    public CropRect move(int dx, int dy, int imageWidth, int imageHeight) {
        checkImageSize(imageWidth, imageHeight);
        int minW = Math.min(MIN_SIZE, imageWidth);
        int minH = Math.min(MIN_SIZE, imageHeight);
        int w = Math.clamp(width, minW, imageWidth);
        int h = Math.clamp(height, minH, imageHeight);
        int nx = Math.clamp(x + dx, 0, imageWidth - w);
        int ny = Math.clamp(y + dy, 0, imageHeight - h);
        return new CropRect(nx, ny, w, h);
    }

    /** Resizes the box by moving {@code handle} by {@code (dx, dy)} image pixels while keeping opposite edges fixed. */
    public CropRect resize(Handle handle, int dx, int dy, int imageWidth, int imageHeight) {
        checkImageSize(imageWidth, imageHeight);
        int minW = Math.min(MIN_SIZE, imageWidth);
        int minH = Math.min(MIN_SIZE, imageHeight);
        CropRect base = clamp(imageWidth, imageHeight);
        int left = base.x;
        int right = base.right();
        int top = base.y;
        int bottom = base.bottom();

        if (handle.movesLeft) {
            left = Math.clamp(left + dx, 0, right - minW);
        } else if (handle.movesRight) {
            right = Math.clamp(right + dx, left + minW, imageWidth);
        }

        if (handle.movesTop) {
            top = Math.clamp(top + dy, 0, bottom - minH);
        } else if (handle.movesBottom) {
            bottom = Math.clamp(bottom + dy, top + minH, imageHeight);
        }

        return new CropRect(left, top, right - left, bottom - top);
    }

    /**
     * Maps this image-space rectangle to a target coordinate space (such as the downscaled preview texture or the on-screen
     * preview box) of size {@code targetWidth x targetHeight}.
     */
    public CropRect scaleTo(int imageWidth, int imageHeight, int targetWidth, int targetHeight) {
        checkImageSize(imageWidth, imageHeight);
        checkImageSize(targetWidth, targetHeight);
        int left = Math.clamp((int) Math.round((double) x * targetWidth / imageWidth), 0, targetWidth - 1);
        int right = Math.clamp((int) Math.round((double) right() * targetWidth / imageWidth), left + 1, targetWidth);
        int top = Math.clamp((int) Math.round((double) y * targetHeight / imageHeight), 0, targetHeight - 1);
        int bottom = Math.clamp((int) Math.round((double) bottom() * targetHeight / imageHeight), top + 1, targetHeight);
        return new CropRect(left, top, right - left, bottom - top);
    }

    /** Copies the pixels inside this crop from {@code src} row by row, top to bottom. */
    public int[] extract(Downscale.PixelSource src) {
        int[] out = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                out[row * width + col] = src.get(x + col, y + row);
            }
        }
        return out;
    }

    /** Copies the pixels inside this crop from a row-major {@code src} array of width {@code srcWidth}. */
    public int[] extract(int[] src, int srcWidth) {
        int[] out = new int[width * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(src, (y + row) * srcWidth + x, out, row * width, width);
        }
        return out;
    }

    private static void checkImageSize(int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("Empty image " + imageWidth + "x" + imageHeight);
        }
    }
}
