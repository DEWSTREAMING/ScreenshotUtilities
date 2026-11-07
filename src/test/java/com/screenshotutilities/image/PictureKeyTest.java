package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.capture.CaptureStage;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PictureKeyTest {
    @Test
    void sameViewAndCropMatch() {
        CropRect a = new CropRect(20, 30, 400, 300);
        CropRect b = new CropRect(20, 30, 400, 300);
        assertEquals(PictureKey.of(CaptureStage.FINAL, a, 1920, 1080), PictureKey.of(CaptureStage.FINAL, b, 1920, 1080));
    }

    @Test
    void differentStageOrDifferentCropProduceDifferentKeys() {
        CropRect cropA = new CropRect(20, 30, 400, 300);
        CropRect cropB = new CropRect(21, 30, 400, 300);
        PictureKey finalA = PictureKey.of(CaptureStage.FINAL, cropA, 1920, 1080);
        PictureKey worldA = PictureKey.of(CaptureStage.WORLD, cropA, 1920, 1080);
        PictureKey finalB = PictureKey.of(CaptureStage.FINAL, cropB, 1920, 1080);
        PictureKey finalUncropped = PictureKey.of(CaptureStage.FINAL, null, 1920, 1080);

        assertNotEquals(finalA, worldA);
        assertNotEquals(finalA, finalB);
        assertNotEquals(finalA, finalUncropped);
    }

    @Test
    void fullImageCropNormalizesToUncroppedKey() {
        PictureKey uncropped = PictureKey.of(CaptureStage.WORLD, null, 1920, 1080);
        PictureKey fullCrop = PictureKey.of(CaptureStage.WORLD, new CropRect(0, 0, 1920, 1080), 1920, 1080);
        assertNull(fullCrop.crop());
        assertEquals(uncropped, fullCrop);
    }

    @Test
    void hiddenGroupsAndEraseBoxesDistinguishKeys() {
        Set<String> available = Set.of("crosshair", "hotbar", "chat");
        PictureKey base = PictureKey.of(CaptureStage.FINAL, Set.of(), available, List.of(), null, 1920, 1080);
        PictureKey hideCrosshair = PictureKey.of(
                CaptureStage.FINAL, Set.of("crosshair"), available, List.of(), null, 1920, 1080);
        PictureKey withErase = PictureKey.of(
                CaptureStage.FINAL, Set.of(), available, List.of(new CropRect(10, 10, 50, 50)), null, 1920, 1080);

        assertNotEquals(base, hideCrosshair);
        assertNotEquals(base, withErase);
        assertNotEquals(hideCrosshair, withErase);
    }

    @Test
    void filterDistinguishesKeysAndDefaultsToNone() {
        PictureKey noneKey = PictureKey.of(
                CaptureStage.FINAL, Set.of(), Set.of(), List.of(), null, FilterSettings.NONE, 1920, 1080);
        PictureKey nullFilterKey = PictureKey.of(
                CaptureStage.FINAL, Set.of(), Set.of(), List.of(), null, null, 1920, 1080);
        PictureKey bwKey = PictureKey.of(
                CaptureStage.FINAL, Set.of(), Set.of(), List.of(), null, FilterSettings.NONE.pickBlackAndWhite(), 1920, 1080);
        PictureKey redKey = PictureKey.of(
                CaptureStage.FINAL, Set.of(), Set.of(), List.of(), null, FilterSettings.NONE.toggleColour(KeepColour.RED), 1920, 1080);

        assertEquals(FilterSettings.NONE, noneKey.filter());
        assertEquals(noneKey, nullFilterKey);
        assertNotEquals(noneKey, bwKey);
        assertNotEquals(bwKey, redKey);
    }

    @Test
    void allAvailableGroupsHiddenNormalizesToWorldKey() {
        Set<String> available = Set.of("crosshair", "hotbar");
        PictureKey allHidden = PictureKey.of(
                CaptureStage.FINAL, Set.of("crosshair", "hotbar"), available, List.of(), null, FilterSettings.NONE.toggleColour(KeepColour.RED), 1920, 1080);
        PictureKey worldKey = PictureKey.of(
                CaptureStage.WORLD, Set.of(), available, List.of(), null, FilterSettings.NONE.toggleColour(KeepColour.RED), 1920, 1080);

        assertEquals(CaptureStage.WORLD, allHidden.stage());
        assertEquals(Set.of(), allHidden.hiddenGroups());
        assertEquals(FilterSettings.NONE.toggleColour(KeepColour.RED), allHidden.filter());
        assertEquals(worldKey, allHidden);
    }

    @Test
    void trackerSeparatesSavedFromGalleryAndBlocksDuplicateAdds() {
        PictureKey.Tracker tracker = new PictureKey.Tracker();
        tracker.setDimensions(1920, 1080);
        tracker.setAvailableGroups(Set.of("crosshair", "hotbar"));

        // Saving the original picture does not mark it as added to the gallery.
        tracker.markSaved(CaptureStage.FINAL, Set.of(), List.of(), null);
        assertTrue(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), null));
        assertFalse(tracker.isInGallery(CaptureStage.FINAL, Set.of(), List.of(), null));

        // Adding the picture blocks adding the exact same picture again, including a full-image crop.
        tracker.markInGallery(CaptureStage.FINAL, Set.of(), List.of(), null);
        assertTrue(tracker.isInGallery(CaptureStage.FINAL, Set.of(), List.of(), null));
        assertTrue(tracker.isInGallery(CaptureStage.FINAL, Set.of(), List.of(), new CropRect(0, 0, 1920, 1080)));

        // Changing crop, hidden HUD groups, erase boxes, stage, or filter allows adding the changed picture.
        CropRect crop = new CropRect(50, 50, 800, 600);
        assertFalse(tracker.isInGallery(CaptureStage.FINAL, Set.of(), List.of(), crop));
        assertFalse(tracker.isInGallery(CaptureStage.FINAL, Set.of("crosshair"), List.of(), null));
        assertFalse(tracker.isInGallery(CaptureStage.FINAL, Set.of(), List.of(new CropRect(10, 10, 40, 40)), null));
        assertFalse(tracker.isInGallery(CaptureStage.WORLD, Set.of(), List.of(), null));
        assertFalse(tracker.isInGallery(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE.pickBlackAndWhite()));

        // Adding a cropped version does not mark it as saved as a copy.
        tracker.markInGallery(CaptureStage.FINAL, Set.of("crosshair"), List.of(), crop);
        assertTrue(tracker.isInGallery(CaptureStage.FINAL, Set.of("crosshair"), List.of(), crop));
        assertFalse(tracker.isSaved(CaptureStage.FINAL, Set.of("crosshair"), List.of(), crop));

        // Unmarking after a failed write allows retrying.
        tracker.unmarkInGallery(CaptureStage.FINAL, Set.of("crosshair"), List.of(), crop);
        assertFalse(tracker.isInGallery(CaptureStage.FINAL, Set.of("crosshair"), List.of(), crop));
    }

    @Test
    void trackerAllowsSavingAgainWhenFilterChanges() {
        PictureKey.Tracker tracker = new PictureKey.Tracker();
        tracker.setDimensions(1920, 1080);

        tracker.markSaved(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE);
        assertTrue(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE));
        assertFalse(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE.pickBlackAndWhite()));
        assertFalse(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE.toggleColour(KeepColour.RED)));

        tracker.markSaved(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE.pickBlackAndWhite());
        assertTrue(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE.pickBlackAndWhite()));
        assertFalse(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), null, FilterSettings.NONE.toggleColour(KeepColour.RED)));
    }

    @Test
    void severalColoursAndRestDistinguishKeys() {
        FilterSettings orange = FilterSettings.NONE.toggleColour(KeepColour.ORANGE);
        FilterSettings orangeBlue = orange.toggleColour(KeepColour.BLUE);
        FilterSettings blueOrange = FilterSettings.NONE.toggleColour(KeepColour.BLUE).toggleColour(KeepColour.ORANGE);

        assertNotEquals(keyWith(orange), keyWith(orangeBlue));
        assertEquals(keyWith(orangeBlue), keyWith(blueOrange));
        assertNotEquals(keyWith(orange), keyWith(orange.withRest(FilterSettings.Rest.BLUR)));
    }

    @Test
    void restDoesNotChangeAKeyWhenNothingIsKept() {
        assertEquals(keyWith(FilterSettings.NONE), keyWith(FilterSettings.NONE.withRest(FilterSettings.Rest.BLUR)));
        FilterSettings bw = FilterSettings.NONE.pickBlackAndWhite();
        assertEquals(keyWith(bw), keyWith(bw.withRest(FilterSettings.Rest.BLUR)));
    }

    @Test
    void keepAreasDistinguishKeysInAnyOrder() {
        CropRect a = new CropRect(10, 10, 50, 50);
        CropRect b = new CropRect(200, 100, 80, 60);
        FilterSettings withA = FilterSettings.NONE.withKeepAreas(List.of(a));

        assertNotEquals(keyWith(FilterSettings.NONE), keyWith(withA));
        assertNotEquals(keyWith(withA), keyWith(withA.withKeepAreas(List.of(a, b))));
        assertEquals(
                keyWith(FilterSettings.NONE.withKeepAreas(List.of(a, b))),
                keyWith(FilterSettings.NONE.withKeepAreas(List.of(b, a))));
    }

    @Test
    void savedPictureWithKeepAreaBlocksOnlyThatPicture() {
        PictureKey.Tracker tracker = new PictureKey.Tracker();
        tracker.setDimensions(1920, 1080);
        CropRect crop = new CropRect(100, 100, 400, 300);
        FilterSettings blurred = FilterSettings.NONE
                .withKeepAreas(List.of(new CropRect(10, 10, 50, 50)))
                .withRest(FilterSettings.Rest.BLUR);

        tracker.markSaved(CaptureStage.FINAL, Set.of(), List.of(), crop, blurred);
        assertTrue(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), crop, blurred));
        assertFalse(tracker.isSaved(CaptureStage.FINAL, Set.of(), List.of(), crop, FilterSettings.NONE));
        assertFalse(tracker.isSaved(
                CaptureStage.FINAL, Set.of(), List.of(), crop, blurred.withRest(FilterSettings.Rest.GREY)));
        assertFalse(tracker.isInGallery(CaptureStage.FINAL, Set.of(), List.of(), crop, blurred));
    }

    private static PictureKey keyWith(FilterSettings filter) {
        return PictureKey.of(CaptureStage.FINAL, Set.of(), Set.of(), List.of(), null, filter, 1920, 1080);
    }

    @Test
    void trackerRenormalizesGalleryKeysWhenAvailableGroupsArrive() {
        PictureKey.Tracker tracker = new PictureKey.Tracker();
        tracker.setDimensions(1920, 1080);

        // Marked before layer extraction finished with an off-screen group ("boss_bar") in Always hide.
        tracker.markInGallery(CaptureStage.FINAL, Set.of("crosshair", "boss_bar"), List.of(), null, FilterSettings.NONE.toggleColour(KeepColour.BLUE));
        tracker.setAvailableGroups(Set.of("crosshair", "hotbar"));

        assertTrue(tracker.isInGallery(CaptureStage.FINAL, Set.of("crosshair"), List.of(), null, FilterSettings.NONE.toggleColour(KeepColour.BLUE)));
    }
}
