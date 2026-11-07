package com.screenshotutilities.capture;

/**
 * Points in the frame where the picture is read. Later stages (per HUD layer, late mod overlays) slot in between these.
 */
public enum CaptureStage {
    /** World, hand and shaders, before any HUD layer. Missing when there is no world (title screen, loading). */
    WORLD,
    /** The finished frame, including HUD and any open screen. Always present on a good capture. */
    FINAL
}
