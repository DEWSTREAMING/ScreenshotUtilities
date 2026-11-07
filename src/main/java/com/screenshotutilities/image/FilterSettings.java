package com.screenshotutilities.image;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the filter does to a picture. Immutable, so it can be handed to a worker thread as it is.
 *
 * <p>Keep areas are boxes in full-size image pixels, picked areas are wand selections in the same pixels. Colours and Black and white rule each other out, keep areas work with
 * either. {@code rest} only matters while something is kept.</p>
 */
public record FilterSettings(
        Set<KeepColour> colours, boolean blackAndWhite, List<CropRect> keepAreas, List<PickedArea> pickedAreas, Rest rest) {
    public enum Rest {
        GREY("grey"),
        BLUR("blur");

        private final String id;

        Rest(String id) {
            this.id = id;
        }

        public String translationKey() {
            return "screenshotutilities.filter.rest." + id;
        }

        public Rest other() {
            return this == GREY ? BLUR : GREY;
        }
    }

    public static final FilterSettings NONE = new FilterSettings(Set.of(), false, List.of(), List.of(), Rest.GREY);

    private static final Comparator<CropRect> BOX_ORDER = Comparator.comparingInt(CropRect::y)
            .thenComparingInt(CropRect::x)
            .thenComparingInt(CropRect::height)
            .thenComparingInt(CropRect::width);

    private static final Comparator<PickedArea> PICK_ORDER = Comparator.comparingInt(PickedArea::seedY)
            .thenComparingInt(PickedArea::seedX)
            .thenComparingInt(PickedArea::tolerance)
            .thenComparingInt(PickedArea::mobId);

    public FilterSettings(Set<KeepColour> colours, boolean blackAndWhite, List<CropRect> keepAreas, Rest rest) {
        this(colours, blackAndWhite, keepAreas, List.of(), rest);
    }

    public FilterSettings {
        // An unmodifiable copy of an EnumSet keeps the colours in enum order, which the button label relies on.
        Set<KeepColour> ordered = EnumSet.noneOf(KeepColour.class);
        if (colours != null) {
            ordered.addAll(colours);
        }
        colours = Collections.unmodifiableSet(ordered);
        keepAreas = keepAreas != null ? List.copyOf(keepAreas) : List.of();
        pickedAreas = pickedAreas != null ? List.copyOf(pickedAreas) : List.of();
        rest = rest != null ? rest : Rest.GREY;
        if (blackAndWhite && !colours.isEmpty()) {
            throw new IllegalArgumentException("Black and white can't be used with kept colours");
        }
    }

    /** Ticks or unticks one colour. Black and white goes off, keep areas stay. */
    public FilterSettings toggleColour(KeepColour colour) {
        Set<KeepColour> next = EnumSet.noneOf(KeepColour.class);
        next.addAll(colours);
        if (!next.remove(colour)) {
            next.add(colour);
        }
        return new FilterSettings(next, false, keepAreas, pickedAreas, rest);
    }

    /** Picks Black and white, which clears the colours. Keep areas stay. */
    public FilterSettings pickBlackAndWhite() {
        return new FilterSettings(Set.of(), true, keepAreas, pickedAreas, rest);
    }

    /** Clears colours and Black and white. Keep areas have their own Clear, so they stay. */
    public FilterSettings pickNone() {
        return new FilterSettings(Set.of(), false, keepAreas, pickedAreas, rest);
    }

    public FilterSettings withKeepAreas(List<CropRect> areas) {
        return new FilterSettings(colours, blackAndWhite, areas, pickedAreas, rest);
    }

    public FilterSettings withPickedAreas(List<PickedArea> areas) {
        return new FilterSettings(colours, blackAndWhite, keepAreas, areas, rest);
    }

    /** The settings as they show with these HUD groups hidden: picks made on a hidden group mark nothing. */
    public FilterSettings shownWith(Set<String> hiddenGroups) {
        if (hiddenGroups.isEmpty() || pickedAreas.stream().noneMatch(p -> hiddenOn(p, hiddenGroups))) {
            return this;
        }
        List<PickedArea> shown = pickedAreas.stream().filter(p -> !hiddenOn(p, hiddenGroups)).toList();
        return new FilterSettings(colours, blackAndWhite, keepAreas, shown, rest);
    }

    private static boolean hiddenOn(PickedArea pick, Set<String> hiddenGroups) {
        // Set.copyOf sets throw on contains(null).
        return pick.hudGroup() != null && hiddenGroups.contains(pick.hudGroup());
    }

    public FilterSettings withRest(Rest newRest) {
        return new FilterSettings(colours, blackAndWhite, keepAreas, pickedAreas, newRest);
    }

    public boolean keepsAnything() {
        return !colours.isEmpty() || !keepAreas.isEmpty() || !pickedAreas.isEmpty();
    }

    /** False when the picture comes out unchanged. */
    public boolean isActive() {
        return blackAndWhite || keepsAnything();
    }

    /** No colours and no Black and white, whatever the keep areas are. */
    public boolean isNoneChosen() {
        return !blackAndWhite && colours.isEmpty();
    }

    /** The same settings with keep areas mapped into a picture of another size, such as the preview. */
    public FilterSettings scaledTo(int imageWidth, int imageHeight, int targetWidth, int targetHeight) {
        if ((keepAreas.isEmpty() && pickedAreas.isEmpty()) || (imageWidth == targetWidth && imageHeight == targetHeight)) {
            return this;
        }
        List<CropRect> scaled = new ArrayList<>(keepAreas.size());
        for (CropRect box : keepAreas) {
            scaled.add(box.scaleTo(imageWidth, imageHeight, targetWidth, targetHeight));
        }
        List<PickedArea> scaledPicks = new ArrayList<>(pickedAreas.size());
        for (PickedArea area : pickedAreas) {
            scaledPicks.add(area.scaledTo(imageWidth, imageHeight, targetWidth, targetHeight));
        }
        return new FilterSettings(colours, blackAndWhite, scaled, scaledPicks, rest);
    }

    /** Settings that give the same picture normalise to equal values, so the same picture can't be saved twice. */
    public FilterSettings normalised() {
        List<CropRect> areas = keepAreas.stream().distinct().sorted(BOX_ORDER).toList();
        List<PickedArea> picks = pickedAreas.stream().distinct().sorted(PICK_ORDER).toList();
        return new FilterSettings(colours, blackAndWhite, areas, picks, keepsAnything() ? rest : Rest.GREY);
    }
}
