package com.screenshotutilities.image;

/**
 * Encodes an image into a Windows {@code CF_DIB} payload: a 40-byte {@code BITMAPINFOHEADER} followed by 24-bit {@code BI_RGB}
 * pixels in bottom-up row order, with each row padded to a 4-byte boundary and no alpha channel.
 *
 * <p>{@code NativeImage.getPixelRGBA} returns packed ints in ABGR order ({@code 0xAABBGGRR}: red in the lowest byte, then
 * green, then blue). A 24-bit DIB stores each pixel as three bytes in blue, green, red order.</p>
 */
public final class DibEncoder {
    public static final int HEADER_SIZE = 40;
    public static final short PLANES = 1;
    public static final short BIT_COUNT = 24;
    public static final int BI_RGB = 0;

    private DibEncoder() {
    }

    /** Byte length of one 24-bit DIB row for {@code width} pixels, padded up to a multiple of 4 bytes. */
    public static int rowStride(int width) {
        if (width <= 0) {
            throw new IllegalArgumentException("Invalid width: " + width);
        }
        return (width * 3 + 3) & ~3;
    }

    /** Encodes a {@code width x height} image whose pixels are packed in {@code NativeImage}'s ABGR order. */
    public static byte[] encode(Downscale.PixelSource abgrSource, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Empty image " + width + "x" + height);
        }
        int stride = rowStride(width);
        long imageBytes = (long) stride * height;
        long totalBytes = HEADER_SIZE + imageBytes;
        if (totalBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Image too large for DIB: " + width + "x" + height);
        }

        byte[] out = new byte[(int) totalBytes];
        putIntLE(out, 0, HEADER_SIZE);
        putIntLE(out, 4, width);
        putIntLE(out, 8, height);
        putShortLE(out, 12, PLANES);
        putShortLE(out, 14, BIT_COUNT);
        putIntLE(out, 16, BI_RGB);
        putIntLE(out, 20, (int) imageBytes);

        for (int row = 0; row < height; row++) {
            int srcY = height - 1 - row;
            int pos = HEADER_SIZE + row * stride;
            for (int x = 0; x < width; x++) {
                int abgr = abgrSource.get(x, srcY);
                out[pos++] = (byte) ((abgr >>> 16) & 0xFF);
                out[pos++] = (byte) ((abgr >>> 8) & 0xFF);
                out[pos++] = (byte) (abgr & 0xFF);
            }
        }
        return out;
    }

    private static void putShortLE(byte[] buf, int offset, short value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static void putIntLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
