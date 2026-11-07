package com.screenshotutilities.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.capture.CapturePlan.AfterSave;
import org.junit.jupiter.api.Test;

class CapturePlanTest {
    @Test
    void plainShotReadsFinalOnly() {
        CapturePlan plan = CapturePlan.of(false, false, false, false);
        assertFalse(plan.wants(CaptureStage.WORLD));
        assertFalse(plan.readLayers());
        assertTrue(plan.wants(CaptureStage.FINAL));
    }

    @Test
    void popupOrHideHudNeedsWorld() {
        assertTrue(CapturePlan.of(true, false, false).wants(CaptureStage.WORLD));
        assertTrue(CapturePlan.of(false, true, false).wants(CaptureStage.WORLD));
        assertTrue(CapturePlan.of(true, true, false).wants(CaptureStage.FINAL));
    }

    @Test
    void layersAreOnlyReadWhenPopupIsOnOrAlwaysHideIsNonEmpty() {
        CapturePlan plain = CapturePlan.of(false, false, false, false);
        assertFalse(plain.readLayers());
        assertFalse(plain.wants(CaptureStage.WORLD));

        CapturePlan hideHudOnly = CapturePlan.of(false, true, false, false);
        assertFalse(hideHudOnly.readLayers());
        assertTrue(hideHudOnly.wants(CaptureStage.WORLD));

        CapturePlan popupOn = CapturePlan.of(true, false, false, false);
        assertTrue(popupOn.readLayers());
        assertTrue(popupOn.wants(CaptureStage.WORLD));

        CapturePlan alwaysHide = CapturePlan.of(false, false, false, true);
        assertTrue(alwaysHide.readLayers());
        assertTrue(alwaysHide.wants(CaptureStage.WORLD));
    }

    @Test
    void hideHandIsCarried() {
        assertTrue(CapturePlan.of(false, false, true).hideHand());
        assertFalse(CapturePlan.of(true, true, false).hideHand());
    }

    @Test
    void hideHudSavesWorldStage() {
        assertEquals(CaptureStage.WORLD, CapturePlan.stageToSave(true, true, false));
        assertEquals(CaptureStage.FINAL, CapturePlan.stageToSave(false, true, false));
    }

    @Test
    void hideHudWithoutWorldStageSavesFinal() {
        assertEquals(CaptureStage.FINAL, CapturePlan.stageToSave(true, false, false));
    }

    @Test
    void hideHudWithScreenOpenSavesFinal() {
        assertEquals(CaptureStage.FINAL, CapturePlan.stageToSave(true, true, true));
    }

    @Test
    void popupOnlyWhereThePauseWorks() {
        assertEquals(AfterSave.POPUP, CapturePlan.afterSave(true, true, true));
        assertEquals(AfterSave.POPUP, CapturePlan.afterSave(true, false, false));
        assertEquals(AfterSave.PROMPT, CapturePlan.afterSave(true, true, false));
    }

    @Test
    void popupOffShowsNothing() {
        assertEquals(AfterSave.NOTHING, CapturePlan.afterSave(false, true, true));
        assertEquals(AfterSave.NOTHING, CapturePlan.afterSave(false, true, false));
        assertEquals(AfterSave.NOTHING, CapturePlan.afterSave(false, false, false));
    }
}
