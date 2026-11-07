package com.screenshotutilities.gallery;

import com.screenshotutilities.image.FileNames;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Plain file store for {@code <instance>/screenshots/gallery/}. Keeps no index or database: the PNG files currently in
 * the folder are the gallery.
 */
public final class GalleryStore {
    public static final String SCREENSHOTS_DIR = "screenshots";
    public static final String GALLERY_DIR = "gallery";
    private static final String PNG_EXTENSION = ".png";
    private static final Object WRITE_LOCK = new Object();

    @FunctionalInterface
    public interface PictureWriter {
        void write(Path target) throws IOException;
    }

    private record DatedFile(Path path, long modifiedMillis, String name) {
    }

    private static final Comparator<DatedFile> NEWEST_FIRST = Comparator
            .comparingLong(DatedFile::modifiedMillis)
            .reversed()
            .thenComparing((a, b) -> b.name().compareToIgnoreCase(a.name()));

    private final Path folder;

    public GalleryStore(Path folder) {
        Objects.requireNonNull(folder, "folder");
        this.folder = folder.toAbsolutePath().normalize();
    }

    public static GalleryStore fromGameDir(Path gameDir) {
        Objects.requireNonNull(gameDir, "gameDir");
        return new GalleryStore(gameDir.resolve(SCREENSHOTS_DIR).resolve(GALLERY_DIR));
    }

    public static GalleryStore fromScreenshotsDir(Path screenshotsDir) {
        Objects.requireNonNull(screenshotsDir, "screenshotsDir");
        return new GalleryStore(screenshotsDir.resolve(GALLERY_DIR));
    }

    public Path folder() {
        return folder;
    }

    /**
     * Lists regular PNG files in the gallery folder, newest first. Returns an empty list if the folder does not exist
     * or cannot be read.
     */
    public List<Path> list() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<DatedFile> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                try {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    Path fileName = entry.getFileName();
                    if (fileName == null) {
                        continue;
                    }
                    String name = fileName.toString();
                    if (!isPngFileName(name)) {
                        continue;
                    }
                    FileTime mtime = Files.getLastModifiedTime(entry);
                    entries.add(new DatedFile(entry.toAbsolutePath().normalize(), mtime.toMillis(), name));
                } catch (IOException | SecurityException ignored) {
                    // Skip unreadable individual entries without failing the listing.
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        entries.sort(NEWEST_FIRST);
        List<Path> result = new ArrayList<>(entries.size());
        for (DatedFile entry : entries) {
            result.add(entry.path());
        }
        return List.copyOf(result);
    }

    /**
     * Creates the gallery folder if needed, picks a free PNG filename based on {@code preferredName}, and invokes
     * {@code writer} to write the picture to that path.
     */
    public Path add(String preferredName, PictureWriter writer) throws IOException {
        Objects.requireNonNull(preferredName, "preferredName");
        Objects.requireNonNull(writer, "writer");
        // Every add makes its own store, so the lock is shared or two adds could pick the same free name.
        synchronized (WRITE_LOCK) {
            Files.createDirectories(folder);
            String baseName = sanitizePngName(preferredName);
            String freeName = FileNames.unique(baseName, n -> Files.exists(folder.resolve(n), LinkOption.NOFOLLOW_LINKS));
            Path target = folder.resolve(freeName).toAbsolutePath().normalize();
            if (!folder.equals(target.getParent())) {
                throw new IllegalArgumentException("Resolved target outside gallery folder: " + target);
            }
            writer.write(target);
            return target;
        }
    }

    /**
     * Copies an existing PNG file into the gallery folder under a free name derived from its filename, never
     * overwriting an existing gallery file.
     */
    public Path copyInto(Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        Path fileName = sourceFile.getFileName();
        String preferred = fileName != null ? fileName.toString() : "screenshot.png";
        return add(preferred, target -> Files.copy(sourceFile, target));
    }

    /**
     * Removes a single file from the gallery folder. Refuses any path outside the gallery folder or any directory.
     */
    public synchronized boolean remove(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Path normalized = file.toAbsolutePath().normalize();
        if (!folder.equals(normalized.getParent())) {
            throw new IllegalArgumentException("Refusing to remove path outside gallery folder: " + file);
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Refusing to remove directory: " + file);
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("Refusing to remove symbolic link: " + file);
        }
        return Files.deleteIfExists(normalized);
    }

    static boolean isPngFileName(String name) {
        if (name == null || name.length() <= PNG_EXTENSION.length()) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).endsWith(PNG_EXTENSION);
    }

    static String sanitizePngName(String rawName) {
        String trimmed = rawName.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "screenshot.png";
        }
        if (!isPngFileName(name)) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            return (stem.isEmpty() ? "screenshot" : stem) + PNG_EXTENSION;
        }
        return name;
    }
}
