package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.image.FilterSettings.Rest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FilterSettingsTest {
    private static final CropRect BOX = new CropRect(10, 20, 100, 50);
    private static final CropRect OTHER_BOX = new CropRect(300, 200, 40, 40);

    @Test
    void noneIsNotActive() {
        assertFalse(FilterSettings.NONE.isActive());
        assertFalse(FilterSettings.NONE.keepsAnything());
        assertTrue(FilterSettings.NONE.isNoneChosen());
    }

    @Test
    void blackAndWhiteKeepsNothing() {
        FilterSettings bw = FilterSettings.NONE.pickBlackAndWhite();
        assertTrue(bw.isActive());
        assertFalse(bw.keepsAnything());
        assertFalse(bw.isNoneChosen());
    }

    @Test
    void tickingAColourTurnsBlackAndWhiteOffAndLeavesAreas() {
        FilterSettings settings = FilterSettings.NONE
                .withKeepAreas(List.of(BOX))
                .pickBlackAndWhite()
                .toggleColour(KeepColour.ORANGE);
        assertFalse(settings.blackAndWhite());
        assertEquals(Set.of(KeepColour.ORANGE), settings.colours());
        assertEquals(List.of(BOX), settings.keepAreas());
    }

    @Test
    void pickingBlackAndWhiteClearsColoursAndLeavesAreas() {
        FilterSettings settings = FilterSettings.NONE
                .withKeepAreas(List.of(BOX))
                .toggleColour(KeepColour.RED)
                .toggleColour(KeepColour.BLUE)
                .pickBlackAndWhite();
        assertTrue(settings.blackAndWhite());
        assertTrue(settings.colours().isEmpty());
        assertEquals(List.of(BOX), settings.keepAreas());
    }

    @Test
    void noneClearsColoursAndBlackAndWhiteButNotAreas() {
        FilterSettings fromColours = FilterSettings.NONE
                .withKeepAreas(List.of(BOX))
                .toggleColour(KeepColour.RED)
                .pickNone();
        assertTrue(fromColours.colours().isEmpty());
        assertEquals(List.of(BOX), fromColours.keepAreas());
        assertTrue(fromColours.isActive());
        assertTrue(fromColours.isNoneChosen());

        FilterSettings fromBw = FilterSettings.NONE.pickBlackAndWhite().pickNone();
        assertFalse(fromBw.blackAndWhite());
        assertFalse(fromBw.isActive());
    }

    @Test
    void tickingAColourTwiceTakesItOff() {
        FilterSettings settings = FilterSettings.NONE.toggleColour(KeepColour.PINK).toggleColour(KeepColour.PINK);
        assertTrue(settings.colours().isEmpty());
        assertFalse(settings.isActive());
    }

    @Test
    void keepAreaAloneIsActiveAndKeepsSomething() {
        FilterSettings settings = FilterSettings.NONE.withKeepAreas(List.of(BOX));
        assertTrue(settings.isActive());
        assertTrue(settings.keepsAnything());
        assertTrue(settings.isNoneChosen());
    }

    @Test
    void coloursListInEnumOrderWhateverOrderTheyWereTicked() {
        FilterSettings settings = FilterSettings.NONE.toggleColour(KeepColour.BLUE).toggleColour(KeepColour.RED);
        assertEquals(List.of(KeepColour.RED, KeepColour.BLUE), List.copyOf(settings.colours()));
    }

    @Test
    void valueIsImmutable() {
        List<CropRect> areas = new ArrayList<>(List.of(BOX));
        FilterSettings settings = FilterSettings.NONE.withKeepAreas(areas);
        areas.add(OTHER_BOX);
        assertEquals(List.of(BOX), settings.keepAreas());
        assertThrows(UnsupportedOperationException.class, () -> settings.keepAreas().add(OTHER_BOX));
        assertThrows(UnsupportedOperationException.class, () -> settings.colours().add(KeepColour.RED));
    }

    @Test
    void blackAndWhiteWithColoursIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FilterSettings(Set.of(KeepColour.RED), true, List.of(), Rest.GREY));
    }

    @Test
    void restIsNormalisedAwayWhenNothingIsKept() {
        assertEquals(FilterSettings.NONE, FilterSettings.NONE.withRest(Rest.BLUR).normalised());
        assertEquals(
                FilterSettings.NONE.pickBlackAndWhite(),
                FilterSettings.NONE.pickBlackAndWhite().withRest(Rest.BLUR).normalised());
    }

    @Test
    void restCountsWhenSomethingIsKept() {
        FilterSettings colour = FilterSettings.NONE.toggleColour(KeepColour.RED);
        assertNotEquals(colour.normalised(), colour.withRest(Rest.BLUR).normalised());
        FilterSettings area = FilterSettings.NONE.withKeepAreas(List.of(BOX));
        assertNotEquals(area.normalised(), area.withRest(Rest.BLUR).normalised());
    }

    @Test
    void boxOrderAndRepeatsDontChangeTheNormalisedValue() {
        FilterSettings ab = FilterSettings.NONE.withKeepAreas(List.of(BOX, OTHER_BOX));
        FilterSettings ba = FilterSettings.NONE.withKeepAreas(List.of(OTHER_BOX, BOX, OTHER_BOX));
        assertEquals(ab.normalised(), ba.normalised());
    }

    @Test
    void scaledToMapsAreasIntoAnotherSize() {
        FilterSettings full = FilterSettings.NONE
                .toggleColour(KeepColour.GREEN)
                .withKeepAreas(List.of(new CropRect(100, 200, 400, 300)))
                .withRest(Rest.BLUR);
        FilterSettings half = full.scaledTo(1920, 1080, 960, 540);
        assertEquals(List.of(new CropRect(50, 100, 200, 150)), half.keepAreas());
        assertEquals(full.colours(), half.colours());
        assertEquals(Rest.BLUR, half.rest());
    }

    @Test
    void restSwitchesBetweenGreyAndBlur() {
        assertEquals(Rest.BLUR, Rest.GREY.other());
        assertEquals(Rest.GREY, Rest.BLUR.other());
    }
}
