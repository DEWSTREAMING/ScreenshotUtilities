package com.screenshotutilities.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.screenshotutilities.ScreenshotUtilities;
import com.screenshotutilities.capture.CaptureSaver;
import com.screenshotutilities.gallery.GalleryStore;
import com.screenshotutilities.image.Downscale;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Gallery manager screen: scrolling thumbnail grid with worker-thread decoding, single-picture view, and safe removal
 * of gallery copies.
 */
public class GalleryScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int THUMB_MAX_WIDTH = 256;
    public static final int THUMB_MAX_HEIGHT = 144;
    private static final int MARGIN = 10;
    private static final int TOP = 22;
    private static final int ROW_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int GRID_GAP = 6;
    private static final int TARGET_CELL_WIDTH = 112;
    private static final int LOAD_AHEAD_ROWS = 2;
    private static final int EVICT_DISTANCE_ROWS = 6;
    private static final int MAX_CONCURRENT_THUMB_LOADS = 2;

    private static int nextTextureId;

    private final GalleryStore store;
    @Nullable
    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();

    private double scroll;
    private int lastFirstVisibleRow;
    private int lastVisibleRowEnd;
    private int lastCols = 3;
    private int activeThumbLoads;
    private boolean screenClosed;

    @Nullable
    private Entry selectedEntry;
    private boolean confirmingRemove;
    @Nullable
    private LoadedTexture detailTexture;
    private int detailLoadId;

    private enum State {
        UNLOADED,
        LOADING,
        READY,
        BROKEN
    }

    private record LoadedTexture(
            ResourceLocation id,
            DynamicTexture texture,
            int width,
            int height,
            int sourceWidth,
            int sourceHeight) {
    }

    private static final class Entry {
        final Path path;
        final String name;
        State state = State.UNLOADED;
        @Nullable
        LoadedTexture thumb;

        Entry(Path path) {
            this.path = path;
            Path fileName = path.getFileName();
            this.name = fileName != null ? fileName.toString() : path.toString();
        }
    }

    private record Rect(int left, int top, int right, int bottom) {
        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    public GalleryScreen(@Nullable Screen parent) {
        super(Component.translatable("screenshotutilities.gallery.title"));
        this.parent = parent;
        this.store = GalleryStore.fromGameDir(Minecraft.getInstance().gameDirectory.toPath());
        for (Path path : store.list()) {
            entries.add(new Entry(path));
        }
    }

    @Override
    protected void init() {
        screenClosed = false;
        int buttonY = height - ROW_HEIGHT - 6;
        List<Button> actions = new ArrayList<>();

        if (selectedEntry != null) {
            if (confirmingRemove) {
                actions.add(Button.builder(
                        Component.translatable("screenshotutilities.gallery.confirm_yes"),
                        b -> removeSelected()).build());
                actions.add(Button.builder(
                        Component.translatable("screenshotutilities.gallery.confirm_no"),
                        b -> {
                            confirmingRemove = false;
                            rebuildWidgets();
                        }).build());
            } else {
                actions.add(Button.builder(
                        Component.translatable("screenshotutilities.gallery.remove"),
                        b -> {
                            confirmingRemove = true;
                            rebuildWidgets();
                        }).build());
                actions.add(Button.builder(
                        Component.translatable("screenshotutilities.gallery.back"),
                        b -> closeDetailView()).build());
            }
        } else {
            actions.add(Button.builder(
                    Component.translatable("screenshotutilities.gallery.open_folder"),
                    b -> openFolder()).build());
            actions.add(Button.builder(
                    Component.translatable("screenshotutilities.popup.done"),
                    b -> onClose()).build());
        }

        layoutRow(actions, buttonY);
        actions.forEach(this::addRenderableWidget);
    }

    private void layoutRow(List<Button> buttons, int y) {
        if (buttons.isEmpty()) {
            return;
        }
        int n = buttons.size();
        int[] widths = new int[n];
        int gaps = GAP * (n - 1);
        int total = gaps;
        for (int i = 0; i < n; i++) {
            widths[i] = Math.max(70, font.width(buttons.get(i).getMessage()) + 16);
            total += widths[i];
        }
        int available = Math.max(80, width - 2 * MARGIN);
        if (total > available) {
            int even = Math.max(24, (available - gaps) / n);
            java.util.Arrays.fill(widths, even);
            total = even * n + gaps;
        }
        int x = Math.max(MARGIN, (width - total) / 2);
        for (int i = 0; i < n; i++) {
            Button button = buttons.get(i);
            button.setRectangle(widths[i], ROW_HEIGHT, x, y);
            x += widths[i] + GAP;
        }
    }

    private Rect contentArea() {
        int bottom = height - ROW_HEIGHT - 12;
        return new Rect(MARGIN, TOP, width - MARGIN, Math.max(TOP + 24, bottom));
    }

    private int columnCount(Rect area) {
        return Math.max(2, Math.min(6, (area.width() + GRID_GAP) / (TARGET_CELL_WIDTH + GRID_GAP)));
    }

    private void openFolder() {
        try {
            Files.createDirectories(store.folder());
            Util.getPlatform().openFile(store.folder().toFile());
        } catch ( Exception e) {
            LOGGER.warn("Couldn't open gallery folder", e);
        }
    }

    private void selectEntry(Entry entry) {
        selectedEntry = entry;
        confirmingRemove = false;
        releaseDetailTexture();
        if (entry.state != State.BROKEN) {
            startDetailLoad(entry);
        }
        rebuildWidgets();
    }

    private void closeDetailView() {
        selectedEntry = null;
        confirmingRemove = false;
        releaseDetailTexture();
        rebuildWidgets();
    }

    private void removeSelected() {
        Entry target = selectedEntry;
        if (target == null) {
            return;
        }
        try {
            store.remove(target.path);
        } catch (Exception e) {
            // The file is still there, so leave it in the grid.
            LOGGER.warn("Couldn't remove gallery file {}", target.path, e);
            closeDetailView();
            return;
        }
        if (target.thumb != null) {
            releaseLoadedTexture(target.thumb);
            target.thumb = null;
        }
        entries.remove(target);
        closeDetailView();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || selectedEntry != null || entries.isEmpty()) {
            return false;
        }
        Rect area = contentArea();
        if (!area.contains(mouseX, mouseY)) {
            return false;
        }
        int cols = columnCount(area);
        int rows = (entries.size() + cols - 1) / cols;
        int cellW = cellWidth(area, cols, rows);
        int cellH = cellHeight(cellW);
        int rowStep = cellH + GRID_GAP;

        int col = (int) Math.floor((mouseX - area.left()) / (cellW + GRID_GAP));
        int row = (int) Math.floor((mouseY - area.top() + scroll) / rowStep);
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return false;
        }
        int index = row * cols + col;
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        Rect cell = cellRect(area, col, row, cellW, cellH);
        if (cell.contains(mouseX, mouseY)) {
            selectEntry(entries.get(index));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (selectedEntry == null && !entries.isEmpty()) {
            Rect area = contentArea();
            int cols = columnCount(area);
            int rows = (entries.size() + cols - 1) / cols;
            int cellW = cellWidth(area, cols, rows);
            int cellH = cellHeight(cellW);
            int rowStep = cellH + GRID_GAP;
            int contentHeight = Math.max(0, rows * rowStep - GRID_GAP);
            int maxScroll = Math.max(0, contentHeight - area.height());
            scroll = Math.clamp(scroll - scrollY * (rowStep / 2.0), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (confirmingRemove) {
                confirmingRemove = false;
                rebuildWidgets();
                return true;
            }
            if (selectedEntry != null) {
                closeDetailView();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int cellWidth(Rect area, int cols, int rows) {
        int roughCellW = (area.width() - (cols - 1) * GRID_GAP) / cols;
        int roughCellH = cellHeight(roughCellW);
        int roughContentH = Math.max(0, rows * (roughCellH + GRID_GAP) - GRID_GAP);
        int scrollbarReserve = roughContentH > area.height() ? 6 : 0;
        int usableW = Math.max(cols * 24, area.width() - scrollbarReserve);
        return Math.max(24, (usableW - (cols - 1) * GRID_GAP) / cols);
    }

    private static int cellHeight(int cellWidth) {
        return Math.max(18, cellWidth * 9 / 16);
    }

    private Rect cellRect(Rect area, int col, int row, int cellW, int cellH) {
        int x = area.left() + col * (cellW + GRID_GAP);
        int y = area.top() + row * (cellH + GRID_GAP) - (int) Math.round(scroll);
        return new Rect(x, y, x + cellW, y + cellH);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        if (selectedEntry != null) {
            renderDetailView(graphics);
            return;
        }

        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        Rect area = contentArea();
        if (entries.isEmpty()) {
            Component empty = Component.translatable("screenshotutilities.gallery.empty");
            int y = area.top() + (area.height() - font.lineHeight) / 2;
            graphics.drawCenteredString(font, empty, width / 2, y, 0xAAAAAA);
            return;
        }

        int cols = columnCount(area);
        int rows = (entries.size() + cols - 1) / cols;
        int cellW = cellWidth(area, cols, rows);
        int cellH = cellHeight(cellW);
        int rowStep = cellH + GRID_GAP;
        int contentHeight = Math.max(0, rows * rowStep - GRID_GAP);
        int maxScroll = Math.max(0, contentHeight - area.height());
        scroll = Math.clamp(scroll, 0, maxScroll);

        int firstVisibleRow = Math.max(0, (int) Math.floor(scroll / rowStep));
        int lastVisibleRow = Math.min(rows - 1, (int) Math.floor((scroll + area.height() - 1) / rowStep));
        lastFirstVisibleRow = firstVisibleRow;
        lastVisibleRowEnd = lastVisibleRow;
        lastCols = cols;

        evictDistantThumbnails(firstVisibleRow, lastVisibleRow, cols);
        scheduleVisibleThumbLoads();

        Entry hoveredEntry = null;
        graphics.enableScissor(area.left(), area.top(), area.right(), area.bottom());
        int startIndex = firstVisibleRow * cols;
        int endIndex = Math.min(entries.size(), (lastVisibleRow + 1) * cols);
        for (int i = startIndex; i < endIndex; i++) {
            Entry entry = entries.get(i);
            int row = i / cols;
            int col = i % cols;
            Rect cell = cellRect(area, col, row, cellW, cellH);
            if (cell.bottom() <= area.top() || cell.top() >= area.bottom()) {
                continue;
            }
            boolean hovered = area.contains(mouseX, mouseY) && cell.contains(mouseX, mouseY);
            if (hovered) {
                hoveredEntry = entry;
            }
            renderThumbnailCell(graphics, cell, entry, hovered);
        }
        graphics.disableScissor();

        if (maxScroll > 0 && area.height() > 0) {
            int barX = area.right() - 2;
            graphics.fill(barX, area.top(), barX + 2, area.bottom(), 0x55000000);
            int thumbH = Math.max(16, area.height() * area.height() / contentHeight);
            int thumbY = area.top() + (int) Math.round((area.height() - thumbH) * (scroll / maxScroll));
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFAAAAAA);
        }

        if (hoveredEntry != null) {
            graphics.renderTooltip(font, Component.literal(hoveredEntry.name), mouseX, mouseY);
        }
    }

    private void renderThumbnailCell(GuiGraphics graphics, Rect cell, Entry entry, boolean hovered) {
        int borderColor;
        int bgColor;
        if (entry.state == State.BROKEN) {
            borderColor = hovered ? 0xFFFF8888 : 0xFF884444;
            bgColor = 0xFF241414;
        } else {
            borderColor = hovered ? 0xFFFFFFFF : 0xFF484848;
            bgColor = 0xFF161616;
        }
        graphics.fill(cell.left(), cell.top(), cell.right(), cell.bottom(), borderColor);
        graphics.fill(cell.left() + 1, cell.top() + 1, cell.right() - 1, cell.bottom() - 1, bgColor);

        Rect inner = new Rect(cell.left() + 1, cell.top() + 1, cell.right() - 1, cell.bottom() - 1);
        if (entry.state == State.READY && entry.thumb != null) {
            LoadedTexture tex = entry.thumb;
            Rect imgRect = fitRect(inner, tex.width(), tex.height());
            graphics.blit(
                    tex.id(),
                    imgRect.left(),
                    imgRect.top(),
                    imgRect.width(),
                    imgRect.height(),
                    0,
                    0,
                    tex.width(),
                    tex.height(),
                    tex.width(),
                    tex.height());
        } else if (entry.state == State.BROKEN) {
            drawBrokenMarker(graphics, inner);
        }
    }

    private void drawBrokenMarker(GuiGraphics graphics, Rect inner) {
        int cx = inner.left() + inner.width() / 2;
        int cy = inner.top() + inner.height() / 2;
        graphics.fill(cx - 8, cy - 1, cx + 8, cy + 1, 0xFFAA5555);
        graphics.fill(cx - 1, cy - 8, cx + 1, cy + 8, 0xFFAA5555);
        Component broken = Component.translatable("screenshotutilities.gallery.broken_short");
        if (inner.width() >= font.width(broken) + 6 && inner.height() >= font.lineHeight + 22) {
            graphics.drawCenteredString(font, broken, cx, cy + 10, 0xCC8888);
        }
    }

    private void renderDetailView(GuiGraphics graphics) {
        Entry entry = selectedEntry;
        if (entry == null) {
            return;
        }
        graphics.drawCenteredString(font, Component.literal(entry.name), width / 2, 8, 0xFFFFFF);
        Rect area = contentArea();
        if (confirmingRemove) {
            area = new Rect(area.left(), area.top(), area.right(), Math.max(area.top() + 24, area.bottom() - 22));
        }

        LoadedTexture tex = detailTexture != null ? detailTexture : entry.thumb;
        if (entry.state == State.BROKEN) {
            Rect frame = fitRect(area, 16, 9);
            graphics.fill(frame.left() - 1, frame.top() - 1, frame.right() + 1, frame.bottom() + 1, 0xFF884444);
            graphics.fill(frame.left(), frame.top(), frame.right(), frame.bottom(), 0xFF241414);
            Component msg = Component.translatable("screenshotutilities.gallery.broken");
            int y = frame.top() + (frame.height() - font.lineHeight) / 2;
            graphics.drawCenteredString(font, msg, width / 2, y, 0xDD9999);
        } else if (tex != null) {
            Rect imgRect = fitRect(area, tex.width(), tex.height());
            graphics.fill(imgRect.left() - 1, imgRect.top() - 1, imgRect.right() + 1, imgRect.bottom() + 1, 0xFF000000);
            graphics.blit(
                    tex.id(),
                    imgRect.left(),
                    imgRect.top(),
                    imgRect.width(),
                    imgRect.height(),
                    0,
                    0,
                    tex.width(),
                    tex.height(),
                    tex.width(),
                    tex.height());
        } else {
            Rect frame = fitRect(area, 16, 9);
            graphics.fill(frame.left() - 1, frame.top() - 1, frame.right() + 1, frame.bottom() + 1, 0xFF484848);
            graphics.fill(frame.left(), frame.top(), frame.right(), frame.bottom(), 0xFF161616);
        }

        if (confirmingRemove) {
            Component prompt = Component.translatable("screenshotutilities.gallery.remove_confirm");
            int textW = font.width(prompt);
            int promptY = height - ROW_HEIGHT - 6 - font.lineHeight - 8;
            int pillX = Math.max(MARGIN, (width - textW) / 2);
            graphics.fill(pillX - 6, promptY - 3, pillX + textW + 6, promptY + font.lineHeight + 3, 0xCC000000);
            graphics.drawString(font, prompt, pillX, promptY, 0xFFFFFF, false);
        }
    }

    private static Rect fitRect(Rect area, int srcW, int srcH) {
        double scale = Math.min((double) area.width() / Math.max(1, srcW), (double) area.height() / Math.max(1, srcH));
        int w = Math.max(1, (int) Math.round(srcW * scale));
        int h = Math.max(1, (int) Math.round(srcH * scale));
        int x = area.left() + (area.width() - w) / 2;
        int y = area.top() + (area.height() - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    private void evictDistantThumbnails(int firstVisibleRow, int lastVisibleRow, int cols) {
        int keepStartRow = Math.max(0, firstVisibleRow - EVICT_DISTANCE_ROWS);
        int keepEndRow = lastVisibleRow + EVICT_DISTANCE_ROWS;
        for (int i = 0; i < entries.size(); i++) {
            int row = i / cols;
            if (row < keepStartRow || row > keepEndRow) {
                Entry entry = entries.get(i);
                if (entry.state == State.READY && entry.thumb != null && entry != selectedEntry) {
                    releaseLoadedTexture(entry.thumb);
                    entry.thumb = null;
                    entry.state = State.UNLOADED;
                }
            }
        }
    }

    private void scheduleVisibleThumbLoads() {
        if (screenClosed || entries.isEmpty()) {
            return;
        }
        int startRow = Math.max(0, lastFirstVisibleRow - 1);
        int endRow = lastVisibleRowEnd + LOAD_AHEAD_ROWS;
        int startIdx = startRow * lastCols;
        int endIdx = Math.min(entries.size(), (endRow + 1) * lastCols);

        for (int i = startIdx; i < endIdx && activeThumbLoads < MAX_CONCURRENT_THUMB_LOADS; i++) {
            Entry entry = entries.get(i);
            if (entry.state == State.UNLOADED) {
                startThumbLoad(entry);
            }
        }
    }

    private void startThumbLoad(Entry entry) {
        entry.state = State.LOADING;
        activeThumbLoads++;
        Path path = entry.path;
        try {
            Util.ioPool().execute(() -> {
                if (screenClosed) {
                    onThumbDecodeFailed(entry, false);
                    return;
                }
                NativeImage small = null;
                try (InputStream in = Files.newInputStream(path);
                        NativeImage full = NativeImage.read(in)) {
                    int srcW = full.getWidth();
                    int srcH = full.getHeight();
                    int[] size = Downscale.fitWithin(srcW, srcH, THUMB_MAX_WIDTH, THUMB_MAX_HEIGHT);
                    int w = size[0];
                    int h = size[1];
                    int[] pixels = Downscale.box(full::getPixelRGBA, srcW, srcH, w, h);
                    small = CaptureSaver.imageFromPixels(pixels, w, h);
                    NativeImage decoded = small;
                    small = null;
                    deliverThumbOnRenderThread(entry, decoded, w, h, srcW, srcH);
                } catch (Throwable t) {
                    if (small != null) {
                        small.close();
                    }
                    LOGGER.debug("Couldn't decode gallery thumbnail {}", path, t);
                    onThumbDecodeFailed(entry, true);
                }
            });
        } catch (Throwable t) {
            entry.state = State.UNLOADED;
            activeThumbLoads = Math.max(0, activeThumbLoads - 1);
        }
    }

    private void deliverThumbOnRenderThread(
            Entry entry, NativeImage small, int width, int height, int sourceWidth, int sourceHeight) {
        try {
            minecraft.execute(() -> {
                activeThumbLoads = Math.max(0, activeThumbLoads - 1);
                if (screenClosed || !entries.contains(entry) || entry.state != State.LOADING) {
                    small.close();
                    scheduleVisibleThumbLoads();
                    return;
                }
                try {
                    DynamicTexture texture = new DynamicTexture(small);
                    texture.setFilter(true, false);
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                            ScreenshotUtilities.MOD_ID, "gallery_thumb/" + nextTextureId++);
                    minecraft.getTextureManager().register(id, texture);
                    entry.thumb = new LoadedTexture(id, texture, width, height, sourceWidth, sourceHeight);
                    entry.state = State.READY;
                } catch (Throwable t) {
                    small.close();
                    entry.state = State.BROKEN;
                }
                scheduleVisibleThumbLoads();
            });
        } catch (Throwable t) {
            small.close();
        }
    }

    private void onThumbDecodeFailed(Entry entry, boolean broken) {
        try {
            minecraft.execute(() -> {
                activeThumbLoads = Math.max(0, activeThumbLoads - 1);
                if (!screenClosed && entries.contains(entry)) {
                    entry.state = broken ? State.BROKEN : State.UNLOADED;
                }
                scheduleVisibleThumbLoads();
            });
        } catch (Throwable ignored) {
        }
    }

    private void startDetailLoad(Entry entry) {
        int loadId = ++detailLoadId;
        Path path = entry.path;
        try {
            Util.ioPool().execute(() -> {
                if (screenClosed || selectedEntry != entry || detailLoadId != loadId) {
                    return;
                }
                NativeImage scaled = null;
                try (InputStream in = Files.newInputStream(path);
                        NativeImage full = NativeImage.read(in)) {
                    int srcW = full.getWidth();
                    int srcH = full.getHeight();
                    int[] size = Downscale.fitWithin(
                            srcW, srcH, ScreenshotPopupScreen.PREVIEW_MAX_WIDTH, ScreenshotPopupScreen.PREVIEW_MAX_HEIGHT);
                    int w = size[0];
                    int h = size[1];
                    int[] pixels = Downscale.box(full::getPixelRGBA, srcW, srcH, w, h);
                    scaled = CaptureSaver.imageFromPixels(pixels, w, h);
                    NativeImage decoded = scaled;
                    scaled = null;
                    deliverDetailOnRenderThread(entry, loadId, decoded, w, h, srcW, srcH);
                } catch (Throwable t) {
                    if (scaled != null) {
                        scaled.close();
                    }
                    LOGGER.debug("Couldn't decode gallery detail image {}", path, t);
                    try {
                        minecraft.execute(() -> {
                            if (!screenClosed && selectedEntry == entry && detailLoadId == loadId) {
                                entry.state = State.BROKEN;
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void deliverDetailOnRenderThread(
            Entry entry, int loadId, NativeImage image, int width, int height, int sourceWidth, int sourceHeight) {
        try {
            minecraft.execute(() -> {
                if (screenClosed || selectedEntry != entry || detailLoadId != loadId) {
                    image.close();
                    return;
                }
                releaseDetailTexture();
                try {
                    DynamicTexture texture = new DynamicTexture(image);
                    texture.setFilter(true, false);
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                            ScreenshotUtilities.MOD_ID, "gallery_detail/" + nextTextureId++);
                    minecraft.getTextureManager().register(id, texture);
                    detailTexture = new LoadedTexture(id, texture, width, height, sourceWidth, sourceHeight);
                } catch (Throwable t) {
                    image.close();
                }
            });
        } catch (Throwable t) {
            image.close();
        }
    }

    private void releaseDetailTexture() {
        detailLoadId++;
        if (detailTexture != null) {
            releaseLoadedTexture(detailTexture);
            detailTexture = null;
        }
    }

    private void releaseAllTextures() {
        releaseDetailTexture();
        for (Entry entry : entries) {
            if (entry.thumb != null) {
                releaseLoadedTexture(entry.thumb);
                entry.thumb = null;
            }
            if (entry.state == State.READY || entry.state == State.LOADING) {
                entry.state = State.UNLOADED;
            }
        }
    }

    private void releaseLoadedTexture(LoadedTexture loaded) {
        try {
            minecraft.getTextureManager().release(loaded.id());
        } catch (Throwable ignored) {
        }
        try {
            loaded.texture().close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void removed() {
        screenClosed = true;
        releaseAllTextures();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
