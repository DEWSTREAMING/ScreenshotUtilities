package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.capture.CaptureStage;
import com.screenshotutilities.image.CarriedEdits.Added;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CarriedEditsTest {
    private static PickedArea pick(int x, int y, int w, int h, List<CropRect> sourceErase) {
        BitSet mask = new BitSet();
        mask.set(0, w * h);
        return new PickedArea(x + 1, y + 1, 30, x, y, w, h, mask,
                new PickedArea.PictureSource(CaptureStage.FINAL, Set.of("chat"), sourceErase));
    }

    private static CarriedEdits sample() {
        PickedArea onHud = pick(100, 50, 20, 10, List.of(new CropRect(0, 0, 40, 40))).onHudGroup("hotbar");
        FilterSettings filter = new FilterSettings(Set.of(KeepColour.RED), false,
                List.of(new CropRect(200, 100, 100, 50)), List.of(onHud), FilterSettings.Rest.BLUR);
        return new CarriedEdits(CaptureStage.FINAL, Set.of("chat", "hotbar"), List.of(new CropRect(10, 20, 30, 40)),
                new CropRect(0, 0, 960, 540), filter, List.of(Added.PICK, Added.BOX));
    }

    @Test
    void sameSizeKeepsEverythingAsItIs() {
        CarriedEdits edits = sample();
        assertSame(edits, edits.mappedTo(1920, 1080, 1920, 1080));
    }

    @Test
    void sameShapeScalesBoxesCropAndPicks() {
        CarriedEdits edits = sample().mappedTo(1920, 1080, 960, 540);
        assertEquals(List.of(new CropRect(5, 10, 15, 20)), edits.eraseBoxes());
        assertEquals(new CropRect(0, 0, 480, 270), edits.crop());
        assertEquals(List.of(new CropRect(100, 50, 50, 25)), edits.filter().keepAreas());
        PickedArea p = edits.filter().pickedAreas().get(0);
        assertEquals(50, p.x());
        assertEquals(25, p.y());
        assertEquals(10, p.width());
        assertEquals(5, p.height());
        assertEquals(50, p.pixelCount());
        assertEquals("hotbar", p.hudGroup());
        assertEquals(List.of(new CropRect(0, 0, 20, 20)), p.source().eraseBoxes());
        assertEquals(Set.of("chat"), p.source().hiddenGroups());
        assertEquals(List.of(Added.PICK, Added.BOX), edits.addedOrder());
        assertEquals(Set.of(KeepColour.RED), edits.filter().colours());
        assertEquals(FilterSettings.Rest.BLUR, edits.filter().rest());
        assertEquals(Set.of("chat", "hotbar"), edits.hiddenGroups());
    }

    @Test
    void cropThatBecomesTheWholePictureGoes() {
        CarriedEdits edits = new CarriedEdits(CaptureStage.FINAL, Set.of(), List.of(), new CropRect(0, 0, 1919, 1080),
                FilterSettings.NONE, List.of());
        assertNull(edits.mappedTo(1920, 1080, 960, 540).crop());
    }

    @Test
    void anotherShapeDropsWhatCantBeMapped() {
        CarriedEdits edits = sample().mappedTo(1920, 1080, 1440, 1080);
        assertTrue(edits.eraseBoxes().isEmpty());
        assertNull(edits.crop());
        assertTrue(edits.filter().keepAreas().isEmpty());
        assertTrue(edits.filter().pickedAreas().isEmpty());
        assertTrue(edits.addedOrder().isEmpty());
        assertEquals(Set.of(KeepColour.RED), edits.filter().colours());
        assertEquals(FilterSettings.Rest.BLUR, edits.filter().rest());
        assertEquals(Set.of("chat", "hotbar"), edits.hiddenGroups());
        assertEquals(CaptureStage.FINAL, edits.view());
    }

    @Test
    void tinyRoundingInShapeStillCounts() {
        assertTrue(CarriedEdits.sameShape(1920, 1080, 1921, 1080));
        assertFalse(CarriedEdits.sameShape(1920, 1080, 1920, 1200));
    }

    @Test
    void orderThatDoesntAddUpIsRebuilt() {
        FilterSettings filter = FilterSettings.NONE.withKeepAreas(List.of(new CropRect(0, 0, 5, 5)))
                .withPickedAreas(List.of(pick(0, 0, 2, 2, List.of())));
        CarriedEdits edits = new CarriedEdits(CaptureStage.FINAL, Set.of(), List.of(), null, filter, List.of(Added.BOX));
        assertEquals(List.of(Added.BOX, Added.PICK), edits.addedOrder());
    }

    @Test
    void emptyPictureIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> sample().mappedTo(0, 1080, 1920, 1080));
    }
}
