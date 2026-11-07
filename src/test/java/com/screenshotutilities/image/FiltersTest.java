package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FiltersTest {
    private static final FilterSettings BW = FilterSettings.NONE.pickBlackAndWhite();
    private static final int ORANGE_PIXEL = pack(255, 0, 128, 255);
    private static final int BLUE_PIXEL = pack(255, 255, 0, 0);
    private static final int GREEN_PIXEL = pack(255, 0, 255, 0);

    private static FilterSettings keep(KeepColour... colours) {
        FilterSettings settings = FilterSettings.NONE;
        for (KeepColour colour : colours) {
            settings = settings.toggleColour(colour);
        }
        return settings;
    }

    private static List<FilterSettings> allSingleChoices() {
        List<FilterSettings> all = new ArrayList<>(List.of(FilterSettings.NONE, BW));
        for (KeepColour colour : KeepColour.values()) {
            all.add(keep(colour));
        }
        return all;
    }

    private static int applyOne(FilterSettings settings, int pixel) {
        int[] one = {pixel};
        Filters.applyInPlace(settings, one, 1, 1);
        return one[0];
    }

    private static int[] filled(int width, int height, int pixel) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, pixel);
        return pixels;
    }

    private static int pack(int a, int b, int g, int r) {
        return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
    }

    private static int red(int pixel) {
        return pixel & 0xFF;
    }

    private static int green(int pixel) {
        return (pixel >> 8) & 0xFF;
    }

    private static int blue(int pixel) {
        return (pixel >> 16) & 0xFF;
    }

    private static int alpha(int pixel) {
        return (pixel >> 24) & 0xFF;
    }

    private static boolean isGrey(int pixel) {
        int r = red(pixel);
        int g = green(pixel);
        int b = blue(pixel);
        return r == g && g == b;
    }

    @Test
    void blackAndWhiteOnKnownColours() {
        // Pure red: round(0.2126 * 255) = 54
        int red = pack(255, 0, 0, 255);
        int bwRed = applyOne(BW, red);
        assertEquals(54, red(bwRed));
        assertEquals(54, green(bwRed));
        assertEquals(54, blue(bwRed));
        assertEquals(255, alpha(bwRed));

        // Pure green: round(0.7152 * 255) = 182
        int green = pack(255, 0, 255, 0);
        int bwGreen = applyOne(BW, green);
        assertEquals(182, red(bwGreen));
        assertEquals(182, green(bwGreen));
        assertEquals(182, blue(bwGreen));
        assertEquals(255, alpha(bwGreen));

        // Pure blue: round(0.0722 * 255) = 18
        int blue = pack(255, 255, 0, 0);
        int bwBlue = applyOne(BW, blue);
        assertEquals(18, red(bwBlue));
        assertEquals(18, green(bwBlue));
        assertEquals(18, blue(bwBlue));
        assertEquals(255, alpha(bwBlue));

        // White
        int white = pack(255, 255, 255, 255);
        int bwWhite = applyOne(BW, white);
        assertEquals(255, red(bwWhite));
        assertEquals(255, green(bwWhite));
        assertEquals(255, blue(bwWhite));

        // Black
        int black = pack(255, 0, 0, 0);
        int bwBlack = applyOne(BW, black);
        assertEquals(0, red(bwBlack));
        assertEquals(0, green(bwBlack));
        assertEquals(0, blue(bwBlack));
    }

    @Test
    void alphaUnchangedAcrossFilters() {
        int[] alphas = {0, 32, 128, 200, 255};
        int sample = pack(0, 50, 150, 250);

        for (int a : alphas) {
            int pixel = (sample & 0x00FFFFFF) | (a << 24);
            for (FilterSettings choice : allSingleChoices()) {
                int out = applyOne(choice, pixel);
                assertEquals(a, alpha(out), "Alpha mismatch for choice " + choice);
            }
        }
    }

    @Test
    void channelOrderRedInLowestByte() {
        int pureRed = pack(255, 0, 0, 255);
        int pureBlue = pack(255, 255, 0, 0);

        assertEquals(255, red(pureRed));
        assertEquals(0, blue(pureRed));

        assertEquals(0, red(pureBlue));
        assertEquals(255, blue(pureBlue));

        // Keeping red keeps pure red untouched and turns pure blue grey
        assertEquals(pureRed, applyOne(keep(KeepColour.RED), pureRed));
        assertTrue(isGrey(applyOne(keep(KeepColour.RED), pureBlue)));

        // Keeping blue keeps pure blue untouched and turns pure red grey
        assertEquals(pureBlue, applyOne(keep(KeepColour.BLUE), pureBlue));
        assertTrue(isGrey(applyOne(keep(KeepColour.BLUE), pureRed)));
    }

    @Test
    void eachKeepColourChoiceKeepsPureSampleAndGreysAnother() {
        // Pure samples: (a, b, g, r)
        int pureRed = pack(255, 0, 0, 255);       // hue 0°
        int pureOrange = pack(255, 0, 128, 255);   // hue 30.1°
        int pureYellow = pack(255, 0, 255, 255);   // hue 60°
        int pureGreen = pack(255, 0, 255, 0);      // hue 120°
        int pureBlue = pack(255, 255, 0, 0);       // hue 240°
        int purePurple = pack(255, 255, 0, 128);   // hue 270°
        int purePink = pack(255, 180, 105, 255);   // hue 330°

        // Red
        assertEquals(pureRed, applyOne(keep(KeepColour.RED), pureRed));
        assertTrue(isGrey(applyOne(keep(KeepColour.GREEN), pureRed)));

        // Orange
        assertEquals(pureOrange, applyOne(keep(KeepColour.ORANGE), pureOrange));
        assertTrue(isGrey(applyOne(keep(KeepColour.BLUE), pureOrange)));

        // Yellow
        assertEquals(pureYellow, applyOne(keep(KeepColour.YELLOW), pureYellow));
        assertTrue(isGrey(applyOne(keep(KeepColour.PURPLE), pureYellow)));

        // Green
        assertEquals(pureGreen, applyOne(keep(KeepColour.GREEN), pureGreen));
        assertTrue(isGrey(applyOne(keep(KeepColour.RED), pureGreen)));

        // Blue
        assertEquals(pureBlue, applyOne(keep(KeepColour.BLUE), pureBlue));
        assertTrue(isGrey(applyOne(keep(KeepColour.RED), pureBlue)));

        // Purple
        assertEquals(purePurple, applyOne(keep(KeepColour.PURPLE), purePurple));
        assertTrue(isGrey(applyOne(keep(KeepColour.GREEN), purePurple)));

        // Pink
        assertEquals(purePink, applyOne(keep(KeepColour.PINK), purePink));
        assertTrue(isGrey(applyOne(keep(KeepColour.GREEN), purePink)));
    }

    @Test
    void redOnBothSidesOfZeroDegrees() {
        // Hue ~355°: r=255, g=0, b=21 (hue = 60 * -21/255 = -4.94° = 355.06°)
        int redHigh = pack(255, 21, 0, 255);
        // Hue ~5°: r=255, g=21, b=0 (hue = 60 * 21/255 = +4.94°)
        int redLow = pack(255, 0, 21, 255);

        // Both are within RED core range and kept
        assertEquals(redHigh, applyOne(keep(KeepColour.RED), redHigh));
        assertEquals(redLow, applyOne(keep(KeepColour.RED), redLow));

        // Both are greyed out by other color filters
        assertTrue(isGrey(applyOne(keep(KeepColour.GREEN), redHigh)));
        assertTrue(isGrey(applyOne(keep(KeepColour.GREEN), redLow)));
        assertTrue(isGrey(applyOne(keep(KeepColour.BLUE), redHigh)));
        assertTrue(isGrey(applyOne(keep(KeepColour.BLUE), redLow)));
    }

    @Test
    void lowSaturationGoesGrey() {
        // Snow pixel with light sky-blue tint: r=240, g=244, b=255 (saturation = 15/255 = 0.058 < MIN_SATURATION)
        int snowWithSkyTint = pack(255, 255, 244, 240);
        int outBlue = applyOne(keep(KeepColour.BLUE), snowWithSkyTint);
        assertTrue(isGrey(outBlue), "Low saturation snow should turn grey, not keep blue speckles");

        // Stone pixel with faint green moss tint: r=120, g=125, b=120 (saturation = 5/125 = 0.04)
        int stone = pack(255, 120, 125, 120);
        int outGreen = applyOne(keep(KeepColour.GREEN), stone);
        assertTrue(isGrey(outGreen), "Low saturation stone should turn grey");
    }

    @Test
    void softEdgeFeathering() {
        // Red target center is 0, half-width 14, edge 8 (transition between 14° and 22°)
        // Hue = 18° is halfway through the edge: r=255, g=76, b=0 (hue = 60 * 76/255 = 17.88°)
        int nearEdge = pack(255, 0, 76, 255);
        int filtered = applyOne(keep(KeepColour.RED), nearEdge);

        // It should be partially desaturated (neither identical to original nor completely grey)
        assertNotEquals(nearEdge, filtered);
        assertNotEquals(red(filtered), green(filtered));
        // Red channel remains higher than green and blue
        assertTrue(red(filtered) > green(filtered));
        assertTrue(green(filtered) > blue(filtered));
    }

    @Test
    void noneFilterLeavesPixelsUntouched() {
        int sample = pack(200, 10, 20, 30);
        assertEquals(sample, applyOne(FilterSettings.NONE, sample));

        int[] pixels = {sample, pack(255, 100, 50, 25)};
        int[] original = pixels.clone();
        Filters.applyInPlace(FilterSettings.NONE, pixels, 2, 1);
        assertEquals(original[0], pixels[0]);
        assertEquals(original[1], pixels[1]);
    }

    @Test
    void severalColoursKeepEachAndGreyTheRest() {
        FilterSettings both = keep(KeepColour.ORANGE, KeepColour.BLUE);
        assertEquals(ORANGE_PIXEL, applyOne(both, ORANGE_PIXEL));
        assertEquals(BLUE_PIXEL, applyOne(both, BLUE_PIXEL));
        assertTrue(isGrey(applyOne(both, GREEN_PIXEL)));
    }

    @Test
    void redAcrossZeroDegreesWorksAlongsideAnotherColour() {
        FilterSettings redAndBlue = keep(KeepColour.BLUE, KeepColour.RED);
        int redHigh = pack(255, 21, 0, 255);
        int redLow = pack(255, 0, 21, 255);
        assertEquals(redHigh, applyOne(redAndBlue, redHigh));
        assertEquals(redLow, applyOne(redAndBlue, redLow));
        assertEquals(BLUE_PIXEL, applyOne(redAndBlue, BLUE_PIXEL));
        assertTrue(isGrey(applyOne(redAndBlue, GREEN_PIXEL)));
    }

    @Test
    void softHueEdgeStaysSoftWithSeveralColours() {
        int nearEdge = pack(255, 0, 76, 255);
        assertEquals(applyOne(keep(KeepColour.RED), nearEdge), applyOne(keep(KeepColour.RED, KeepColour.BLUE), nearEdge));
    }

    @Test
    void keepAreaStaysAndOutsideGoesGrey() {
        int w = 200;
        int h = 100;
        int[] pixels = filled(w, h, GREEN_PIXEL);
        CropRect box = new CropRect(50, 20, 40, 30);
        Filters.applyInPlace(FilterSettings.NONE.withKeepAreas(List.of(box)), pixels, w, h);

        assertEquals(GREEN_PIXEL, pixels[20 * w + 50]);
        assertEquals(GREEN_PIXEL, pixels[49 * w + 89]);
        assertTrue(isGrey(pixels[0]));
        assertTrue(isGrey(pixels[99 * w + 199]));
    }

    @Test
    void keepAreaEdgeFadesOutward() {
        int w = 960;
        int edge = Filters.edgeWidthFor(w);
        assertEquals(3, edge);
        int h = 40;
        int boxRight = 100;
        int[] pixels = filled(w, h, GREEN_PIXEL);
        Filters.applyInPlace(
                FilterSettings.NONE.withKeepAreas(List.of(new CropRect(50, 0, boxRight - 50, h))), pixels, w, h);

        int y = 20 * w;
        assertEquals(GREEN_PIXEL, pixels[y + boxRight - 1]);
        int previousGap = 255;
        for (int x = boxRight; x < boxRight + edge; x++) {
            int gap = green(pixels[y + x]) - red(pixels[y + x]);
            assertTrue(gap > 0, "Should still have some colour at " + x);
            assertTrue(gap < previousGap, "Should fade at " + x);
            previousGap = gap;
        }
        assertTrue(isGrey(pixels[y + boxRight + edge]));
    }

    @Test
    void softEdgeScalesWithThePicture() {
        assertEquals(6, Filters.edgeWidthFor(1920));
        assertEquals(4, Filters.edgeWidthFor(1280));
        assertEquals(1, Filters.edgeWidthFor(10));
    }

    @Test
    void overlappingKeepAreasDontDependOnOrder() {
        CropRect a = new CropRect(10, 10, 30, 30);
        CropRect b = new CropRect(30, 30, 30, 30);
        int[] first = filled(100, 100, GREEN_PIXEL);
        int[] second = filled(100, 100, GREEN_PIXEL);
        Filters.applyInPlace(FilterSettings.NONE.withKeepAreas(List.of(a, b)), first, 100, 100);
        Filters.applyInPlace(FilterSettings.NONE.withKeepAreas(List.of(b, a)), second, 100, 100);
        assertArrayEquals(first, second);
    }

    @Test
    void keepAreaKeepsAnyColourNextToTickedColour() {
        int[] pixels = {GREEN_PIXEL, GREEN_PIXEL, BLUE_PIXEL, GREEN_PIXEL};
        FilterSettings settings = keep(KeepColour.BLUE).withKeepAreas(List.of(new CropRect(0, 0, 1, 1)));
        Filters.applyInPlace(settings, pixels, 4, 1);
        assertEquals(GREEN_PIXEL, pixels[0]);
        assertEquals(BLUE_PIXEL, pixels[2]);
        assertTrue(isGrey(pixels[3]));
    }

    @Test
    void blackAndWhiteWithKeepAreaIsGreyOutsideOnly() {
        int w = 400;
        int[] pixels = filled(w, 10, GREEN_PIXEL);
        Filters.applyInPlace(BW.withKeepAreas(List.of(new CropRect(0, 0, 20, 10))), pixels, w, 10);
        assertEquals(GREEN_PIXEL, pixels[5]);
        assertEquals(182, red(pixels[300]));
        assertTrue(isGrey(pixels[300]));
    }

    @Test
    void blurredRestKeepsColourSharpAndBlursTheRest() {
        int w = 200;
        int h = 20;
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Orange marks the left half and green and black stripes the right, so the stripes must smooth out.
                pixels[y * w + x] = x < 100 ? ORANGE_PIXEL : (x % 2 == 0 ? GREEN_PIXEL : pack(255, 0, 0, 0));
            }
        }
        int[] filtered = pixels.clone();
        Filters.applyInPlace(keep(KeepColour.ORANGE).withRest(FilterSettings.Rest.BLUR), filtered, w, h);

        assertEquals(ORANGE_PIXEL, filtered[10]);
        int a = green(filtered[10 * w + 170]);
        int b = green(filtered[10 * w + 171]);
        assertTrue(Math.abs(a - b) < 40, "Stripes should be smoothed out, got " + a + " and " + b);
        assertTrue(!isGrey(filtered[10 * w + 170]), "Blur keeps the colour of the rest");
    }

    @Test
    void blurredRestWithBlackAndWhiteIsGreyToo() {
        int w = 400;
        int[] pixels = filled(w, 10, GREEN_PIXEL);
        Filters.applyInPlace(
                BW.withKeepAreas(List.of(new CropRect(0, 0, 20, 10))).withRest(FilterSettings.Rest.BLUR), pixels, w, 10);
        assertEquals(GREEN_PIXEL, pixels[5]);
        assertTrue(isGrey(pixels[300]));
    }

    @Test
    void restIsIgnoredWhenNothingIsKept() {
        int[] grey = {GREEN_PIXEL, BLUE_PIXEL, ORANGE_PIXEL, GREEN_PIXEL};
        int[] blurSetting = grey.clone();
        Filters.applyInPlace(BW, grey, 2, 2);
        Filters.applyInPlace(BW.withRest(FilterSettings.Rest.BLUR), blurSetting, 2, 2);
        assertArrayEquals(grey, blurSetting);

        int[] untouched = {GREEN_PIXEL, BLUE_PIXEL};
        Filters.applyInPlace(FilterSettings.NONE.withRest(FilterSettings.Rest.BLUR), untouched, 2, 1);
        assertArrayEquals(new int[] {GREEN_PIXEL, BLUE_PIXEL}, untouched);
    }

    @Test
    void wrongPixelCountIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Filters.applyInPlace(BW, new int[3], 2, 2));
    }

    @Test
    void blurAndKeepArea1080pPerformance() {
        int w = 1920;
        int h = 1080;
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = pack(255, (i * 3) & 0xFF, (i * 5) & 0xFF, (i * 7) & 0xFF);
        }
        FilterSettings settings = keep(KeepColour.ORANGE, KeepColour.BLUE)
                .withKeepAreas(List.of(new CropRect(100, 100, 800, 600), new CropRect(500, 300, 900, 500)))
                .withRest(FilterSettings.Rest.BLUR);

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            int[] copy = pixels.clone();
            long start = System.nanoTime();
            Filters.applyInPlace(settings, copy, w, h);
            best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
        }
        System.out.println("1080p blur, colours and keep areas: " + best + " ms");
        assertTrue(best < 400, "1080p blur filter took " + best + " ms");
    }

    @Test
    void performanceBenchmark1080p() {
        // 1920x1080 image = 2,073,600 pixels
        int count = 1920 * 1080;
        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) {
            pixels[i] = pack(255, (i * 3) & 0xFF, (i * 5) & 0xFF, (i * 7) & 0xFF);
        }

        long start = System.nanoTime();
        Filters.applyInPlace(keep(KeepColour.RED), pixels, 1920, 1080);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Target: under ~100 ms at 1080p on worker thread
        assertTrue(elapsedMs < 150, "1080p filter took " + elapsedMs + " ms, expected under 150 ms");
    }
}
