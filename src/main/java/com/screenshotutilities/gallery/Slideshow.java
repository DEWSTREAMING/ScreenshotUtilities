package com.screenshotutilities.gallery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides which gallery picture is showing, which is next, and how far the cross-fade has got. It never touches files or
 * textures: the caller passes the time and says which pictures are loaded.
 *
 * <p>Order is random with no repeats until every picture has been shown, and the same picture never comes up twice in a
 * row unless it is the only one. Pictures reported broken are skipped until they leave the list.
 */
public final class Slideshow {
    /** If ticks stop for longer than this (the player was in a world), the clock is treated as paused. */
    static final long PAUSE_GAP_MILLIS = 1000;

    /** Alphas are 0 to 1. {@code next} is null when there is no cross-fade going on. */
    public record Frame(Path current, float currentAlpha, Path next, float nextAlpha) {
        public static final Frame NONE = new Frame(null, 0f, null, 0f);
    }

    private final Random random;
    private final Set<Path> pictures = new LinkedHashSet<>();
    private final Set<Path> broken = new HashSet<>();
    private final List<Path> bag = new ArrayList<>();

    private Path current;
    private Path next;
    private long currentSince = -1;
    private long fadeStart = -1;
    private long introStart = -1;
    private boolean everShown;
    private long lastTick = -1;

    public Slideshow(Random random) {
        this.random = random;
    }

    /** Replaces the list of gallery pictures. New files join the current round, removed ones leave it. */
    public void setPictures(Collection<Path> latest) {
        Set<Path> fresh = new LinkedHashSet<>(latest);
        for (Path p : fresh) {
            if (!pictures.contains(p) && !p.equals(current) && !p.equals(next)) {
                int at = bag.isEmpty() ? 0 : random.nextInt(bag.size() + 1);
                bag.add(at, p);
            }
        }
        pictures.clear();
        pictures.addAll(fresh);
        broken.retainAll(pictures);
        bag.retainAll(pictures);
        bag.removeAll(broken);
        if (next != null && !pictures.contains(next)) {
            next = null;
            fadeStart = -1;
        }
        if (usableCount() == 0 && !pictures.contains(current)) {
            reset();
        }
        if (current != null && next == null && fadeStart < 0) {
            next = pick(current);
        }
    }

    /** The picture can't be read. It is skipped for the rest of the session unless it leaves the list. */
    public void markBroken(Path path) {
        if (path == null || !broken.add(path)) {
            return;
        }
        bag.remove(path);
        if (path.equals(next)) {
            next = null;
            fadeStart = -1;
            if (current != null) {
                next = pick(current);
            }
        } else if (path.equals(current)) {
            current = next;
            next = null;
            currentSince = -1;
            fadeStart = -1;
            if (current == null) {
                everShown = false;
            } else {
                next = pick(current);
            }
        }
    }

    /** True when there is nothing that could be shown: no pictures, or none that can be read. */
    public boolean nothingToShow() {
        return current == null && usableCount() == 0;
    }

    public Path current() {
        return current;
    }

    public Path next() {
        return next;
    }

    /**
     * Moves the show on to {@code now} (milliseconds) and returns what to draw.
     *
     * @param periodMillis time each picture stays, counted from the end of the previous fade
     * @param fadeMillis length of the cross-fade
     * @param ready tells whether a picture's texture is loaded
     */
    public Frame tick(long now, long periodMillis, long fadeMillis, Predicate<Path> ready) {
        if (lastTick >= 0 && now - lastTick > PAUSE_GAP_MILLIS) {
            long gap = now - lastTick;
            if (currentSince >= 0) {
                currentSince += gap;
            }
            if (fadeStart >= 0) {
                fadeStart += gap;
            }
            if (introStart >= 0) {
                introStart += gap;
            }
        }
        lastTick = now;
        if (current == null) {
            current = pick(null);
            currentSince = -1;
            fadeStart = -1;
        }
        if (current == null) {
            return Frame.NONE;
        }
        if (next == null && fadeStart < 0) {
            next = pick(current);
        }
        if (currentSince < 0) {
            if (!ready.test(current)) {
                return new Frame(current, 0f, null, 0f);
            }
            currentSince = now;
            introStart = everShown ? -1 : now;
            everShown = true;
        }
        long hold = Math.max(0, periodMillis - fadeMillis);
        if (fadeStart < 0 && next != null && now - currentSince >= hold && ready.test(next)) {
            fadeStart = now;
        }
        if (fadeStart >= 0 && now - fadeStart >= fadeMillis) {
            current = next;
            currentSince = now;
            fadeStart = -1;
            introStart = -1;
            next = pick(current);
        }
        if (fadeStart >= 0) {
            return new Frame(current, 1f, next, progress(now - fadeStart, fadeMillis));
        }
        float alpha = introStart >= 0 ? progress(now - introStart, fadeMillis) : 1f;
        if (alpha >= 1f) {
            introStart = -1;
        }
        return new Frame(current, alpha, null, 0f);
    }

    private static float progress(long elapsed, long total) {
        if (total <= 0) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, (float) elapsed / total));
    }

    private void reset() {
        current = null;
        next = null;
        currentSince = -1;
        fadeStart = -1;
        introStart = -1;
        everShown = false;
        bag.clear();
    }

    private int usableCount() {
        int n = 0;
        for (Path p : pictures) {
            if (!broken.contains(p)) {
                n++;
            }
        }
        return n;
    }

    /** Takes the next picture from the round, starting a new round when it is used up. Null if only {@code avoid} is left. */
    private Path pick(Path avoid) {
        if (usableCount() == 0 || (usableCount() == 1 && avoid != null && pictures.contains(avoid))) {
            return null;
        }
        if (bag.isEmpty()) {
            for (Path p : pictures) {
                if (!broken.contains(p)) {
                    bag.add(p);
                }
            }
            Collections.shuffle(bag, random);
        }
        for (int i = 0; i < bag.size(); i++) {
            if (!bag.get(i).equals(avoid)) {
                return bag.remove(i);
            }
        }
        return null;
    }
}
