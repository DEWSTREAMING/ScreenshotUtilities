package com.screenshotutilities.capture;

import java.util.Set;

/**
 * Decisions about a capture that don't need the game, kept here so they can be unit tested.
 *
 * @param stages     stages to read in the next frame
 * @param readLayers whether to read between HUD layer groups
 * @param hideHand   whether to stop the hand being drawn for that frame
 */
public record CapturePlan(Set<CaptureStage> stages, boolean readLayers, boolean hideHand) {
    public enum AfterSave {
        /** Open the popup straight away. */
        POPUP,
        /** Show the corner prompt naming the "open last screenshot" key. */
        PROMPT,
        NOTHING
    }

    public CapturePlan {
        stages = Set.copyOf(stages);
    }

    public CapturePlan(Set<CaptureStage> stages, boolean hideHand) {
        this(stages, false, hideHand);
    }

    public static CapturePlan of(boolean showPopup, boolean hideHud, boolean hideHand) {
        return of(showPopup, hideHud, hideHand, false);
    }

    /**
     * The World stage is only read when something can use it: Hide HUD, the popup, or Always hide. Layer reads only run when
     * the popup is on or Always hide is non-empty.
     */
    public static CapturePlan of(boolean showPopup, boolean hideHud, boolean hideHand, boolean hasAlwaysHide) {
        boolean readLayers = showPopup || hasAlwaysHide;
        Set<CaptureStage> stages = showPopup || hideHud || hasAlwaysHide
                ? Set.of(CaptureStage.WORLD, CaptureStage.FINAL)
                : Set.of(CaptureStage.FINAL);
        return new CapturePlan(stages, readLayers, hideHand);
    }

    public boolean wants(CaptureStage stage) {
        return stages.contains(stage);
    }

    /**
     * Which stage goes into the saved file. With a screen open the World stage would drop that screen, so the shot keeps
     * what the player saw.
     */
    public static CaptureStage stageToSave(boolean hideHud, boolean hasWorldStage, boolean screenWasOpen) {
        return hideHud && hasWorldStage && !screenWasOpen ? CaptureStage.WORLD : CaptureStage.FINAL;
    }

    /**
     * The popup pauses the game, so it only opens where pausing works (unpublished single player) or where there's no world
     * at all. Everywhere else a corner prompt is shown so F2 never interrupts a fight.
     */
    public static AfterSave afterSave(boolean showPopup, boolean inWorld, boolean privateSinglePlayer) {
        if (!showPopup) {
            return AfterSave.NOTHING;
        }
        return !inWorld || privateSinglePlayer ? AfterSave.POPUP : AfterSave.PROMPT;
    }
}
