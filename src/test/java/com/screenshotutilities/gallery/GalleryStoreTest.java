package com.screenshotutilities.gallery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GalleryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void missingFolderReturnsEmptyListWithoutCreatingFolder() {
        Path galleryDir = tempDir.resolve("screenshots").resolve("gallery");
        GalleryStore store = GalleryStore.fromGameDir(tempDir);

        assertEquals(galleryDir.toAbsolutePath().normalize(), store.folder());
        assertEquals(List.of(), store.list());
        assertFalse(Files.exists(galleryDir));
    }

    @Test
    void fileAtFolderPathReturnsEmptyListWithoutCrashing() throws IOException {
        Path notADir = tempDir.resolve("not_a_directory");
        Files.writeString(notADir, "plain file", StandardCharsets.UTF_8);
        GalleryStore store = new GalleryStore(notADir);

        assertEquals(List.of(), store.list());
    }

    @Test
    void listsPngFilesNewestFirstAndSkipsNonPngsAndSubfolders() throws IOException {
        Path galleryDir = tempDir.resolve("gallery");
        Files.createDirectories(galleryDir);
        GalleryStore store = new GalleryStore(galleryDir);

        Path oldest = Files.writeString(galleryDir.resolve("2026-10-31_10.00.00.png"), "1", StandardCharsets.UTF_8);
        Path middle = Files.writeString(galleryDir.resolve("2026-10-31_10.05.00.PNG"), "2", StandardCharsets.UTF_8);
        Path newest = Files.writeString(galleryDir.resolve("2026-10-31_10.10.00.png"), "3", StandardCharsets.UTF_8);

        Files.setLastModifiedTime(oldest, FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(middle, FileTime.fromMillis(2_000_000L));
        Files.setLastModifiedTime(newest, FileTime.fromMillis(3_000_000L));

        // Non-PNG files, bare ".png", and subdirectories should be ignored silently.
        Files.writeString(galleryDir.resolve("notes.txt"), "text", StandardCharsets.UTF_8);
        Files.writeString(galleryDir.resolve("photo.jpg"), "jpg", StandardCharsets.UTF_8);
        Files.writeString(galleryDir.resolve(".png"), "dotfile", StandardCharsets.UTF_8);
        Path nestedDir = Files.createDirectories(galleryDir.resolve("subfolder.png"));
        Files.writeString(nestedDir.resolve("inside.png"), "nested", StandardCharsets.UTF_8);

        assertEquals(
                List.of(
                        newest.toAbsolutePath().normalize(),
                        middle.toAbsolutePath().normalize(),
                        oldest.toAbsolutePath().normalize()),
                store.list());
    }

    @Test
    void addAndCopyIntoCreateFolderAndPickFreeNamesWithoutOverwriting() throws IOException {
        Path galleryDir = tempDir.resolve("screenshots").resolve("gallery");
        GalleryStore store = new GalleryStore(galleryDir);

        Path first = store.add("2026-10-31_12.00.00.png", p -> Files.write(p, new byte[]{1, 2, 3}));
        Path second = store.add("2026-10-31_12.00.00.png", p -> Files.write(p, new byte[]{4, 5, 6}));

        Path sourceFile = tempDir.resolve("2026-10-31_12.00.00.png");
        Files.write(sourceFile, new byte[]{7, 8, 9});
        Path third = store.copyInto(sourceFile);

        assertEquals("2026-10-31_12.00.00.png", first.getFileName().toString());
        assertEquals("2026-10-31_12.00.00_2.png", second.getFileName().toString());
        assertEquals("2026-10-31_12.00.00_3.png", third.getFileName().toString());

        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(first));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(second));
        assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(third));
    }

    @Test
    void removeDeletesGalleryFileAndRefusesPathsOutsideGalleryFolder() throws IOException {
        Path screenshotsDir = Files.createDirectories(tempDir.resolve("screenshots"));
        Path original = Files.writeString(screenshotsDir.resolve("original.png"), "keep", StandardCharsets.UTF_8);
        GalleryStore store = GalleryStore.fromScreenshotsDir(screenshotsDir);

        Path galleryCopy = store.copyInto(original);
        assertTrue(Files.exists(galleryCopy));

        // Refuses parent folder file, traversal path, sibling folder file, and directory.
        assertThrows(IllegalArgumentException.class, () -> store.remove(original));
        assertThrows(IllegalArgumentException.class, () -> store.remove(store.folder().resolve("..").resolve("original.png")));
        assertThrows(IllegalArgumentException.class, () -> store.remove(store.folder()));
        Path subDir = Files.createDirectories(store.folder().resolve("nested.png"));
        assertThrows(IllegalArgumentException.class, () -> store.remove(subDir));
        assertTrue(Files.exists(original));

        // Deletes the gallery copy and returns false if already gone.
        assertTrue(store.remove(galleryCopy));
        assertFalse(Files.exists(galleryCopy));
        assertFalse(store.remove(galleryCopy));
        assertTrue(Files.exists(original));
    }
}
