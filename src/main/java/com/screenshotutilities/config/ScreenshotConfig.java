package com.screenshotutilities.config;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ScreenshotConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_POPUP;
    public static final ModConfigSpec.BooleanValue ALWAYS_COPY;
    public static final ModConfigSpec.BooleanValue ALWAYS_ADD_TO_GALLERY;
    public static final ModConfigSpec.BooleanValue HIDE_HUD;
    public static final ModConfigSpec.BooleanValue HIDE_HAND;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALWAYS_HIDE;
    public static final ModConfigSpec.BooleanValue SLIDESHOW_MAIN_MENU;
    public static final ModConfigSpec.BooleanValue SLIDESHOW_LOADING;
    public static final ModConfigSpec.IntValue SLIDESHOW_SECONDS;
    public static final ModConfigSpec.BooleanValue GALLERY_BUTTON;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SHOW_POPUP = builder
                .comment("Open the screenshot popup after F2 in single player, or show a corner prompt on servers.")
                .translation("screenshotutilities.configuration.showPopup")
                .define("showPopup", true);
        ALWAYS_COPY = builder
                .comment("Copy every F2 screenshot to the clipboard.")
                .translation("screenshotutilities.configuration.alwaysCopy")
                .define("alwaysCopy", false);
        ALWAYS_ADD_TO_GALLERY = builder
                .comment("Add every F2 screenshot to the gallery.")
                .translation("screenshotutilities.configuration.alwaysAddToGallery")
                .define("alwaysAddToGallery", false);
        HIDE_HUD = builder
                .comment("Save F2 shots without the HUD.")
                .translation("screenshotutilities.configuration.hideHud")
                .define("hideHud", false);
        HIDE_HAND = builder
                .comment("Leave your hand out of F2 shots.")
                .translation("screenshotutilities.configuration.hideHand")
                .define("hideHand", false);
        ALWAYS_HIDE = builder
                .comment("HUD element group IDs to always hide in F2 screenshots.")
                .translation("screenshotutilities.configuration.alwaysHide")
                .defineListAllowEmpty("alwaysHide", List.of(), () -> "", o -> o instanceof String s && !s.isBlank());
        SLIDESHOW_MAIN_MENU = builder
                .comment("Show gallery pictures behind the main menu and its menus.")
                .translation("screenshotutilities.configuration.slideshowMainMenu")
                .define("slideshowMainMenu", true);
        SLIDESHOW_LOADING = builder
                .comment("Show gallery pictures on loading screens.")
                .translation("screenshotutilities.configuration.slideshowLoading")
                .define("slideshowLoading", true);
        SLIDESHOW_SECONDS = builder
                .comment("Seconds each slideshow picture stays up.")
                .translation("screenshotutilities.configuration.slideshowSeconds")
                .defineInRange("slideshowSeconds", 8, 3, 60);
        GALLERY_BUTTON = builder
                .comment("Add a Gallery button to the main menu.")
                .translation("screenshotutilities.configuration.galleryButton")
                .define("galleryButton", true);
        SPEC = builder.build();
    }

    private ScreenshotConfig() {
    }

    /** Returns the configured {@code alwaysHide} group IDs as an unmodifiable set. */
    public static Set<String> alwaysHideSet() {
        List<? extends String> raw = ALWAYS_HIDE.get();
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String item : raw) {
            if (item != null && !item.isBlank()) {
                set.add(item.trim());
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /** Saves {@code groupIds} to the {@code alwaysHide} config setting. */
    public static void setAlwaysHide(Collection<String> groupIds) {
        List<String> cleaned = groupIds.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        ALWAYS_HIDE.set(cleaned);
        SPEC.save();
    }
}
