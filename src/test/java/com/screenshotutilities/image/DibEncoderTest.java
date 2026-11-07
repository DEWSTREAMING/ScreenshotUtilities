package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class DibEncoderTest {
    @Test
    void headerMatches24BitBiRgbSpec() {
        byte[] dib = DibEncoder.encode((x, y) -> 0xFF000000, 5, 3);
        ByteBuffer buf = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);

        int expectedStride = 16; // 5 * 3 = 15, padded up to 16
        assertEquals(40, buf.getInt(0));
        assertEquals(5, buf.getInt(4));
        assertEquals(3, buf.getInt(8));
        assertEquals(1, buf.getShort(12));
        assertEquals(24, buf.getShort(14));
        assertEquals(0, buf.getInt(16));
        assertEquals(expectedStride * 3, buf.getInt(20));
        assertEquals(0, buf.getInt(24));
        assertEquals(0, buf.getInt(28));
        assertEquals(0, buf.getInt(32));
        assertEquals(0, buf.getInt(36));
        assertEquals(40 + expectedStride * 3, dib.length);
    }

    @Test
    void rowStridePadsEveryWidthModulo4ToMultipleOf4Bytes() {
        assertEquals(4, DibEncoder.rowStride(1));  // 3 -> 4 (1 pad byte)
        assertEquals(8, DibEncoder.rowStride(2));  // 6 -> 8 (2 pad bytes)
        assertEquals(12, DibEncoder.rowStride(3)); // 9 -> 12 (3 pad bytes)
        assertEquals(12, DibEncoder.rowStride(4)); // 12 -> 12 (0 pad bytes)

        // Verify padding bytes are zeroed in encoded output for width = 1 (1 pad byte) and width = 2 (2 pad bytes).
        byte[] w1 = DibEncoder.encode((x, y) -> 0xFFFFFFFF, 1, 1);
        assertEquals(0, w1[40 + 3]);

        byte[] w2 = DibEncoder.encode((x, y) -> 0xFFFFFFFF, 2, 1);
        assertEquals(0, w2[40 + 6]);
        assertEquals(0, w2[40 + 7]);
    }

    @Test
    void convertsNativeImageAbgrToDibBgrAndDropsAlpha() {
        // NativeImage.getPixelRGBA returns 0xAABBGGRR: R=0x12, G=0x34, B=0x56, A=0x78.
        int abgr = 0x78563412;
        byte[] dib = DibEncoder.encode((x, y) -> abgr, 1, 1);

        // 24-bit BI_RGB stores B, G, R in that byte order, with no alpha byte.
        assertEquals(0x56, dib[40] & 0xFF);
        assertEquals(0x34, dib[41] & 0xFF);
        assertEquals(0x12, dib[42] & 0xFF);
        assertEquals(0x00, dib[43] & 0xFF); // row padding byte, not alpha
    }

    @Test
    void writesRowsBottomUpFromLastRowToFirst() {
        // 1x3 image: top row red (R=0xAA), middle row green (G=0xBB), bottom row blue (B=0xCC) in ABGR order.
        int[] rows = {0xFF0000AA, 0xFF00BB00, 0xFFCC0000};
        byte[] dib = DibEncoder.encode((x, y) -> rows[y], 1, 3);
        int stride = 4;

        // DIB row 0 is the bottom row of the source image (y = 2, blue).
        assertEquals(0xCC, dib[40] & 0xFF);
        assertEquals(0x00, dib[41] & 0xFF);
        assertEquals(0x00, dib[42] & 0xFF);

        // DIB row 1 is the middle row (y = 1, green).
        assertEquals(0x00, dib[40 + stride] & 0xFF);
        assertEquals(0xBB, dib[40 + stride + 1] & 0xFF);
        assertEquals(0x00, dib[40 + stride + 2] & 0xFF);

        // DIB row 2 is the top row (y = 0, red).
        assertEquals(0x00, dib[40 + 2 * stride] & 0xFF);
        assertEquals(0x00, dib[40 + 2 * stride + 1] & 0xFF);
        assertEquals(0xAA, dib[40 + 2 * stride + 2] & 0xFF);
    }

    @Test
    void rejectsEmptyDimensions() {
        assertThrows(IllegalArgumentException.class, () -> DibEncoder.rowStride(0));
        assertThrows(IllegalArgumentException.class, () -> DibEncoder.encode((x, y) -> 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> DibEncoder.encode((x, y) -> 0, 10, 0));
    }
}
