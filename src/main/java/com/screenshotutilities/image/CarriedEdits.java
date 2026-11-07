package com.screenshotutilities.image;

import com.screenshotutilities.capture.CaptureStage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The popup's edits, carried from one shot to a shader retake of it. Everything is in full-size picture pixels. The retake
 * has the same camera, so boxes and picks keep their exact shapes; only a different picture size needs mapping.
 *
 * @param addedOrder what Highlight mode added, oldest first, so Undo takes the right thing on the retake too
 */
public record CarriedEdits(
        CaptureStage view,
        Set<String> hiddenGroups,
        List<CropRect> eraseBoxes,
        @Nullable CropRect crop,
        FilterSettings filter,
        List<Added> addedOrder) {
    /** One thing Highlight mode added: a box or a wand pick. */
    public enum Added {
        BOX,
        PICK
    }

    /** Pictures whose shapes differ more than this can't be mapped: a wider window shows more of the world at the sides. */
    static final double MAX_ASPECT_DIFFERENCE = 0.01;

    public CarriedEdits {
        hiddenGroups = Collections.unmodifiableSet(new LinkedHashSet<>(hiddenGroups));
        eraseBoxes = List.copyOf(eraseBoxes);
        filter = filter != null ? filter : FilterSettings.NONE;
        addedOrder = List.copyOf(matchingOrder(addedOrder, filter));
    }

    /** An order that doesn't add up to the boxes and picks there are is rebuilt, so Undo can never run past the end. */
    private static List<Added> matchingOrder(List<Added> order, FilterSettings filter) {
        long boxes = order.stream().filter(a -> a == Added.BOX).count();
        long picks = order.size() - boxes;
        if (boxes == filter.keepAreas().size() && picks == filter.pickedAreas().size()) {
            return order;
        }
        List<Added> rebuilt = new ArrayList<>();
        filter.keepAreas().forEach(b -> rebuilt.add(Added.BOX));
        filter.pickedAreas().forEach(p -> rebuilt.add(Added.PICK));
        return rebuilt;
    }

    /**
     * The edits for a picture of {@code toWidth x toHeight} that were made on {@code fromWidth x fromHeight}. The same size
     * gives them back as they are. The same shape at another size scales boxes, crop and picks. Another shape drops them
     * and keeps the rest (view, hidden HUD groups, colours, Black and white and Rest).
     */
    public CarriedEdits mappedTo(int fromWidth, int fromHeight, int toWidth, int toHeight) {
        if (fromWidth <= 0 || fromHeight <= 0 || toWidth <= 0 || toHeight <= 0) {
            throw new IllegalArgumentException("Empty picture");
        }
        if (fromWidth == toWidth && fromHeight == toHeight) {
            return this;
        }
        if (!sameShape(fromWidth, fromHeight, toWidth, toHeight)) {
            FilterSettings plain = filter.withKeepAreas(List.of()).withPickedAreas(List.of());
            return new CarriedEdits(view, hiddenGroups, List.of(), null, plain, List.of());
        }
        List<CropRect> erase = scaleBoxes(eraseBoxes, fromWidth, fromHeight, toWidth, toHeight);
        CropRect newCrop = null;
        if (crop != null) {
            CropRect scaled = crop.scaleTo(fromWidth, fromHeight, toWidth, toHeight);
            newCrop = scaled.isFullImage(toWidth, toHeight) ? null : scaled;
        }
        List<CropRect> keep = scaleBoxes(filter.keepAreas(), fromWidth, fromHeight, toWidth, toHeight);
        List<PickedArea> picks = new ArrayList<>(filter.pickedAreas().size());
        for (PickedArea pick : filter.pickedAreas()) {
            PickedArea.PictureSource source = pick.source();
            PickedArea.PictureSource newSource = new PickedArea.PictureSource(source.stage(), source.hiddenGroups(),
                    scaleBoxes(source.eraseBoxes(), fromWidth, fromHeight, toWidth, toHeight));
            picks.add(pick.scaledTo(fromWidth, fromHeight, toWidth, toHeight).withSource(newSource));
        }
        FilterSettings newFilter = filter.withKeepAreas(keep).withPickedAreas(picks);
        return new CarriedEdits(view, hiddenGroups, erase, newCrop, newFilter, addedOrder);
    }

    static boolean sameShape(int fromWidth, int fromHeight, int toWidth, int toHeight) {
        double from = (double) fromWidth / fromHeight;
        double to = (double) toWidth / toHeight;
        return Math.abs(from - to) <= from * MAX_ASPECT_DIFFERENCE;
    }

    private static List<CropRect> scaleBoxes(List<CropRect> boxes, int fromWidth, int fromHeight, int toWidth, int toHeight) {
        List<CropRect> out = new ArrayList<>(boxes.size());
        for (CropRect box : boxes) {
            out.add(box.scaleTo(fromWidth, fromHeight, toWidth, toHeight));
        }
        return out;
    }
}
