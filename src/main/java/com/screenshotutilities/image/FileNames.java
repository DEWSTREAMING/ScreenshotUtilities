package com.screenshotutilities.image;

import java.util.Locale;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * File naming for screenshots and the copies saved from the popup.
 */
public final class FileNames {
    public static final String EDIT_SUFFIX = "_edit";
    /** Used in a retake's name when it was taken with shaders off. */
    public static final String NO_SHADERS = "no_shaders";
    /** Long pack names are cut, so a retake's name stays well under any path limit. */
    static final int MAX_PACK_PART = 40;

    private FileNames() {
    }

    /**
     * Name for an edited copy of {@code originalName}, for example {@code 2026-10-31_13.17.20_edit.png}, then
     * {@code ..._edit_2.png} and so on if that is taken.
     */
    public static String editCopy(String originalName, Predicate<String> taken) {
        return firstFree(stem(originalName) + EDIT_SUFFIX, extension(originalName), taken);
    }

    /**
     * Name for a retake of {@code originalName} with a shader pack, for example
     * {@code 2026-10-31_13.17.20_ComplementaryUnbound_r5.9.3.png}. {@code packName} is null for no shaders.
     */
    public static String retakeCopy(String originalName, @Nullable String packName, Predicate<String> taken) {
        return unique(stem(originalName) + "_" + packPart(packName) + extension(originalName), taken);
    }

    /**
     * A pack's file name cut down to letters, digits, dots and dashes, with anything else as one underscore, so it is safe
     * in a file name on any system. Formatting codes and a {@code .zip} ending are dropped.
     */
    public static String packPart(@Nullable String packName) {
        if (packName == null) {
            return NO_SHADERS;
        }
        String name = packName.replaceAll("§.?", "").strip();
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            boolean keep = Character.isLetterOrDigit(c) || c == '.' || c == '-';
            if (keep) {
                out.appendCodePoint(c);
            } else if (out.isEmpty() || out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        String part = trimEnds(out.toString());
        if (part.length() > MAX_PACK_PART) {
            int end = MAX_PACK_PART;
            if (Character.isHighSurrogate(part.charAt(end - 1))) {
                end--;
            }
            part = trimEnds(part.substring(0, end));
        }
        return part.isEmpty() ? "shader" : part;
    }

    /** Dots and underscores at the ends would give names like {@code shot_.png} or {@code shot_pack..png}. */
    private static String trimEnds(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '_' || s.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '_' || s.charAt(end - 1) == '.')) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * Keeps {@code name} if it is free, otherwise adds {@code _2}, {@code _3}... the way vanilla does when two shots land in
     * the same second.
     */
    public static String unique(String name, Predicate<String> taken) {
        if (!taken.test(name)) {
            return name;
        }
        return firstFree(stem(name), extension(name), taken);
    }

    private static String firstFree(String stem, String extension, Predicate<String> taken) {
        String candidate = stem + extension;
        int i = 2;
        while (taken.test(candidate)) {
            candidate = stem + "_" + i + extension;
            i++;
        }
        return candidate;
    }

    static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : ".png";
    }
}
