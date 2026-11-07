package com.screenshotutilities.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlideshowTest {
    private static final long PERIOD = 8000;
    private static final long FADE = 1000;

    private static List<Path> paths(int n) {
        List<Path> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(Path.of("gallery", "p" + i + ".png"));
        }
        return list;
    }

    private static Slideshow.Frame tick(Slideshow s, long now) {
        return s.tick(now, PERIOD, FADE, p -> true);
    }

    /** Runs the show and records each picture as it becomes the current one. */
    private static List<Path> sequence(Slideshow s, int count) {
        List<Path> seen = new ArrayList<>();
        long now = 0;
        while (seen.size() < count) {
            Slideshow.Frame f = tick(s, now);
            if (f.current() != null && (seen.isEmpty() || !seen.get(seen.size() - 1).equals(f.current()))) {
                seen.add(f.current());
            }
            now += 100;
            if (now > 10_000_000) {
                throw new AssertionError("show stopped moving");
            }
        }
        return seen;
    }

    @Test
    void everyPictureShownOncePerRound() {
        for (long seed = 0; seed < 20; seed++) {
            Slideshow s = new Slideshow(new Random(seed));
            s.setPictures(paths(5));
            List<Path> seen = sequence(s, 15);
            for (int round = 0; round < 3; round++) {
                Set<Path> inRound = new HashSet<>(seen.subList(round * 5, round * 5 + 5));
                assertEquals(5, inRound.size(), "seed " + seed + " round " + round);
            }
        }
    }

    @Test
    void neverTheSameTwiceInARow() {
        for (long seed = 0; seed < 50; seed++) {
            Slideshow s = new Slideshow(new Random(seed));
            s.setPictures(paths(2));
            List<Path> seen = sequence(s, 30);
            for (int i = 1; i < seen.size(); i++) {
                assertNotEquals(seen.get(i - 1), seen.get(i), "seed " + seed);
            }
        }
    }

    @Test
    void onePictureStaysWithNoFade() {
        Slideshow s = new Slideshow(new Random(1));
        Path only = paths(1).get(0);
        s.setPictures(List.of(only));
        Slideshow.Frame f = tick(s, 0);
        assertEquals(only, f.current());
        for (long now = 100; now < 60_000; now += 100) {
            f = tick(s, now);
            assertEquals(only, f.current());
            assertNull(f.next());
            if (now >= 1000) {
                assertEquals(1f, f.currentAlpha());
            }
        }
        assertNull(s.next());
    }

    @Test
    void noPicturesMeansNothingToShow() {
        Slideshow s = new Slideshow(new Random(1));
        assertTrue(s.nothingToShow());
        assertNull(tick(s, 0).current());
        s.setPictures(List.of());
        assertNull(tick(s, 5000).current());
    }

    @Test
    void firstPictureFadesInThenHolds() {
        Slideshow s = new Slideshow(new Random(1));
        s.setPictures(paths(3));
        assertEquals(0f, tick(s, 0).currentAlpha(), 0.001f);
        assertEquals(0.5f, tick(s, 500).currentAlpha(), 0.001f);
        assertEquals(1f, tick(s, 1000).currentAlpha(), 0.001f);
    }

    @Test
    void waitsForTheFirstPictureToLoad() {
        Slideshow s = new Slideshow(new Random(1));
        s.setPictures(paths(3));
        Slideshow.Frame f = s.tick(0, PERIOD, FADE, p -> false);
        assertEquals(0f, f.currentAlpha());
        f = s.tick(500, PERIOD, FADE, p -> false);
        assertEquals(0f, f.currentAlpha());
        f = s.tick(600, PERIOD, FADE, p -> true);
        assertEquals(0f, f.currentAlpha(), 0.001f);
        f = s.tick(1100, PERIOD, FADE, p -> true);
        assertEquals(0.5f, f.currentAlpha(), 0.001f);
    }

    @Test
    void crossFadeProgress() {
        Slideshow s = new Slideshow(new Random(1));
        s.setPictures(paths(3));
        tick(s, 0);
        Path first = s.current();
        Slideshow.Frame f = tick(s, 1000);
        assertNull(f.next());
        f = tick(s, 1500);
        f = tick(s, 2000);
        f = tick(s, 2900);
        f = tick(s, 3800);
        f = tick(s, 4700);
        f = tick(s, 5600);
        f = tick(s, 6500);
        f = tick(s, 7000);
        assertEquals(first, f.current());
        assertEquals(s.next(), f.next());
        assertEquals(0f, f.nextAlpha(), 0.001f);
        f = tick(s, 7500);
        assertEquals(1f, f.currentAlpha());
        assertEquals(0.5f, f.nextAlpha(), 0.001f);
        Path incoming = f.next();
        f = tick(s, 8000);
        assertEquals(incoming, f.current());
        assertNull(f.next());
        assertEquals(1f, f.currentAlpha());
    }

    @Test
    void doesNotFadeUntilNextIsLoaded() {
        Slideshow s = new Slideshow(new Random(1));
        s.setPictures(paths(3));
        s.tick(0, PERIOD, FADE, p -> true);
        Path first = s.current();
        Path second = s.next();
        long now = 0;
        Slideshow.Frame f = null;
        while (now < 20_000) {
            now += 500;
            f = s.tick(now, PERIOD, FADE, p -> p.equals(first));
        }
        assertNull(f.next());
        assertEquals(first, f.current());
        f = s.tick(now + 100, PERIOD, FADE, p -> true);
        assertEquals(second, f.next());
    }

    @Test
    void brokenPictureIsSkippedForever() {
        Slideshow s = new Slideshow(new Random(3));
        List<Path> all = paths(4);
        s.setPictures(all);
        tick(s, 0);
        Path bad = s.next();
        s.markBroken(bad);
        s.setPictures(all);
        List<Path> seen = sequence(s, 12);
        assertFalse(seen.contains(bad));
    }

    @Test
    void brokenCurrentMovesOn() {
        Slideshow s = new Slideshow(new Random(3));
        s.setPictures(paths(3));
        tick(s, 0);
        Path bad = s.current();
        Path expected = s.next();
        s.markBroken(bad);
        assertEquals(expected, s.current());
        assertNotEquals(bad, s.next());
    }

    @Test
    void allBrokenMeansNothingToShow() {
        Slideshow s = new Slideshow(new Random(3));
        List<Path> all = paths(3);
        s.setPictures(all);
        tick(s, 0);
        for (Path p : all) {
            s.markBroken(p);
        }
        assertTrue(s.nothingToShow());
        assertNull(tick(s, 100).current());
    }

    @Test
    void removedFileLeavesTheShow() {
        Slideshow s = new Slideshow(new Random(5));
        List<Path> all = paths(4);
        s.setPictures(all);
        tick(s, 0);
        Path gone = s.next();
        List<Path> left = new ArrayList<>(all);
        left.remove(gone);
        s.setPictures(left);
        assertNotEquals(gone, s.next());
        List<Path> seen = sequence(s, 12);
        assertFalse(seen.subList(1, seen.size()).contains(gone));
    }

    @Test
    void removingEverythingResets() {
        Slideshow s = new Slideshow(new Random(5));
        s.setPictures(paths(3));
        tick(s, 0);
        s.setPictures(List.of());
        assertTrue(s.nothingToShow());
        assertNull(tick(s, 100).current());
        s.setPictures(paths(2));
        assertEquals(0f, tick(s, 200).currentAlpha(), 0.001f);
        assertTrue(tick(s, 700).currentAlpha() > 0f);
    }

    @Test
    void addedFileJoinsTheRound() {
        Slideshow s = new Slideshow(new Random(5));
        List<Path> all = paths(3);
        s.setPictures(all);
        tick(s, 0);
        Path extra = Path.of("gallery", "extra.png");
        List<Path> more = new ArrayList<>(all);
        more.add(extra);
        s.setPictures(more);
        assertTrue(sequence(s, 12).contains(extra));
    }

    @Test
    void longGapPausesTheClock() {
        Slideshow s = new Slideshow(new Random(1));
        s.setPictures(paths(3));
        tick(s, 0);
        tick(s, 1000);
        tick(s, 1900);
        Slideshow.Frame f = tick(s, 3_600_000);
        assertNull(f.next());
        f = tick(s, 3_600_000 + 500);
        assertNull(f.next());
        long now = 3_600_500;
        while (now < 3_600_000 + 8000 && f.next() == null) {
            now += 500;
            f = tick(s, now);
        }
        assertNotNull(f.next());
    }
}
