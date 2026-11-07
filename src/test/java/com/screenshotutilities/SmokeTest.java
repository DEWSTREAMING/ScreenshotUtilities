package com.screenshotutilities;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void modIdMatchesGradleProperties() {
        assertEquals("screenshotutilities", ScreenshotUtilities.MOD_ID);
    }
}
