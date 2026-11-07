package com.screenshotutilities.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.screenshotutilities.ScreenshotUtilities;
import com.screenshotutilities.capture.Capture;
import com.screenshotutilities.capture.CaptureSaver;
import com.screenshotutilities.capture.CaptureStage;
import com.screenshotutilities.capture.HudGroups;
import com.screenshotutilities.clipboard.ScreenshotClipboard;
import com.screenshotutilities.compat.IrisShaders;
import com.screenshotutilities.compat.ShaderSetup;
import com.screenshotutilities.config.ScreenshotConfig;
import com.screenshotutilities.image.CarriedEdits;
import com.screenshotutilities.image.CarriedEdits.Added;
import com.screenshotutilities.image.CropRect;
import com.screenshotutilities.image.CropRect.Handle;
import com.screenshotutilities.image.Downscale;
import com.screenshotutilities.image.EntityMask;
import com.screenshotutilities.image.FilterSettings;
import com.screenshotutilities.image.Filters;
import com.screenshotutilities.image.KeepColour;
import com.screenshotutilities.image.LayerStack;
import com.screenshotutilities.image.MagicWand;
import com.screenshotutilities.image.MobPick;
import com.screenshotutilities.image.PickedArea;
import com.screenshotutilities.image.ZoomView;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Shows the last capture with the actions that can be taken on it. Holds its own lease on the capture, so a new F2 or the
 * holder letting go doesn't pull the pictures out from under it.
 */
public class ScreenshotPopupScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Preview texture size cap. The texture is drawn with linear filtering, so it can be shown a bit smaller or larger. */
    public static final int PREVIEW_MAX_WIDTH = 1280;
    public static final int PREVIEW_MAX_HEIGHT = 720;
    private static final int MARGIN = 10;
    private static final int TOP = 22;
    private static final int ROW_HEIGHT = 20;
    private static final int GAP = 4;
    /** Room on the right for the HUD element list. */
    private static final int SIDE_PANEL_WIDTH = 120;
    private static final int SIDE_PANEL_MIN_SCREEN_WIDTH = 420;
    private static final int GROUP_HEADER_HEIGHT = 16;
    private static final int GROUP_FOOTER_HEIGHT = 22;
    private static final int GROUP_ROW_HEIGHT = 18;
    private static final int GROUP_BOX_SIZE = 12;
    private static final int HANDLE_HALF_SIZE = 2;
    private static final int HANDLE_HIT_RADIUS = 6;
    private static final int MIN_ERASE_DRAG_PIXELS = 2;
    private static final long OVERLAY_DURATION_MS = 3000;
    /** The mask comes a frame or two after the shot. A click that beats it waits this long, then uses the colour wand. */
    private static final long MOB_MASK_WAIT_MS = 500;
    private static final int LIST_MIN_WIDTH = 110;
    private static final int LIST_ROW_HEIGHT = 16;
    private static final int LIST_TEXT_INDENT = 18;
    private static final int HIDE_HUD_ROW_HEIGHT = 21;
    private static final int ZOOM_BAR_SIZE = 3;
    /** Scroll bars are easier to grab than they look. */
    private static final int ZOOM_BAR_GRAB = 6;
    /** How long the view has to stay still before the sharp part is built. */
    private static final long SHARP_DELAY_MS = 150;
    /** The sharp part is cut this much larger than what shows on each side (a fraction of what shows), so small moves reuse it. */
    private static final double SHARP_MARGIN = 0.25;
    /** Screen pixels per picture pixel from which the sharp part is drawn with hard pixel edges. */
    private static final double NEAREST_FROM = 2.0;

    private static final String MODRINTH_SHADERS = "https://modrinth.com/shaders";
    private static final String CURSEFORGE_SHADERS = "https://www.curseforge.com/minecraft/shaders";

    private static int nextTextureId;
    /** A popup that opened the settings screen and expects to come back. */
    @Nullable
    private static ScreenshotPopupScreen parked;

    private final Capture capture;
    @Nullable
    private final Screen parent;
    private final Map<CaptureStage, Preview> previews = new EnumMap<>(CaptureStage.class);
    @Nullable
    private int[] worldPreviewPixels;
    @Nullable
    private int[] finalPreviewPixels;
    @Nullable
    private ComposedPreview composedPreview;
    private boolean composedPreviewDirty = true;
    /** False until the composed texture holds a picture worth showing. Until then the plain preview stays up. */
    private boolean composedReady;
    /** Each preview filter job takes a number, and a result is only used if no newer job has started. */
    private volatile int previewGeneration;

    private boolean leaseHeld = true;
    private boolean openingSettings;
    private CaptureStage view;
    private final Set<String> hiddenGroups = new LinkedHashSet<>();
    private boolean groupsInitialized;
    private double groupScroll;
    private Overlay overlay = Overlay.NONE;
    private double listScroll;
    /** Iris's packs for the retake list, null while they are being listed. */
    @Nullable
    private List<String> shaderPacks;
    /** What Iris has chosen, read when the retake list opens. */
    @Nullable
    private ShaderSetup shaderSetup;
    private int shaderListGeneration;
    private FilterSettings filter = FilterSettings.NONE;
    @Nullable
    private CropRect crop;

    private boolean cropMode;
    @Nullable
    private CropRect draftCrop;
    private DragMode dragMode = DragMode.NONE;
    @Nullable
    private Handle activeHandle;
    @Nullable
    private CropRect dragStartCrop;
    private double dragStartMouseX;
    private double dragStartMouseY;
    private int dragAnchorX;
    private int dragAnchorY;

    private BoxMode boxMode = BoxMode.NONE;
    private final List<CropRect> eraseBoxes = new ArrayList<>();
    @Nullable
    private List<CropRect> boxesAtEntry;
    /** What Highlight mode added, boxes and picks together, oldest first. Undo takes the last one. */
    private final List<Added> addedOrder = new ArrayList<>();
    @Nullable
    private FilterSettings filterAtEntry;
    @Nullable
    private List<Added> addedAtEntry;
    private boolean wandMode;
    private boolean pickRunning;
    /** Each pick takes a number, and a result is only used if no newer number was handed out since (Esc, closing). */
    private int pickGeneration;
    @Nullable
    private CropRect draftBox;

    private final ZoomView zoom = new ZoomView();
    /** What the zoom was set on: null for the whole picture, otherwise the crop shown. A change goes back to fit. */
    @Nullable
    private CropRect zoomShape;
    /** The mouse button moving the picture, or -1. */
    private int panButton = -1;
    private ScrollBar barDrag = ScrollBar.NONE;

    /** The full-size working picture the sharp part is cut from, kept until what it was built from changes. */
    @Nullable
    private SharpPicture sharpPicture;
    @Nullable
    private SharpTile sharpTile;
    private boolean sharpRunning;
    /** Whether the sharp part's texture is set to hard pixel edges now. */
    private boolean sharpNearest;
    /** Bumped when the screen lets go of its previews, so a late result is dropped. */
    private int sharpGeneration;
    /** The picture part that showed last frame, and since when, to tell when the view has stopped moving. */
    @Nullable
    private CropRect sharpLastWanted;
    private long sharpStillSinceMs;

    @Nullable
    private Button filterButton;
    @Nullable
    private Button moreButton;
    @Nullable
    private Button copyButton;
    @Nullable
    private Button saveCopyButton;
    @Nullable
    private Button addToGalleryButton;
    @Nullable
    private Button applyCropButton;
    @Nullable
    private Button undoEraseButton;
    @Nullable
    private Button clearEraseButton;
    @Nullable
    private Button lessButton;
    @Nullable
    private Button moreToleranceButton;
    private int toolRowCount = 1;
    private int toolRowRightEdge;
    private int toolStatusY;

    @Nullable
    private Component overlayMessage;
    private long overlayUntilMs;

    private enum DragMode {
        NONE,
        DRAW,
        MOVE,
        RESIZE
    }

    /** Only one of these is open at a time. */
    private enum Overlay {
        NONE,
        FILTER,
        MORE,
        SHADERS,
        HUD
    }

    /** Erase and Highlight both work by dragging boxes on the whole picture. */
    private enum BoxMode {
        NONE,
        ERASE,
        HIGHLIGHT
    }

    private enum ScrollBar {
        NONE,
        ACROSS,
        DOWN
    }

    /** {@code mobName} is set for a whole entity pick. */
    private record PickResult(@Nullable PickedArea area, boolean large, @Nullable String mobName) {
    }

    private enum RowMark {
        NONE,
        PICKED,
        TICK_OFF,
        TICK_ON
    }

    private record ListRow(Component label, RowMark mark, boolean enabled, Runnable action) {
    }

    private record Preview(ResourceLocation id, int width, int height, int sourceWidth, int sourceHeight) {
    }

    private record ComposedPreview(Preview preview, DynamicTexture texture, NativeImage image) {
    }

    /** What the working picture is built from. A sharp part made from another key is never shown. */
    private record SharpKey(CaptureStage view, Set<String> hiddenGroups, List<CropRect> eraseBoxes, FilterSettings filter) {
    }

    private record SharpPicture(SharpKey key, int[] pixels, int width, int height) {
    }

    /** A part of the full-size picture at one texel per picture pixel. {@code region} is in picture pixels. */
    private record SharpTile(SharpKey key, CropRect region, ResourceLocation id, DynamicTexture texture) {
    }

    /** Takes over a lease the caller has already acquired. */
    public ScreenshotPopupScreen(Capture capture, @Nullable Screen parent) {
        super(Component.translatable("screenshotutilities.popup.title"));
        this.capture = capture;
        this.parent = parent;
        CaptureStage saved = capture.savedStage();
        this.view = saved != null && capture.has(saved) ? saved : CaptureStage.FINAL;
        this.hiddenGroups.addAll(capture.initialHiddenGroups());
        syncGroupsFromCapture();
    }

    @Nullable
    public Screen parent() {
        return parent;
    }

    /** Lets go of a parked popup once the child screen is replaced by anything other than the way back. */
    public static void onScreenOpening(@Nullable Screen next) {
        if (parked != null && next != parked
                && !(next instanceof ConfigurationScreen)
                && !(next instanceof ConfigurationScreen.ConfigurationSectionScreen)
                && !(next instanceof GalleryScreen)
                && !(next instanceof ShaderRetakeScreen)
                && !(next instanceof ConfirmScreen)) {
            releaseParked();
        }
    }

    public static void releaseParked() {
        if (parked != null) {
            parked.releaseLease();
            parked = null;
        }
    }

    /** The edits as they are now, in full-size picture pixels, for a retake of this shot. */
    public CarriedEdits carriedEdits() {
        return new CarriedEdits(view, hiddenGroups, eraseBoxes, crop, filter, addedOrder);
    }

    /** Width and height of the full-size picture, or null if the shot is gone. */
    @Nullable
    public static int[] pictureSize(Capture shot) {
        try {
            NativeImage image = shot.get(CaptureStage.FINAL);
            return new int[] {image.getWidth(), image.getHeight()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Starts this popup with edits carried from another shot, already mapped to this picture's size. Call before the screen
     * opens. Hidden groups this shot doesn't have are dropped once its layers are ready.
     */
    public void carryEdits(CarriedEdits edits) {
        view = capture.has(edits.view()) ? edits.view() : CaptureStage.FINAL;
        hiddenGroups.clear();
        hiddenGroups.addAll(edits.hiddenGroups());
        eraseBoxes.clear();
        if (capture.has(CaptureStage.WORLD)) {
            eraseBoxes.addAll(edits.eraseBoxes());
        }
        crop = edits.crop();
        filter = edits.filter();
        addedOrder.clear();
        addedOrder.addAll(edits.addedOrder());
        groupsInitialized = false;
        syncGroupsFromCapture();
        composedPreviewDirty = true;
    }

    public void showOverlayMessage(Component message) {
        this.overlayMessage = message;
        this.overlayUntilMs = Util.getMillis() + OVERLAY_DURATION_MS;
    }

    public void onLayersReady(Capture readyCapture) {
        if (readyCapture != this.capture || !leaseHeld || capture.isClosed()) {
            return;
        }
        syncGroupsFromCapture();
        composedPreviewDirty = true;
        rebuildWidgets();
    }

    private void syncGroupsFromCapture() {
        if (groupsInitialized || capture.isLayersPending()) {
            return;
        }
        List<String> available = capture.groups();
        groupsInitialized = true;
        if (available.isEmpty()) {
            hiddenGroups.clear();
            return;
        }
        if (view == CaptureStage.WORLD) {
            hiddenGroups.clear();
            hiddenGroups.addAll(available);
        } else {
            hiddenGroups.retainAll(available);
            if (hiddenGroups.size() == available.size() && capture.has(CaptureStage.WORLD)) {
                view = CaptureStage.WORLD;
            }
        }
        composedPreviewDirty = true;
    }

    private boolean hasGroupPanel() {
        return capture.has(CaptureStage.WORLD) && (capture.isLayersPending() || !capture.groups().isEmpty());
    }

    private boolean isSidePanelDocked() {
        return hasGroupPanel() && width >= SIDE_PANEL_MIN_SCREEN_WIDTH;
    }

    private boolean canHideHud() {
        return previews.containsKey(CaptureStage.WORLD) && previews.containsKey(CaptureStage.FINAL);
    }

    private boolean isListOpen() {
        return overlay == Overlay.FILTER || overlay == Overlay.MORE || overlay == Overlay.SHADERS;
    }

    @Override
    protected void init() {
        if (parked == this) {
            parked = null;
        }
        openingSettings = false;
        if (!leaseHeld || capture.isClosed()) {
            minecraft.tell(this::onClose);
            return;
        }
        if (previews.isEmpty()) {
            buildPreviews();
        }
        syncGroupsFromCapture();
        if (overlay == Overlay.HUD && isSidePanelDocked()) {
            overlay = Overlay.NONE;
        }

        filterButton = null;
        moreButton = null;
        applyCropButton = null;
        undoEraseButton = null;
        clearEraseButton = null;
        lessButton = null;
        moreToleranceButton = null;

        List<AbstractWidget> tools = new ArrayList<>();
        if (cropMode) {
            overlay = Overlay.NONE;
            addHideHudCheckbox(tools);
            applyCropButton = createToolButton(Component.translatable("screenshotutilities.popup.apply_crop"), b -> applyCrop());
            tools.add(applyCropButton);
            tools.add(createToolButton(Component.translatable("screenshotutilities.popup.cancel_crop"), b -> cancelCrop()));
        } else if (boxMode != BoxMode.NONE) {
            overlay = Overlay.NONE;
            addHideHudCheckbox(tools);
            if (boxMode == BoxMode.HIGHLIGHT) {
                tools.add(createToolButton(
                        Component.translatable("screenshotutilities.popup.wand", Component.translatable(
                                wandMode ? "screenshotutilities.popup.wand_on" : "screenshotutilities.popup.wand_off")),
                        b -> toggleWand()));
                lessButton = createToolButton(
                        Component.translatable("screenshotutilities.popup.wand_less"),
                        b -> adjustTolerance(-MagicWand.TOLERANCE_STEP));
                tools.add(lessButton);
                moreToleranceButton = createToolButton(
                        Component.translatable("screenshotutilities.popup.wand_more"),
                        b -> adjustTolerance(MagicWand.TOLERANCE_STEP));
                tools.add(moreToleranceButton);
            }
            undoEraseButton = createToolButton(Component.translatable("screenshotutilities.popup.undo_erase"), b -> undoBox());
            tools.add(undoEraseButton);
            clearEraseButton = createToolButton(Component.translatable("screenshotutilities.popup.clear_erase"), b -> clearBoxes());
            tools.add(clearEraseButton);
            tools.add(createToolButton(Component.translatable("screenshotutilities.popup.done_erase"), b -> finishBoxMode()));
        } else {
            tools.add(createToolButton(Component.translatable("screenshotutilities.popup.crop"), b -> enterCropMode()));
            if (crop != null) {
                tools.add(createToolButton(Component.translatable("screenshotutilities.popup.reset_crop"), b -> resetCrop()));
            }
            Button eraseButton = createToolButton(
                    Component.translatable("screenshotutilities.popup.erase"), b -> enterBoxMode(BoxMode.ERASE));
            if (!capture.has(CaptureStage.WORLD)) {
                eraseButton.active = false;
                eraseButton.setTooltip(Tooltip.create(Component.translatable("screenshotutilities.popup.erase_unavailable")));
            }
            tools.add(eraseButton);
            tools.add(createToolButton(
                    Component.translatable("screenshotutilities.popup.highlight"), b -> enterBoxMode(BoxMode.HIGHLIGHT)));

            filterButton = createToolButton(filterButtonLabel(), b -> toggleOverlay(Overlay.FILTER));
            tools.add(filterButton);

            addHideHudCheckbox(tools);
            if (hasGroupPanel() && !isSidePanelDocked()) {
                tools.add(createToolButton(
                        Component.translatable("screenshotutilities.popup.hud_button"),
                        b -> toggleOverlay(Overlay.HUD)));
            }

            moreButton = createToolButton(
                    Component.translatable("screenshotutilities.popup.more"), b -> toggleOverlay(Overlay.MORE));
            tools.add(moreButton);
        }
        layoutToolWidgets(tools);
        tools.forEach(this::addRenderableWidget);

        List<Button> actions = new ArrayList<>();
        copyButton = Button.builder(Component.translatable("screenshotutilities.popup.copy"), b -> copyWorkingPicture()).build();
        if (!ScreenshotClipboard.isSupported()) {
            copyButton.setTooltip(Tooltip.create(Component.translatable("screenshotutilities.popup.copy_unsupported")));
        }
        actions.add(copyButton);
        saveCopyButton = Button.builder(Component.translatable("screenshotutilities.popup.save_copy"), b -> saveCopy()).build();
        actions.add(saveCopyButton);
        addToGalleryButton = Button.builder(
                Component.translatable("screenshotutilities.popup.add_to_gallery"), b -> addToGallery()).build();
        actions.add(addToGalleryButton);
        actions.add(Button.builder(Component.translatable("screenshotutilities.popup.done"), b -> onClose()).build());
        layoutRow(actions, height - ROW_HEIGHT - 6);
        actions.forEach(this::addRenderableWidget);
        updateButtons();
    }

    /** With a HUD panel, Hide HUD lives at the top of it. Without one it stays here, so it's never lost. */
    private void addHideHudCheckbox(List<AbstractWidget> tools) {
        if (canHideHud() && !hasGroupPanel()) {
            tools.add(Checkbox.builder(Component.translatable("screenshotutilities.popup.hide_hud"), font)
                    .pos(MARGIN, 0)
                    .selected(view == CaptureStage.WORLD)
                    .onValueChange((box, selected) -> onHideHudToggled(selected))
                    .build());
        }
    }

    private Component filterButtonLabel() {
        List<KeepColour> colours = List.copyOf(filter.colours());
        Component value;
        if (filter.blackAndWhite()) {
            value = Component.translatable("screenshotutilities.filter.black_and_white");
        } else if (colours.isEmpty()) {
            value = Component.translatable("screenshotutilities.filter.none");
        } else if (colours.size() == 1) {
            value = Component.translatable(colours.get(0).translationKey());
        } else if (colours.size() == 2) {
            value = Component.translatable(
                    "screenshotutilities.popup.filter_two",
                    Component.translatable(colours.get(0).translationKey()),
                    Component.translatable(colours.get(1).translationKey()));
        } else {
            value = Component.translatable("screenshotutilities.popup.filter_many", colours.size());
        }
        return Component.translatable("screenshotutilities.popup.filter", value);
    }

    private void toggleOverlay(Overlay which) {
        overlay = overlay == which ? Overlay.NONE : which;
        listScroll = 0;
    }

    private List<ListRow> openListRows() {
        return switch (overlay) {
            case FILTER -> filterRows();
            case MORE -> moreRows();
            case SHADERS -> shaderRows();
            default -> List.of();
        };
    }

    private List<ListRow> filterRows() {
        List<ListRow> rows = new ArrayList<>();
        rows.add(new ListRow(
                Component.translatable("screenshotutilities.filter.none"),
                filter.isNoneChosen() ? RowMark.PICKED : RowMark.NONE,
                true,
                () -> setFilter(filter.pickNone())));
        rows.add(new ListRow(
                Component.translatable("screenshotutilities.filter.black_and_white"),
                filter.blackAndWhite() ? RowMark.PICKED : RowMark.NONE,
                true,
                () -> setFilter(filter.pickBlackAndWhite())));
        for (KeepColour colour : KeepColour.values()) {
            rows.add(new ListRow(
                    Component.translatable(colour.translationKey()),
                    filter.colours().contains(colour) ? RowMark.TICK_ON : RowMark.TICK_OFF,
                    true,
                    () -> setFilter(filter.toggleColour(colour))));
        }
        rows.add(new ListRow(
                Component.translatable(
                        "screenshotutilities.popup.rest", Component.translatable(filter.rest().translationKey())),
                RowMark.NONE,
                filter.keepsAnything(),
                () -> setFilter(filter.withRest(filter.rest().other()))));
        return rows;
    }

    private List<ListRow> moreRows() {
        List<ListRow> rows = new ArrayList<>();
        if (canRetake()) {
            rows.add(moreRow("screenshotutilities.popup.retake", this::openShaderList));
        }
        rows.add(moreRow("screenshotutilities.popup.gallery", this::openGallery));
        rows.add(moreRow("screenshotutilities.popup.settings", this::openSettings));
        rows.add(moreRow("screenshotutilities.popup.dont_ask", this::dontAskAgain));
        return rows;
    }

    private boolean canRetake() {
        return minecraft.level != null && IrisShaders.available();
    }

    /** Only unpublished single player pauses, so only there the scene holds still while the pack switches. */
    private boolean gamePauses() {
        return minecraft.hasSingleplayerServer()
                && minecraft.getSingleplayerServer() != null
                && !minecraft.getSingleplayerServer().isPublished();
    }

    private void openShaderList() {
        try {
            shaderSetup = IrisShaders.current();
        } catch (Throwable t) {
            LOGGER.warn("Couldn't read Iris's shader choice", t);
            showOverlayMessage(Component.translatable("screenshotutilities.message.retake_failed"));
            return;
        }
        overlay = Overlay.SHADERS;
        listScroll = 0;
        shaderPacks = null;
        int generation = ++shaderListGeneration;
        Util.ioPool().execute(() -> {
            List<String> packs;
            try {
                packs = List.copyOf(IrisShaders.listPacks());
            } catch (Throwable t) {
                LOGGER.warn("Couldn't list shader packs", t);
                packs = List.of();
            }
            List<String> found = packs;
            minecraft.execute(() -> {
                if (generation == shaderListGeneration) {
                    shaderPacks = found;
                }
            });
        });
    }

    /** No shaders, each pack, then Get more shaders. What Iris draws with now is marked and can't be picked. */
    private List<ListRow> shaderRows() {
        ShaderSetup now = shaderSetup;
        if (now == null) {
            return List.of();
        }
        List<ListRow> rows = new ArrayList<>();
        boolean noneNow = now.activePack() == null;
        rows.add(new ListRow(
                Component.translatable("screenshotutilities.popup.no_shaders"),
                noneNow ? RowMark.PICKED : RowMark.NONE,
                !noneNow,
                () -> startRetake(ShaderSetup.noShaders(now.packName()))));
        List<String> packs = shaderPacks;
        if (packs == null) {
            rows.add(new ListRow(
                    Component.translatable("screenshotutilities.popup.finding_packs"), RowMark.NONE, false, () -> { }));
        } else {
            for (String pack : packs) {
                boolean current = pack.equals(now.activePack());
                rows.add(new ListRow(
                        Component.literal(pack),
                        current ? RowMark.PICKED : RowMark.NONE,
                        !current,
                        () -> startRetake(new ShaderSetup(pack, true))));
            }
        }
        rows.add(new ListRow(Component.translatable("screenshotutilities.popup.get_shaders_modrinth"), RowMark.NONE, true,
                () -> openShaderPage(MODRINTH_SHADERS)));
        rows.add(new ListRow(Component.translatable("screenshotutilities.popup.get_shaders_curseforge"), RowMark.NONE, true,
                () -> openShaderPage(CURSEFORGE_SHADERS)));
        return rows;
    }

    private void startRetake(ShaderSetup target) {
        ShaderSetup now = shaderSetup;
        overlay = Overlay.NONE;
        if (now == null || !canRetake()) {
            return;
        }
        openingSettings = true;
        parked = this;
        if (gamePauses()) {
            minecraft.setScreen(new ShaderRetakeScreen(this, parent, capture, now, target));
            return;
        }
        // On a server or an opened LAN world the game keeps running while the pack loads.
        minecraft.setScreen(new ConfirmScreen(
                go -> minecraft.setScreen(go && canRetake() ? new ShaderRetakeScreen(this, parent, capture, now, target) : this),
                Component.translatable("screenshotutilities.retake.confirm_title"),
                Component.translatable("screenshotutilities.retake.confirm_text"),
                Component.translatable("screenshotutilities.retake.continue"),
                Component.translatable("gui.cancel")));
    }

    private void openShaderPage(String url) {
        overlay = Overlay.NONE;
        openingSettings = true;
        parked = this;
        ConfirmLinkScreen.confirmLinkNow(this, url);
    }

    private ListRow moreRow(String key, Runnable action) {
        return new ListRow(Component.translatable(key), RowMark.NONE, true, () -> {
            overlay = Overlay.NONE;
            action.run();
        });
    }

    private void setFilter(FilterSettings next) {
        if (filter.equals(next)) {
            return;
        }
        filter = next;
        composedPreviewDirty = true;
        rebuildWidgets();
    }

    private void onHideHudToggled(boolean selected) {
        List<String> available = capture.groups();
        if (selected) {
            view = CaptureStage.WORLD;
            hiddenGroups.clear();
            hiddenGroups.addAll(available);
        } else {
            view = CaptureStage.FINAL;
            hiddenGroups.clear();
        }
        composedPreviewDirty = true;
        updateButtons();
    }

    private void toggleGroup(String groupId) {
        List<String> available = capture.groups();
        if (available.isEmpty()) {
            return;
        }
        if (view == CaptureStage.WORLD) {
            // All groups were hidden while Hide HUD was ticked; ticking this group shows only this group.
            hiddenGroups.clear();
            hiddenGroups.addAll(available);
            hiddenGroups.remove(groupId);
            view = CaptureStage.FINAL;
        } else {
            if (!hiddenGroups.remove(groupId)) {
                hiddenGroups.add(groupId);
            }
            if (hiddenGroups.containsAll(available) && capture.has(CaptureStage.WORLD)) {
                view = CaptureStage.WORLD;
            } else {
                view = CaptureStage.FINAL;
            }
        }
        composedPreviewDirty = true;
        updateButtons();
    }

    private Button createToolButton(Component label, Button.OnPress onPress) {
        int w = Math.max(40, font.width(label) + 12);
        return Button.builder(label, onPress).bounds(0, 0, w, ROW_HEIGHT).build();
    }

    private void layoutToolWidgets(List<AbstractWidget> tools) {
        if (tools.isEmpty()) {
            toolRowCount = 1;
            Rect area = previewArea();
            toolStatusY = area.bottom() + GAP + (ROW_HEIGHT - font.lineHeight) / 2;
            toolRowRightEdge = area.left();
            return;
        }
        int available = Math.max(80, width - 2 * MARGIN);
        int n = tools.size();
        int[] standardWidths = new int[n];
        int[] compactWidths = new int[n];
        int totalStandard = 0;
        int totalCompact = 0;
        for (int i = 0; i < n; i++) {
            AbstractWidget w = tools.get(i);
            if (w instanceof Checkbox) {
                int cbWidth = 20 + font.width(w.getMessage()) + 4;
                standardWidths[i] = cbWidth;
                compactWidths[i] = cbWidth;
            } else {
                int textW = font.width(w.getMessage());
                standardWidths[i] = Math.max(48, textW + 14);
                compactWidths[i] = Math.max(32, textW + 8);
            }
            totalStandard += standardWidths[i];
            totalCompact += compactWidths[i];
            if (i + 1 < n) {
                totalStandard += (w instanceof Checkbox) ? GAP * 2 : GAP;
                totalCompact += 3;
            }
        }

        if (totalStandard <= available) {
            toolRowCount = 1;
            Rect area = previewArea();
            int y = area.bottom() + GAP;
            int x = MARGIN;
            for (int i = 0; i < n; i++) {
                AbstractWidget w = tools.get(i);
                w.setRectangle(standardWidths[i], ROW_HEIGHT, x, y);
                int gap = (w instanceof Checkbox) ? GAP * 2 : GAP;
                x += standardWidths[i] + gap;
            }
            toolRowRightEdge = x;
            toolStatusY = y + (ROW_HEIGHT - font.lineHeight) / 2;
            return;
        }

        if (totalCompact <= available) {
            toolRowCount = 1;
            Rect area = previewArea();
            int y = area.bottom() + GAP;
            int extra = available - totalCompact;
            int buttonCount = 0;
            for (AbstractWidget w : tools) {
                if (!(w instanceof Checkbox)) {
                    buttonCount++;
                }
            }
            int x = MARGIN;
            for (int i = 0; i < n; i++) {
                AbstractWidget w = tools.get(i);
                int widthForWidget = compactWidths[i];
                if (!(w instanceof Checkbox) && buttonCount > 0) {
                    int add = Math.min(standardWidths[i] - compactWidths[i], extra / buttonCount);
                    widthForWidget += add;
                    extra -= add;
                    buttonCount--;
                }
                w.setRectangle(widthForWidget, ROW_HEIGHT, x, y);
                x += widthForWidget + 3;
            }
            toolRowRightEdge = x;
            toolStatusY = y + (ROW_HEIGHT - font.lineHeight) / 2;
            return;
        }

        // Wrap onto more rows when the buttons can't fit on one line without clipping labels. The rows are counted first,
        // because the preview area and so the first row's position depend on how many there are.
        int rightLimit = width - MARGIN;
        int rows = 1;
        int x = MARGIN;
        for (int i = 0; i < n; i++) {
            int wWidth = Math.min(compactWidths[i], available);
            if (x > MARGIN && x + wWidth > rightLimit) {
                rows++;
                x = MARGIN;
            }
            x += wWidth + 3;
        }
        toolRowCount = rows;
        int y = previewArea().bottom() + GAP;
        x = MARGIN;
        for (int i = 0; i < n; i++) {
            AbstractWidget w = tools.get(i);
            int wWidth = Math.min(compactWidths[i], available);
            if (x > MARGIN && x + wWidth > rightLimit) {
                x = MARGIN;
                y += ROW_HEIGHT + GAP;
            }
            w.setRectangle(wWidth, ROW_HEIGHT, x, y);
            x += wWidth + 3;
        }
        toolRowRightEdge = x;
        toolStatusY = y + (ROW_HEIGHT - font.lineHeight) / 2;
    }

    private void layoutRow(List<Button> buttons, int y) {
        if (buttons.isEmpty()) {
            return;
        }
        int n = buttons.size();
        int[] widths = new int[n];
        int[] compact = new int[n];
        int gaps = GAP * (n - 1);
        int total = gaps;
        int totalCompact = gaps;
        for (int i = 0; i < n; i++) {
            int textW = font.width(buttons.get(i).getMessage());
            widths[i] = Math.max(54, textW + 16);
            compact[i] = Math.max(34, textW + 8);
            total += widths[i];
            totalCompact += compact[i];
        }
        int available = Math.max(80, width - 2 * MARGIN);
        if (total > available) {
            if (totalCompact <= available) {
                int extra = available - totalCompact;
                for (int i = 0; i < n; i++) {
                    int add = extra / (n - i);
                    widths[i] = compact[i] + add;
                    extra -= add;
                }
            } else {
                int usable = Math.max(n * 20, available - gaps);
                int sumText = Math.max(1, totalCompact - gaps);
                int assigned = 0;
                for (int i = 0; i < n; i++) {
                    widths[i] = (i == n - 1)
                            ? Math.max(20, usable - assigned)
                            : Math.max(20, usable * compact[i] / sumText);
                    assigned += widths[i];
                }
            }
            total = available;
        }
        int x = Math.max(MARGIN, (width - total) / 2);
        for (int i = 0; i < n; i++) {
            Button button = buttons.get(i);
            button.setRectangle(widths[i], ROW_HEIGHT, x, y);
            x += widths[i] + GAP;
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

        ZoomView.Box box() {
            return new ZoomView.Box(left, top, right, bottom);
        }

        static Rect of(ZoomView.Box box) {
            return new Rect(box.left(), box.top(), box.right(), box.bottom());
        }
    }

    private Rect previewArea() {
        int right = width - MARGIN - (isSidePanelDocked() ? SIDE_PANEL_WIDTH + MARGIN : 0);
        int rowsBelow = 1 + toolRowCount;
        int bottom = height - rowsBelow * ROW_HEIGHT - 6 - (rowsBelow) * GAP;
        return new Rect(MARGIN, TOP, right, Math.max(TOP + 20, bottom));
    }

    /** Sits over the preview, just above the button that opened it. */
    @Nullable
    private Rect listRect(List<ListRow> rows) {
        Button anchor = overlay == Overlay.FILTER ? filterButton : moreButton;
        if (rows.isEmpty() || anchor == null) {
            return null;
        }
        int widest = 0;
        for (ListRow row : rows) {
            widest = Math.max(widest, font.width(row.label()));
        }
        Rect area = previewArea();
        int menuW = Math.min(Math.max(LIST_MIN_WIDTH, widest + LIST_TEXT_INDENT + 10), area.width() - 8);
        int naturalH = rows.size() * LIST_ROW_HEIGHT + 4;
        int menuH = Math.min(naturalH, Math.max(32, area.height() - 8));

        int center = anchor.getX() + anchor.getWidth() / 2;
        int left = Math.clamp(center - menuW / 2, area.left() + 4, area.right() - menuW - 4);
        int bottom = area.bottom() - 2;
        int top = Math.max(area.top() + 4, bottom - menuH);
        return new Rect(left, top, left + menuW, bottom);
    }

    @Nullable
    private Rect groupPanelRect() {
        if (!hasGroupPanel()) {
            return null;
        }
        Rect area = previewArea();
        if (isSidePanelDocked()) {
            int left = width - MARGIN - SIDE_PANEL_WIDTH;
            return new Rect(left, area.top(), left + SIDE_PANEL_WIDTH, area.bottom());
        }
        if (overlay == Overlay.HUD && !cropMode && boxMode == BoxMode.NONE) {
            int panelW = Math.min(SIDE_PANEL_WIDTH, Math.max(80, area.width() - 8));
            int right = area.right() - 4;
            int left = right - panelW;
            return new Rect(left, area.top() + 4, right, area.bottom() - 4);
        }
        return null;
    }

    private Rect hideHudRowRect(Rect panel) {
        int top = panel.top() + GROUP_HEADER_HEIGHT;
        return new Rect(panel.left() + 4, top, panel.right() - 4, top + GROUP_ROW_HEIGHT);
    }

    private Rect groupListScrollArea(Rect panel) {
        return new Rect(
                panel.left() + 4,
                panel.top() + GROUP_HEADER_HEIGHT + (canHideHud() ? HIDE_HUD_ROW_HEIGHT : 0),
                panel.right() - 4,
                panel.bottom() - GROUP_FOOTER_HEIGHT);
    }

    private Rect alwaysHideButtonRect(Rect panel) {
        return new Rect(
                panel.left() + 4,
                panel.bottom() - GROUP_FOOTER_HEIGHT + 3,
                panel.right() - 4,
                panel.bottom() - 3);
    }

    private boolean isAlwaysHideButtonActive() {
        return !capture.isLayersPending()
                && !capture.groups().isEmpty()
                && !alwaysHideAfterSave().equals(ScreenshotConfig.alwaysHideSet());
    }

    /** Keeps listed groups that weren't on screen for this shot, such as a boss bar. */
    private Set<String> alwaysHideAfterSave() {
        Set<String> merged = new LinkedHashSet<>(ScreenshotConfig.alwaysHideSet());
        capture.groups().forEach(merged::remove);
        merged.addAll(hiddenGroups);
        return merged;
    }

    private void saveAlwaysHide() {
        if (!isAlwaysHideButtonActive()) {
            return;
        }
        ScreenshotConfig.setAlwaysHide(alwaysHideAfterSave());
        showOverlayMessage(Component.translatable("screenshotutilities.message.always_hide_saved"));
    }

    @Nullable
    private Preview currentPreview() {
        boolean needsCompose = shownFilter().isActive()
                || (view == CaptureStage.FINAL && (!hiddenGroups.isEmpty() || !eraseBoxes.isEmpty()));
        if (needsCompose) {
            ensureComposedPreview();
            if (composedPreview != null && composedReady) {
                return composedPreview.preview();
            }
        } else if (composedReady) {
            composedReady = false;
            composedPreviewDirty = true;
            previewGeneration++;
        }
        Preview preview = previews.get(view);
        return preview != null ? preview : previews.get(CaptureStage.FINAL);
    }

    private void ensureComposedPreview() {
        Preview base = previews.get(view);
        if (base == null) {
            base = previews.get(CaptureStage.FINAL);
        }
        if (base == null) {
            return;
        }
        int w = base.width();
        int h = base.height();
        if (composedPreview == null || composedPreview.preview().width() != w || composedPreview.preview().height() != h) {
            if (composedPreview != null) {
                minecraft.getTextureManager().release(composedPreview.preview().id());
            }
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            DynamicTexture tex = new DynamicTexture(img);
            tex.setFilter(true, false);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    ScreenshotUtilities.MOD_ID, "popup_preview/" + nextTextureId++);
            minecraft.getTextureManager().register(id, tex);
            composedPreview = new ComposedPreview(new Preview(id, w, h, base.sourceWidth(), base.sourceHeight()), tex, img);
            composedPreviewDirty = true;
            composedReady = false;
        }
        if (!composedPreviewDirty) {
            return;
        }
        int[] rebuilt;
        if (view == CaptureStage.WORLD) {
            if (worldPreviewPixels == null) {
                return;
            }
            rebuilt = worldPreviewPixels.clone();
        } else {
            List<CropRect> scaledErase = scaleEraseBoxes(eraseBoxes, base.sourceWidth(), base.sourceHeight(), w, h);
            LayerStack previewStack = capture.previewLayerStack();
            if (previewStack != null && previewStack.width() == w && previewStack.height() == h) {
                rebuilt = previewStack.rebuild(hiddenGroups, scaledErase);
            } else if (finalPreviewPixels != null && worldPreviewPixels != null) {
                rebuilt = finalPreviewPixels.clone();
                LayerStack.applyEraseBoxes(rebuilt, worldPreviewPixels, w, h, scaledErase);
            } else if (finalPreviewPixels != null) {
                rebuilt = finalPreviewPixels.clone();
            } else {
                return;
            }
        }

        composedPreviewDirty = false;
        FilterSettings shown = shownFilter();
        if (shown.isActive()) {
            filterPreviewOnWorker(rebuilt, w, h, shown, base.sourceWidth(), base.sourceHeight());
        } else {
            previewGeneration++;
            uploadComposed(rebuilt, w, h);
        }
    }

    /** Blur on a preview takes long enough to show, so the last preview stays up until this is back. */
    private void filterPreviewOnWorker(int[] pixels, int w, int h, FilterSettings settings, int sourceW, int sourceH) {
        int generation = ++previewGeneration;
        try {
            Util.ioPool().execute(() -> {
                if (generation != previewGeneration) {
                    return;
                }
                try {
                    long start = System.nanoTime();
                    // Mapping a pick's mask down walks every selected pixel, so it happens here.
                    Filters.applyInPlace(settings.scaledTo(sourceW, sourceH, w, h), pixels, w, h);
                    LOGGER.debug("Preview filter {}x{} in {} ms", w, h, (System.nanoTime() - start) / 1_000_000);
                    minecraft.execute(() -> {
                        if (generation == previewGeneration) {
                            uploadComposed(pixels, w, h);
                        }
                    });
                } catch (Throwable t) {
                    LOGGER.warn("Couldn't filter the preview", t);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warn("Couldn't start the preview filter", e);
        }
    }

    private void uploadComposed(int[] pixels, int w, int h) {
        if (composedPreview == null || composedPreview.preview().width() != w || composedPreview.preview().height() != h) {
            return;
        }
        NativeImage img = composedPreview.image();
        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                img.setPixelRGBA(x, y, pixels[rowOffset + x]);
            }
        }
        composedPreview.texture().upload();
        composedReady = true;
    }

    private static List<CropRect> scaleEraseBoxes(
            List<CropRect> boxes, int srcW, int srcH, int dstW, int dstH) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        List<CropRect> scaled = new ArrayList<>(boxes.size());
        for (CropRect box : boxes) {
            scaled.add(box.scaleTo(srcW, srcH, dstW, dstH));
        }
        return scaled;
    }

    /** The whole picture on screen, zoomed. Can be larger than the area. */
    private Rect fullPreviewRect(Rect area, Preview preview) {
        return zoomed(fitRect(area, preview.width(), preview.height()), area);
    }

    /** The applied crop on screen in the plain view, zoomed. */
    private Rect croppedPreviewRect(Rect area, CropRect activeCrop) {
        return zoomed(fitRect(area, activeCrop.width(), activeCrop.height()), area);
    }

    private static Rect fitRect(Rect area, int contentW, int contentH) {
        double scale = Math.min((double) area.width() / contentW, (double) area.height() / contentH);
        int w = Math.max(1, (int) Math.round(contentW * scale));
        int h = Math.max(1, (int) Math.round(contentH * scale));
        int x = area.left() + (area.width() - w) / 2;
        int y = area.top() + (area.height() - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    private Rect zoomed(Rect base, Rect area) {
        checkZoomShape();
        return Rect.of(zoom.rect(base.box(), area.box()));
    }

    /** The plain view with a crop applied shows only the crop. Everything else shows the whole picture. */
    private boolean showsCrop() {
        return !cropMode && boxMode == BoxMode.NONE && crop != null;
    }

    /** Goes back to fit when the picture on screen changes shape. */
    private void checkZoomShape() {
        CropRect shape = showsCrop() ? crop : null;
        if (!Objects.equals(shape, zoomShape)) {
            zoomShape = shape;
            zoom.reset();
            panButton = -1;
            barDrag = ScrollBar.NONE;
        }
    }

    /** The fit rect of whatever is on screen, which is what the zoom works from. */
    private Rect shownBaseRect(Rect area, Preview preview) {
        checkZoomShape();
        return showsCrop() ? fitRect(area, crop.width(), crop.height()) : fitRect(area, preview.width(), preview.height());
    }

    private Rect shownRect(Rect area, Preview preview) {
        return showsCrop() ? croppedPreviewRect(area, crop) : fullPreviewRect(area, preview);
    }

    /** The part of the picture that shows. Hit tests and anything placed for reading use this, not the zoomed rect. */
    private static Rect visiblePart(Rect picture, Rect area) {
        return Rect.of(picture.box().intersect(area.box()));
    }

    /** A left drag that works from mouse distance, or a scroll bar drag. The wheel and panning wait for these. */
    private boolean isLeftDragRunning() {
        return dragMode != DragMode.NONE || barDrag != ScrollBar.NONE;
    }

    public void refreshButtons() {
        updateButtons();
    }

    private void updateButtons() {
        boolean editing = cropMode || boxMode != BoxMode.NONE;
        if (copyButton != null) {
            copyButton.active = !editing && ScreenshotClipboard.isSupported();
        }
        if (saveCopyButton != null) {
            saveCopyButton.active = !editing && !capture.isSaved(view, hiddenGroups, eraseBoxes, crop, shownFilter());
        }
        if (addToGalleryButton != null) {
            addToGalleryButton.active = !editing && !capture.isInGallery(view, hiddenGroups, eraseBoxes, crop, shownFilter());
        }
        if (applyCropButton != null) {
            applyCropButton.active = draftCrop != null;
        }
        boolean canUndo = !pickRunning && hasAnyBoxes();
        if (undoEraseButton != null) {
            undoEraseButton.active = canUndo;
        }
        if (clearEraseButton != null) {
            clearEraseButton.active = canUndo;
        }
        PickedArea last = lastPickIfLast();
        // A whole mob has no tolerance to change.
        boolean canRetune = !pickRunning && last != null && !last.isMob();
        if (lessButton != null) {
            lessButton.active = canRetune && last.tolerance() > MagicWand.MIN_TOLERANCE;
        }
        if (moreToleranceButton != null) {
            moreToleranceButton.active = canRetune && last.tolerance() < MagicWand.MAX_TOLERANCE;
        }
    }

    private void enterCropMode() {
        cropMode = true;
        boxMode = BoxMode.NONE;
        overlay = Overlay.NONE;
        draftCrop = crop;
        draftBox = null;
        dragMode = DragMode.NONE;
        activeHandle = null;
        dragStartCrop = null;
        rebuildWidgets();
    }

    private void applyCrop() {
        if (draftCrop == null) {
            return;
        }
        Preview preview = currentPreview();
        if (preview != null && draftCrop.isFullImage(preview.sourceWidth(), preview.sourceHeight())) {
            crop = null;
        } else {
            crop = draftCrop;
        }
        cropMode = false;
        draftCrop = null;
        dragMode = DragMode.NONE;
        activeHandle = null;
        dragStartCrop = null;
        rebuildWidgets();
    }

    private void cancelCrop() {
        cropMode = false;
        draftCrop = null;
        dragMode = DragMode.NONE;
        activeHandle = null;
        dragStartCrop = null;
        rebuildWidgets();
    }

    private void resetCrop() {
        crop = null;
        draftCrop = null;
        cropMode = false;
        overlay = Overlay.NONE;
        dragMode = DragMode.NONE;
        activeHandle = null;
        dragStartCrop = null;
        rebuildWidgets();
    }

    private void enterBoxMode(BoxMode mode) {
        if (mode == BoxMode.ERASE) {
            if (!capture.has(CaptureStage.WORLD)) {
                return;
            }
            if (view == CaptureStage.WORLD) {
                view = CaptureStage.FINAL;
                hiddenGroups.clear();
                composedPreviewDirty = true;
            }
        }
        boxMode = mode;
        cropMode = false;
        overlay = Overlay.NONE;
        boxesAtEntry = List.copyOf(boxes());
        filterAtEntry = filter;
        addedAtEntry = List.copyOf(addedOrder);
        wandMode = false;
        draftBox = null;
        dragMode = DragMode.NONE;
        rebuildWidgets();
    }

    /** The boxes the current mode edits. */
    private List<CropRect> boxes() {
        return boxMode == BoxMode.HIGHLIGHT ? filter.keepAreas() : eraseBoxes;
    }

    private void setBoxes(List<CropRect> boxes) {
        if (boxMode == BoxMode.HIGHLIGHT) {
            filter = filter.withKeepAreas(boxes);
        } else {
            List<CropRect> copy = List.copyOf(boxes);
            eraseBoxes.clear();
            eraseBoxes.addAll(copy);
        }
        composedPreviewDirty = true;
        updateButtons();
    }

    /** Anything Undo and Clear could take away in the current mode. */
    private boolean hasAnyBoxes() {
        return boxMode == BoxMode.HIGHLIGHT ? !addedOrder.isEmpty() : !eraseBoxes.isEmpty();
    }

    /** Adds a box the player drew. In Highlight mode it goes into the shared history with the picks. */
    private void addBox(CropRect box) {
        if (boxMode == BoxMode.HIGHLIGHT) {
            List<CropRect> next = new ArrayList<>(filter.keepAreas());
            next.add(box);
            filter = filter.withKeepAreas(next);
            addedOrder.add(Added.BOX);
            composedPreviewDirty = true;
            updateButtons();
        } else {
            List<CropRect> next = new ArrayList<>(eraseBoxes);
            next.add(box);
            setBoxes(next);
        }
    }

    private void undoBox() {
        if (pickRunning) {
            return;
        }
        if (boxMode != BoxMode.HIGHLIGHT) {
            List<CropRect> boxes = boxes();
            if (!boxes.isEmpty()) {
                setBoxes(boxes.subList(0, boxes.size() - 1));
            }
            return;
        }
        if (addedOrder.isEmpty()) {
            return;
        }
        Added last = addedOrder.remove(addedOrder.size() - 1);
        if (last == Added.BOX) {
            List<CropRect> boxes = filter.keepAreas();
            filter = filter.withKeepAreas(boxes.subList(0, boxes.size() - 1));
        } else {
            List<PickedArea> picks = filter.pickedAreas();
            filter = filter.withPickedAreas(picks.subList(0, picks.size() - 1));
        }
        composedPreviewDirty = true;
        updateButtons();
    }

    private void clearBoxes() {
        if (pickRunning) {
            return;
        }
        if (boxMode == BoxMode.HIGHLIGHT) {
            if (!addedOrder.isEmpty()) {
                filter = filter.withKeepAreas(List.of()).withPickedAreas(List.of());
                addedOrder.clear();
                composedPreviewDirty = true;
                updateButtons();
            }
        } else if (!boxes().isEmpty()) {
            setBoxes(List.of());
        }
    }

    private void finishBoxMode() {
        boxMode = BoxMode.NONE;
        wandMode = false;
        draftBox = null;
        boxesAtEntry = null;
        filterAtEntry = null;
        addedAtEntry = null;
        dragMode = DragMode.NONE;
        rebuildWidgets();
    }

    private void cancelBoxMode() {
        // A pick that is still running would land after the player said no.
        pickGeneration++;
        if (boxMode == BoxMode.HIGHLIGHT) {
            if (filterAtEntry != null && addedAtEntry != null) {
                filter = filter.withKeepAreas(filterAtEntry.keepAreas()).withPickedAreas(filterAtEntry.pickedAreas());
                addedOrder.clear();
                addedOrder.addAll(addedAtEntry);
                composedPreviewDirty = true;
            }
        } else if (boxesAtEntry != null) {
            setBoxes(boxesAtEntry);
        }
        finishBoxMode();
    }

    private void toggleWand() {
        wandMode = !wandMode;
        draftBox = null;
        dragMode = DragMode.NONE;
        rebuildWidgets();
    }

    /** The newest pick, but only when it is also the newest thing added, so Less and More know what to redo. */
    @Nullable
    private PickedArea lastPickIfLast() {
        if (boxMode != BoxMode.HIGHLIGHT || addedOrder.isEmpty() || addedOrder.get(addedOrder.size() - 1) != Added.PICK
                || filter.pickedAreas().isEmpty()) {
            return null;
        }
        return filter.pickedAreas().get(filter.pickedAreas().size() - 1);
    }

    private void adjustTolerance(int delta) {
        PickedArea last = lastPickIfLast();
        if (last == null || last.isMob() || pickRunning) {
            return;
        }
        int next = MagicWand.clampTolerance(last.tolerance() + delta);
        if (next != last.tolerance()) {
            startPick(last.seedX(), last.seedY(), next, last.source(), true, true);
        }
    }

    /**
     * Picks on a worker from the picture the pick was (or is being) made from: hidden groups and erase boxes applied, no
     * filter and no crop, at full size. A click on a mob that shows there picks the whole mob, unless {@code colourOnly}.
     * Anything else uses the colour wand. The worker holds its own {@link Capture} lease and hands back an immutable value.
     */
    private void startPick(
            int seedX,
            int seedY,
            int tolerance,
            @Nullable PickedArea.PictureSource fixedSource,
            boolean replaceLast,
            boolean colourOnly) {
        if (pickRunning) {
            return;
        }
        PickedArea.PictureSource source = fixedSource != null
                ? fixedSource
                : new PickedArea.PictureSource(view, Set.copyOf(hiddenGroups), List.copyOf(eraseBoxes));
        if (!capture.acquire()) {
            showOverlayMessage(Component.translatable("screenshotutilities.popup.wand_failed"));
            return;
        }
        pickRunning = true;
        int generation = ++pickGeneration;
        updateButtons();
        try {
            Util.ioPool().execute(() -> {
                PickResult result = new PickResult(null, false, null);
                try {
                    PickResult mob = colourOnly ? null : pickMob(source, seedX, seedY);
                    if (mob != null) {
                        result = mob;
                        return;
                    }
                    if (!source.hiddenGroups().isEmpty() && source.stage() == CaptureStage.FINAL) {
                        capture.awaitLayers(CaptureSaver.REBUILD_TIMEOUT_MS);
                    }
                    CaptureSaver.RebuiltPicture pic = CaptureSaver.buildPicture(
                            capture, source.stage(), source.hiddenGroups(), source.eraseBoxes(), null, FilterSettings.NONE);
                    int[] pixels = pic.pixels();
                    if (pixels == null) {
                        NativeImage stage = pic.directStage();
                        pixels = new CropRect(0, 0, pic.width(), pic.height()).extract(stage::getPixelRGBA);
                    }
                    long start = System.nanoTime();
                    PickedArea area = MagicWand.select(pixels, pic.width(), pic.height(), seedX, seedY, tolerance, source);
                    if (source.stage() == CaptureStage.FINAL) {
                        area = area.onHudGroup(hudGroupAt(source, seedX, seedY, pic.width(), pic.height()));
                    }
                    LOGGER.debug("Wand pick {}x{} in {} ms", pic.width(), pic.height(), (System.nanoTime() - start) / 1_000_000);
                    boolean large = (long) area.pixelCount() * 2 > (long) pic.width() * pic.height();
                    result = new PickResult(area, large, null);
                } catch (Throwable t) {
                    LOGGER.warn("Couldn't pick from the picture", t);
                } finally {
                    capture.release();
                    PickResult done = result;
                    minecraft.execute(() -> finishPick(done, generation, replaceLast));
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warn("Couldn't start the pick", e);
            capture.release();
            pickRunning = false;
            updateButtons();
        }
    }

    private void finishPick(PickResult result, int generation, boolean replaceLast) {
        pickRunning = false;
        if (generation == pickGeneration && leaseHeld) {
            PickedArea area = result.area();
            if (area == null) {
                showOverlayMessage(Component.translatable("screenshotutilities.popup.wand_failed"));
            } else {
                List<PickedArea> picks = new ArrayList<>(filter.pickedAreas());
                if (replaceLast && !picks.isEmpty()) {
                    picks.set(picks.size() - 1, area);
                    filter = filter.withPickedAreas(picks);
                } else if (!picks.contains(area)) {
                    picks.add(area);
                    filter = filter.withPickedAreas(picks);
                    addedOrder.add(Added.PICK);
                }
                composedPreviewDirty = true;
                if (result.mobName() != null) {
                    showOverlayMessage(Component.translatable("screenshotutilities.popup.wand_mob", result.mobName()));
                } else if (result.large()) {
                    showOverlayMessage(Component.translatable("screenshotutilities.popup.wand_large"));
                }
            }
        }
        updateButtons();
    }

    /**
     * The whole mob under the click, as it shows in the source picture, or null to use the colour wand: no mask (none
     * yet, none made, or it failed), no mob there, the click is on something covering the mob, or where the HUD is can't
     * be told. Runs on the pick worker, which holds a lease. Never fails the pick, the colour wand is always left.
     */
    @Nullable
    private PickResult pickMob(PickedArea.PictureSource source, int seedX, int seedY) {
        try {
            EntityMask mask = capture.awaitEntityMask(MOB_MASK_WAIT_MS);
            if (mask == null || mask.idAt(seedX, seedY) == EntityMask.NONE || !capture.has(source.stage())) {
                return null;
            }
            NativeImage stage = capture.get(source.stage());
            if (stage.getWidth() != mask.width() || stage.getHeight() != mask.height()) {
                return null;
            }
            BitSet covered = null;
            if (source.stage() == CaptureStage.FINAL) {
                covered = hudCover(source, mask);
                if (covered == null) {
                    return null;
                }
            }
            long start = System.nanoTime();
            PickedArea area = MobPick.select(mask, seedX, seedY, covered, source);
            LOGGER.debug("Mob pick {}x{} in {} ms", mask.width(), mask.height(), (System.nanoTime() - start) / 1_000_000);
            return area != null ? new PickResult(area, false, mask.nameOf(area.mobId())) : null;
        } catch (Throwable t) {
            LOGGER.warn("Mob pick failed, using the colour wand", t);
            return null;
        }
    }

    /** The shown HUD group a click landed on in a Final source, so hiding that group hides the pick too. */
    @Nullable
    private String hudGroupAt(PickedArea.PictureSource source, int x, int y, int width, int height) {
        if (capture.isLayersPending()) {
            capture.awaitLayers(CaptureSaver.REBUILD_TIMEOUT_MS);
        }
        LayerStack stack = capture.layerStack();
        if (stack == null || stack.width() != width || stack.height() != height) {
            return null;
        }
        Set<String> skipped = new HashSet<>(source.hiddenGroups());
        skipped.add(HudGroups.SCREEN_EFFECTS);
        return stack.groupAt(x, y, skipped, source.eraseBoxes());
    }

    /** What the filter shows with the current hidden groups. Picks made on a hidden HUD element mark nothing. */
    private FilterSettings shownFilter() {
        return filter.shownWith(hiddenGroups);
    }

    /** Pixels the HUD covers in a Final source, or null if that can't be told. */
    @Nullable
    private BitSet hudCover(PickedArea.PictureSource source, EntityMask mask) {
        if (capture.isLayersPending()) {
            capture.awaitLayers(CaptureSaver.REBUILD_TIMEOUT_MS);
        }
        LayerStack stack = capture.layerStack();
        if (stack != null && stack.width() == mask.width() && stack.height() == mask.height()) {
            // Screen effects such as the vignette tint the whole view rather than cover anything.
            return MobPick.hudCover(stack.parts(), source.hiddenGroups(), Set.of(HudGroups.SCREEN_EFFECTS),
                    source.eraseBoxes(), mask.width(), mask.height());
        }
        BitSet hud = mask.hudPixels();
        if (hud != null) {
            MobPick.clearBoxes(hud, source.eraseBoxes(), mask.width(), mask.height());
        }
        return hud;
    }

    private void copyWorkingPicture() {
        // Copies, because the worker reads these after the player may have changed them.
        ScreenshotClipboard.copy(capture, view, Set.copyOf(hiddenGroups), List.copyOf(eraseBoxes), crop, shownFilter());
    }

    private void addToGallery() {
        if (!capture.isInGallery(view, hiddenGroups, eraseBoxes, crop, shownFilter())) {
            CaptureSaver.addToGallery(capture, view, Set.copyOf(hiddenGroups), List.copyOf(eraseBoxes), crop, shownFilter());
        }
        updateButtons();
    }

    private void saveCopy() {
        if (!capture.isSaved(view, hiddenGroups, eraseBoxes, crop, shownFilter())) {
            CaptureSaver.saveCopy(capture, view, Set.copyOf(hiddenGroups), List.copyOf(eraseBoxes), crop, shownFilter());
        }
        updateButtons();
    }

    private void dontAskAgain() {
        ScreenshotConfig.SHOW_POPUP.set(false);
        ScreenshotConfig.SPEC.save();
        onClose();
    }

    private void openGallery() {
        openingSettings = true;
        parked = this;
        minecraft.setScreen(new GalleryScreen(this));
    }

    private void openSettings() {
        openingSettings = true;
        parked = this;
        minecraft.setScreen(new ConfigurationScreen(ScreenshotUtilities.container(), this));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isListOpen()) {
            List<ListRow> rows = openListRows();
            Rect list = listRect(rows);
            if (list != null && list.contains(mouseX, mouseY)) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    int index = (int) Math.floor((mouseY - list.top() - 2 + listScroll) / LIST_ROW_HEIGHT);
                    if (index >= 0 && index < rows.size() && rows.get(index).enabled()) {
                        rows.get(index).action().run();
                    }
                }
                return true;
            }
            Overlay wasOpen = overlay;
            super.mouseClicked(mouseX, mouseY, button);
            // A button's own click may have changed what's open already.
            if (overlay == wasOpen) {
                overlay = Overlay.NONE;
            }
            return true;
        }

        Rect panel = groupPanelRect();
        if (panel != null && panel.contains(mouseX, mouseY)) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && canHideHud() && hideHudRowRect(panel).contains(mouseX, mouseY)) {
                onHideHudToggled(view != CaptureStage.WORLD);
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !capture.isLayersPending()) {
                Rect alwaysBtn = alwaysHideButtonRect(panel);
                if (alwaysBtn.contains(mouseX, mouseY)) {
                    saveAlwaysHide();
                    return true;
                }
                Rect listArea = groupListScrollArea(panel);
                if (listArea.contains(mouseX, mouseY)) {
                    List<String> groups = capture.groups();
                    int index = (int) Math.floor((mouseY - listArea.top() + groupScroll) / GROUP_ROW_HEIGHT);
                    if (index >= 0 && index < groups.size()) {
                        toggleGroup(groups.get(index));
                        return true;
                    }
                }
            }
            return true;
        }
        if (overlay == Overlay.HUD && !isSidePanelDocked()) {
            if (!super.mouseClicked(mouseX, mouseY, button)) {
                overlay = Overlay.NONE;
            }
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        Preview preview = currentPreview();
        if (preview == null) {
            return false;
        }
        Rect area = previewArea();
        Rect visible = visiblePart(shownRect(area, preview), area);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT || button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (zoom.isZoomed() && visible.contains(mouseX, mouseY) && !isLeftDragRunning() && panButton == -1) {
                panButton = button;
                return true;
            }
            return false;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (panButton != -1) {
            return true;
        }
        if (startScrollBarDrag(mouseX, mouseY, area, preview)) {
            return true;
        }
        if (!cropMode && boxMode == BoxMode.NONE) {
            if (zoom.isZoomed() && visible.contains(mouseX, mouseY)) {
                panButton = button;
                return true;
            }
            return false;
        }
        Rect imgRect = fullPreviewRect(area, preview);
        if (boxMode != BoxMode.NONE) {
            if (visible.contains(mouseX, mouseY)) {
                if (boxMode == BoxMode.HIGHLIGHT && wandMode) {
                    startPick(
                            Math.min(screenToImageX(mouseX, imgRect, preview.sourceWidth()), preview.sourceWidth() - 1),
                            Math.min(screenToImageY(mouseY, imgRect, preview.sourceHeight()), preview.sourceHeight() - 1),
                            MagicWand.DEFAULT_TOLERANCE, null, false, hasShiftDown());
                    return true;
                }
                if (boxMode == BoxMode.ERASE && view == CaptureStage.WORLD) {
                    view = CaptureStage.FINAL;
                    hiddenGroups.clear();
                    composedPreviewDirty = true;
                }
                dragMode = DragMode.DRAW;
                dragAnchorX = screenToImageX(mouseX, imgRect, preview.sourceWidth());
                dragAnchorY = screenToImageY(mouseY, imgRect, preview.sourceHeight());
                draftBox = null;
                return true;
            }
            return false;
        }
        if (!cropMode) {
            return false;
        }
        // Handles stay in screen units, so they are as easy to grab at any zoom. Only what shows can be grabbed.
        boolean nearVisible = mouseX >= visible.left() - HANDLE_HIT_RADIUS && mouseX <= visible.right() + HANDLE_HIT_RADIUS
                && mouseY >= visible.top() - HANDLE_HIT_RADIUS && mouseY <= visible.bottom() + HANDLE_HIT_RADIUS;
        if (!nearVisible) {
            return false;
        }
        if (draftCrop != null) {
            Rect box = cropOnScreen(draftCrop, preview, imgRect);
            Handle handle = hitHandle(box, mouseX, mouseY);
            if (handle != null) {
                dragMode = DragMode.RESIZE;
                activeHandle = handle;
                dragStartCrop = draftCrop;
                dragStartMouseX = mouseX;
                dragStartMouseY = mouseY;
                return true;
            }
            if (mouseX >= box.left() && mouseX <= box.right() && mouseY >= box.top() && mouseY <= box.bottom()) {
                dragMode = DragMode.MOVE;
                dragStartCrop = draftCrop;
                dragStartMouseX = mouseX;
                dragStartMouseY = mouseY;
                return true;
            }
        }
        dragMode = DragMode.DRAW;
        dragAnchorX = screenToImageX(clampTo(mouseX, visible.left(), visible.right()), imgRect, preview.sourceWidth());
        dragAnchorY = screenToImageY(clampTo(mouseY, visible.top(), visible.bottom()), imgRect, preview.sourceHeight());
        draftCrop = CropRect.fromDrag(dragAnchorX, dragAnchorY, dragAnchorX, dragAnchorY,
                preview.sourceWidth(), preview.sourceHeight());
        updateButtons();
        return true;
    }

    private static double clampTo(double value, int min, int max) {
        return Math.clamp(value, min, Math.max(min, max));
    }

    /** Left mouse on a scroll bar. Off the thumb, the thumb jumps to the mouse first. */
    private boolean startScrollBarDrag(double mouseX, double mouseY, Rect area, Preview preview) {
        if (!zoom.isZoomed()) {
            return false;
        }
        Rect base = shownBaseRect(area, preview);
        Rect visible = visiblePart(shownRect(area, preview), area);
        Rect across = scrollBarAcross(base, area, visible);
        if (across != null && grabRect(across, true).contains(mouseX, mouseY)) {
            ZoomView.Thumb thumb = zoom.thumbX(base.box(), area.box(), across.left(), across.width());
            if (mouseX < thumb.start() || mouseX > thumb.end()) {
                zoom.dragThumbX(mouseX - (thumb.start() + thumb.end()) / 2, base.box(), area.box(), across.width());
            }
            barDrag = ScrollBar.ACROSS;
            return true;
        }
        Rect down = scrollBarDown(base, area, visible);
        if (down != null && grabRect(down, false).contains(mouseX, mouseY)) {
            ZoomView.Thumb thumb = zoom.thumbY(base.box(), area.box(), down.top(), down.height());
            if (mouseY < thumb.start() || mouseY > thumb.end()) {
                zoom.dragThumbY(mouseY - (thumb.start() + thumb.end()) / 2, base.box(), area.box(), down.height());
            }
            barDrag = ScrollBar.DOWN;
            return true;
        }
        return false;
    }

    /** The bar's own rect grown inwards, so a thin bar is still easy to hit. */
    private static Rect grabRect(Rect bar, boolean across) {
        return across
                ? new Rect(bar.left(), bar.bottom() - ZOOM_BAR_GRAB, bar.right(), bar.bottom())
                : new Rect(bar.right() - ZOOM_BAR_GRAB, bar.top(), bar.right(), bar.bottom());
    }

    /** The bottom bar, or null when the picture fits across. */
    @Nullable
    private Rect scrollBarAcross(Rect base, Rect area, Rect visible) {
        if (!zoom.canScrollX(base.box(), area.box())) {
            return null;
        }
        int right = visible.right() - (zoom.canScrollY(base.box(), area.box()) ? ZOOM_BAR_SIZE + 1 : 0);
        return new Rect(visible.left(), visible.bottom() - ZOOM_BAR_SIZE, right, visible.bottom());
    }

    /** The right bar, or null when the picture fits top to bottom. */
    @Nullable
    private Rect scrollBarDown(Rect base, Rect area, Rect visible) {
        if (!zoom.canScrollY(base.box(), area.box())) {
            return null;
        }
        int bottom = visible.bottom() - (zoom.canScrollX(base.box(), area.box()) ? ZOOM_BAR_SIZE + 1 : 0);
        return new Rect(visible.right() - ZOOM_BAR_SIZE, visible.top(), visible.right(), bottom);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isListOpen()) {
            List<ListRow> rows = openListRows();
            Rect list = listRect(rows);
            if (list != null && list.contains(mouseX, mouseY)) {
                int maxScroll = Math.max(0, rows.size() * LIST_ROW_HEIGHT - (list.height() - 4));
                listScroll = Math.clamp(listScroll - scrollY * LIST_ROW_HEIGHT, 0, maxScroll);
                return true;
            }
        }
        Rect panel = groupPanelRect();
        if (panel != null && panel.contains(mouseX, mouseY) && !capture.isLayersPending()) {
            Rect listArea = groupListScrollArea(panel);
            int maxScroll = Math.max(0, capture.groups().size() * GROUP_ROW_HEIGHT - listArea.height());
            groupScroll = Math.clamp(groupScroll - scrollY * GROUP_ROW_HEIGHT, 0, maxScroll);
            return true;
        }
        Preview preview = currentPreview();
        if (preview != null && scrollY != 0) {
            Rect area = previewArea();
            if (visiblePart(shownRect(area, preview), area).contains(mouseX, mouseY)) {
                // MOVE and RESIZE work from mouse distance, which goes wrong if the scale changes partway.
                if (!isLeftDragRunning()) {
                    zoom.zoomAt(scrollY, mouseX, mouseY, shownBaseRect(area, preview).box(), area.box());
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panButton != -1 || barDrag != ScrollBar.NONE) {
            Preview preview = currentPreview();
            if (preview != null) {
                Rect area = previewArea();
                Rect base = shownBaseRect(area, preview);
                if (barDrag != ScrollBar.NONE && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    Rect visible = visiblePart(shownRect(area, preview), area);
                    Rect across = scrollBarAcross(base, area, visible);
                    Rect down = scrollBarDown(base, area, visible);
                    if (barDrag == ScrollBar.ACROSS && across != null) {
                        zoom.dragThumbX(dragX, base.box(), area.box(), across.width());
                    } else if (barDrag == ScrollBar.DOWN && down != null) {
                        zoom.dragThumbY(dragY, base.box(), area.box(), down.height());
                    }
                } else if (button == panButton && (panButton != GLFW.GLFW_MOUSE_BUTTON_LEFT || dragMode == DragMode.NONE)) {
                    zoom.panBy(dragX, dragY, base.box(), area.box());
                }
            }
            return true;
        }
        if (boxMode != BoxMode.NONE && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragMode == DragMode.DRAW) {
            Preview preview = currentPreview();
            if (preview != null) {
                Rect area = previewArea();
                Rect imgRect = fullPreviewRect(area, preview);
                Rect visible = visiblePart(imgRect, area);
                // No auto-scroll, so a box stops at what shows.
                int curX = screenToImageX(clampTo(mouseX, visible.left(), visible.right()), imgRect, preview.sourceWidth());
                int curY = screenToImageY(clampTo(mouseY, visible.top(), visible.bottom()), imgRect, preview.sourceHeight());
                draftBox = boxFromDrag(
                        dragAnchorX, dragAnchorY, curX, curY, preview.sourceWidth(), preview.sourceHeight());
                return true;
            }
        }
        if (cropMode && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragMode != DragMode.NONE) {
            Preview preview = currentPreview();
            if (preview != null) {
                Rect imgRect = fullPreviewRect(previewArea(), preview);
                int srcW = preview.sourceWidth();
                int srcH = preview.sourceHeight();
                switch (dragMode) {
                    case DRAW -> {
                        Rect visible = visiblePart(imgRect, previewArea());
                        int curX = screenToImageX(clampTo(mouseX, visible.left(), visible.right()), imgRect, srcW);
                        int curY = screenToImageY(clampTo(mouseY, visible.top(), visible.bottom()), imgRect, srcH);
                        draftCrop = CropRect.fromDrag(dragAnchorX, dragAnchorY, curX, curY, srcW, srcH);
                    }
                    case MOVE -> {
                        if (dragStartCrop != null) {
                            int dx = screenDeltaToImage(mouseX - dragStartMouseX, imgRect.width(), srcW);
                            int dy = screenDeltaToImage(mouseY - dragStartMouseY, imgRect.height(), srcH);
                            draftCrop = dragStartCrop.move(dx, dy, srcW, srcH);
                        }
                    }
                    case RESIZE -> {
                        if (dragStartCrop != null && activeHandle != null) {
                            int dx = screenDeltaToImage(mouseX - dragStartMouseX, imgRect.width(), srcW);
                            int dy = screenDeltaToImage(mouseY - dragStartMouseY, imgRect.height(), srcH);
                            draftCrop = dragStartCrop.resize(activeHandle, dx, dy, srcW, srcH);
                        }
                    }
                    case NONE -> {
                    }
                }
                updateButtons();
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == panButton) {
            panButton = -1;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && barDrag != ScrollBar.NONE) {
            barDrag = ScrollBar.NONE;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragMode != DragMode.NONE) {
            if (boxMode != BoxMode.NONE && dragMode == DragMode.DRAW) {
                Preview preview = currentPreview();
                if (preview != null && draftBox == null) {
                    Rect area = previewArea();
                    Rect imgRect = fullPreviewRect(area, preview);
                    Rect visible = visiblePart(imgRect, area);
                    int curX = screenToImageX(clampTo(mouseX, visible.left(), visible.right()), imgRect, preview.sourceWidth());
                    int curY = screenToImageY(clampTo(mouseY, visible.top(), visible.bottom()), imgRect, preview.sourceHeight());
                    draftBox = boxFromDrag(
                            dragAnchorX, dragAnchorY, curX, curY, preview.sourceWidth(), preview.sourceHeight());
                }
                if (draftBox != null) {
                    CropRect finished = draftBox;
                    draftBox = null;
                    addBox(finished);
                }
            }
            dragMode = DragMode.NONE;
            activeHandle = null;
            dragStartCrop = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Nullable
    private static CropRect boxFromDrag(int x1, int y1, int x2, int y2, int maxW, int maxH) {
        int left = Math.clamp(Math.min(x1, x2), 0, maxW);
        int right = Math.clamp(Math.max(x1, x2), 0, maxW);
        int top = Math.clamp(Math.min(y1, y2), 0, maxH);
        int bottom = Math.clamp(Math.max(y1, y2), 0, maxH);
        if (right - left < MIN_ERASE_DRAG_PIXELS || bottom - top < MIN_ERASE_DRAG_PIXELS) {
            return null;
        }
        return new CropRect(left, top, right - left, bottom - top);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isListOpen() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            overlay = Overlay.NONE;
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_0 || keyCode == GLFW.GLFW_KEY_KP_0) && zoom.isZoomed() && !isLeftDragRunning()) {
            zoom.reset();
            panButton = -1;
            return true;
        }
        if (cropMode) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelCrop();
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && draftCrop != null) {
                applyCrop();
                return true;
            }
        }
        if (boxMode != BoxMode.NONE) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelBoxMode();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                finishBoxMode();
                return true;
            }
        }
        if (overlay == Overlay.HUD && !isSidePanelDocked() && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            overlay = Overlay.NONE;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static int screenToImageX(double mouseX, Rect imgRect, int sourceWidth) {
        double rel = (mouseX - imgRect.left()) / Math.max(1, imgRect.width());
        return Math.clamp((int) Math.round(rel * sourceWidth), 0, sourceWidth);
    }

    private static int screenToImageY(double mouseY, Rect imgRect, int sourceHeight) {
        double rel = (mouseY - imgRect.top()) / Math.max(1, imgRect.height());
        return Math.clamp((int) Math.round(rel * sourceHeight), 0, sourceHeight);
    }

    private static int screenDeltaToImage(double screenDelta, int screenSpan, int sourceSpan) {
        return (int) Math.round(screenDelta * sourceSpan / Math.max(1, screenSpan));
    }

    private static Rect cropOnScreen(CropRect rect, Preview preview, Rect imgRect) {
        CropRect scaled = rect.scaleTo(preview.sourceWidth(), preview.sourceHeight(), imgRect.width(), imgRect.height());
        return new Rect(
                imgRect.left() + scaled.x(),
                imgRect.top() + scaled.y(),
                imgRect.left() + scaled.right(),
                imgRect.top() + scaled.bottom());
    }

    @Nullable
    private static Handle hitHandle(Rect box, double mx, double my) {
        boolean nearLeft = Math.abs(mx - box.left()) <= HANDLE_HIT_RADIUS;
        boolean nearRight = Math.abs(mx - box.right()) <= HANDLE_HIT_RADIUS;
        boolean nearTop = Math.abs(my - box.top()) <= HANDLE_HIT_RADIUS;
        boolean nearBottom = Math.abs(my - box.bottom()) <= HANDLE_HIT_RADIUS;
        boolean withinX = mx >= box.left() - HANDLE_HIT_RADIUS && mx <= box.right() + HANDLE_HIT_RADIUS;
        boolean withinY = my >= box.top() - HANDLE_HIT_RADIUS && my <= box.bottom() + HANDLE_HIT_RADIUS;

        if (nearLeft && nearTop) {
            return Handle.TOP_LEFT;
        }
        if (nearRight && nearTop) {
            return Handle.TOP_RIGHT;
        }
        if (nearRight && nearBottom) {
            return Handle.BOTTOM_RIGHT;
        }
        if (nearLeft && nearBottom) {
            return Handle.BOTTOM_LEFT;
        }
        if (nearTop && withinX) {
            return Handle.TOP;
        }
        if (nearBottom && withinX) {
            return Handle.BOTTOM;
        }
        if (nearLeft && withinY) {
            return Handle.LEFT;
        }
        if (nearRight && withinY) {
            return Handle.RIGHT;
        }
        return null;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);

        Preview preview = currentPreview();
        if (preview == null) {
            return;
        }
        Rect area = previewArea();
        Rect displayedRect = shownRect(area, preview);
        Rect visible = visiblePart(displayedRect, area);
        graphics.fill(visible.left() - 1, visible.top() - 1, visible.right() + 1, visible.bottom() + 1, 0xFF000000);
        // Zoomed, the picture is larger than its area and must never slide over the buttons, panel or title.
        graphics.enableScissor(area.left(), area.top(), area.right(), area.bottom());
        if (showsCrop()) {
            renderCroppedPreview(graphics, displayedRect, preview, crop);
            renderSharp(graphics, preview, displayedRect, visible);
        } else {
            graphics.blit(preview.id(), displayedRect.left(), displayedRect.top(), displayedRect.width(), displayedRect.height(),
                    0, 0, preview.width(), preview.height(), preview.width(), preview.height());
            renderSharp(graphics, preview, displayedRect, visible);
            if (cropMode) {
                renderCropOverlay(graphics, displayedRect, visible, area, preview);
            } else if (boxMode != BoxMode.NONE) {
                renderBoxOverlay(graphics, displayedRect, visible, preview);
            }
        }
        graphics.disableScissor();
        renderZoomMarks(graphics, area, preview, visible);
        renderGroupPanel(graphics, mouseX, mouseY);
        renderList(graphics, mouseX, mouseY);
        renderToolRowStatus(graphics, area, visible);
    }

    private SharpKey sharpKey() {
        CaptureStage stage = capture.has(view) ? view : CaptureStage.FINAL;
        return new SharpKey(stage, Set.copyOf(hiddenGroups), List.copyOf(eraseBoxes), shownFilter());
    }

    /**
     * Past the preview's own size the soft preview is drawn larger than it is. Once the view has been still for a moment,
     * the part that shows is cut from the full-size working picture on a worker and drawn over it in the same place.
     */
    private void renderSharp(GuiGraphics graphics, Preview preview, Rect picture, Rect visible) {
        int srcW = preview.sourceWidth();
        int srcH = preview.sourceHeight();
        CropRect shown = showsCrop() ? crop.clamp(srcW, srcH) : new CropRect(0, 0, srcW, srcH);
        double guiScale = minecraft.getWindow().getGuiScale();
        double previewTexels = (double) shown.width() * preview.width() / srcW;
        if (!zoom.isZoomed() || visible.width() <= 0 || visible.height() <= 0
                || picture.width() * guiScale <= previewTexels + 0.5) {
            sharpLastWanted = null;
            return;
        }
        SharpKey key = sharpKey();
        if (sharpPicture != null && !sharpPicture.key().equals(key)) {
            sharpPicture = null;
        }
        if (sharpTile != null && !sharpTile.key().equals(key)) {
            releaseSharpTile();
        }

        double perPixelX = (double) picture.width() / shown.width();
        double perPixelY = (double) picture.height() / shown.height();
        int x0 = shown.x() + (int) Math.floor((visible.left() - picture.left()) / perPixelX);
        int y0 = shown.y() + (int) Math.floor((visible.top() - picture.top()) / perPixelY);
        int x1 = shown.x() + (int) Math.ceil((visible.right() - picture.left()) / perPixelX);
        int y1 = shown.y() + (int) Math.ceil((visible.bottom() - picture.top()) / perPixelY);
        x0 = Math.clamp(x0, shown.x(), shown.right() - 1);
        y0 = Math.clamp(y0, shown.y(), shown.bottom() - 1);
        x1 = Math.clamp(x1, x0 + 1, shown.right());
        y1 = Math.clamp(y1, y0 + 1, shown.bottom());
        CropRect wanted = new CropRect(x0, y0, x1 - x0, y1 - y0);

        long now = Util.getMillis();
        if (!wanted.equals(sharpLastWanted)) {
            sharpLastWanted = wanted;
            sharpStillSinceMs = now;
        }
        SharpTile tile = sharpTile;
        boolean covers = tile != null && contains(tile.region(), wanted);
        if (!covers && !sharpRunning && now - sharpStillSinceMs >= SHARP_DELAY_MS) {
            int mx = (int) Math.ceil(wanted.width() * SHARP_MARGIN);
            int my = (int) Math.ceil(wanted.height() * SHARP_MARGIN);
            int rx0 = Math.max(shown.x(), wanted.x() - mx);
            int ry0 = Math.max(shown.y(), wanted.y() - my);
            int rx1 = Math.min(shown.right(), wanted.right() + mx);
            int ry1 = Math.min(shown.bottom(), wanted.bottom() + my);
            startSharp(key, new CropRect(rx0, ry0, rx1 - rx0, ry1 - ry0), srcW, srcH);
        }
        if (tile == null || !contains(tile.region(), wanted)) {
            return;
        }
        boolean nearest = perPixelX * guiScale >= NEAREST_FROM;
        if (sharpNearest != nearest) {
            tile.texture().setFilter(!nearest, false);
            sharpNearest = nearest;
        }
        CropRect r = tile.region();
        // A float pose, so the sharp part lands exactly on the soft one rather than on whole GUI units.
        graphics.pose().pushPose();
        graphics.pose().translate(
                picture.left() + (r.x() - shown.x()) * perPixelX, picture.top() + (r.y() - shown.y()) * perPixelY, 0);
        graphics.pose().scale((float) perPixelX, (float) perPixelY, 1);
        graphics.blit(tile.id(), 0, 0, 0, 0, r.width(), r.height(), r.width(), r.height());
        graphics.pose().popPose();
    }

    private static boolean contains(CropRect outer, CropRect inner) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    /**
     * Builds the full-size working picture if it isn't kept already (no crop, current filter, on a worker with its own
     * lease, as {@link #startPick} does), then cuts {@code region} out of it into an image. Only the upload is left for
     * the render thread.
     */
    private void startSharp(SharpKey key, CropRect region, int srcW, int srcH) {
        SharpPicture have = sharpPicture;
        boolean needLease = have == null;
        if (needLease && !capture.acquire()) {
            return;
        }
        sharpRunning = true;
        int generation = sharpGeneration;
        long askedAt = System.nanoTime();
        try {
            Util.ioPool().execute(() -> {
                SharpPicture picture = have;
                NativeImage image = null;
                long buildMs = -1;
                long cutMs = -1;
                try {
                    if (picture == null) {
                        long start = System.nanoTime();
                        if (!key.hiddenGroups().isEmpty() && key.view() == CaptureStage.FINAL) {
                            capture.awaitLayers(CaptureSaver.REBUILD_TIMEOUT_MS);
                        }
                        CaptureSaver.RebuiltPicture pic = CaptureSaver.buildPicture(
                                capture, key.view(), key.hiddenGroups(), key.eraseBoxes(), null, key.filter());
                        int[] pixels = pic.pixels();
                        if (pixels == null) {
                            NativeImage stage = pic.directStage();
                            pixels = new CropRect(0, 0, pic.width(), pic.height()).extract(stage::getPixelRGBA);
                        }
                        picture = new SharpPicture(key, pixels, pic.width(), pic.height());
                        buildMs = (System.nanoTime() - start) / 1_000_000;
                    }
                    if (picture.width() == srcW && picture.height() == srcH && region.right() <= srcW && region.bottom() <= srcH) {
                        long start = System.nanoTime();
                        image = cutOut(picture, region);
                        cutMs = (System.nanoTime() - start) / 1_000_000;
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Couldn't build the sharp view", t);
                } finally {
                    if (needLease) {
                        capture.release();
                    }
                    SharpPicture builtPicture = picture;
                    NativeImage cut = image;
                    long built = buildMs;
                    long cutTook = cutMs;
                    try {
                        minecraft.execute(() -> finishSharp(generation, key, builtPicture, region, cut, askedAt, built, cutTook));
                    } catch (RuntimeException e) {
                        if (cut != null) {
                            cut.close();
                        }
                        throw e;
                    }
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warn("Couldn't start the sharp view", e);
            if (needLease) {
                capture.release();
            }
            sharpRunning = false;
        }
    }

    private static NativeImage cutOut(SharpPicture picture, CropRect region) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, region.width(), region.height(), false);
        try {
            int[] pixels = picture.pixels();
            int w = picture.width();
            for (int y = 0; y < region.height(); y++) {
                int row = (region.y() + y) * w + region.x();
                for (int x = 0; x < region.width(); x++) {
                    image.setPixelRGBA(x, y, pixels[row + x]);
                }
            }
            return image;
        } catch (Throwable t) {
            image.close();
            throw t;
        }
    }

    private void finishSharp(
            int generation,
            SharpKey key,
            @Nullable SharpPicture picture,
            CropRect region,
            @Nullable NativeImage image,
            long askedAt,
            long buildMs,
            long cutMs) {
        sharpRunning = false;
        boolean current = generation == sharpGeneration && leaseHeld && key.equals(sharpKey());
        if (!current) {
            if (image != null) {
                image.close();
            }
            return;
        }
        if (picture != null) {
            sharpPicture = picture;
        }
        if (image == null) {
            return;
        }
        long start = System.nanoTime();
        DynamicTexture texture;
        try {
            texture = new DynamicTexture(image);
        } catch (Throwable t) {
            image.close();
            LOGGER.warn("Couldn't upload the sharp view", t);
            return;
        }
        texture.setFilter(true, false);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ScreenshotUtilities.MOD_ID, "popup_sharp/" + nextTextureId++);
        // The texture manager closes the texture, and with it the image, on release.
        minecraft.getTextureManager().register(id, texture);
        releaseSharpTile();
        sharpTile = new SharpTile(key, region, id, texture);
        sharpNearest = false;
        long uploadMs = (System.nanoTime() - start) / 1_000_000;
        LOGGER.debug("Sharp view {}x{} of {}x{}: picture {} ms, cut {} ms, upload {} ms, {} ms in all",
                region.width(), region.height(), picture != null ? picture.width() : 0, picture != null ? picture.height() : 0,
                buildMs, cutMs, uploadMs, (System.nanoTime() - askedAt) / 1_000_000);
    }

    private void releaseSharpTile() {
        if (sharpTile != null) {
            minecraft.getTextureManager().release(sharpTile.id());
            sharpTile = null;
        }
    }

    /** The zoom label and scroll bars, only while zoomed. */
    private void renderZoomMarks(GuiGraphics graphics, Rect area, Preview preview, Rect visible) {
        if (!zoom.isZoomed()) {
            return;
        }
        Rect base = shownBaseRect(area, preview);
        Rect across = scrollBarAcross(base, area, visible);
        if (across != null) {
            graphics.fill(across.left(), across.top(), across.right(), across.bottom(), 0x66000000);
            ZoomView.Thumb thumb = zoom.thumbX(base.box(), area.box(), across.left(), across.width());
            graphics.fill((int) Math.round(thumb.start()), across.top(), (int) Math.round(thumb.end()), across.bottom(),
                    barDrag == ScrollBar.ACROSS ? 0xFFFFFFFF : 0xCCCCCCCC);
        }
        Rect down = scrollBarDown(base, area, visible);
        if (down != null) {
            graphics.fill(down.left(), down.top(), down.right(), down.bottom(), 0x66000000);
            ZoomView.Thumb thumb = zoom.thumbY(base.box(), area.box(), down.top(), down.height());
            graphics.fill(down.left(), (int) Math.round(thumb.start()), down.right(), (int) Math.round(thumb.end()),
                    barDrag == ScrollBar.DOWN ? 0xFFFFFFFF : 0xCCCCCCCC);
        }
        Component label = Component.translatable("screenshotutilities.popup.zoom", zoom.percent());
        int labelW = font.width(label);
        int right = visible.right() - (down != null ? ZOOM_BAR_SIZE + 1 : 0) - 3;
        int x = Math.max(visible.left() + 3, right - labelW);
        int y = visible.top() + 4;
        graphics.fill(x - 3, y - 2, x + labelW + 3, y + font.lineHeight + 1, 0xAA000000);
        graphics.drawString(font, label, x, y, 0xFFFFFF, false);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        List<ListRow> rows = openListRows();
        Rect menu = listRect(rows);
        if (menu == null) {
            return;
        }
        graphics.fill(menu.left(), menu.top(), menu.right(), menu.bottom(), 0xEE101010);
        drawBoxOutline(graphics, menu, 0xFF555555);

        int contentHeight = rows.size() * LIST_ROW_HEIGHT;
        int listLeft = menu.left() + 2;
        int listTop = menu.top() + 2;
        int listRight = menu.right() - 2;
        int listBottom = menu.bottom() - 2;
        int listH = listBottom - listTop;
        int maxScroll = Math.max(0, contentHeight - listH);
        listScroll = Math.clamp(listScroll, 0, maxScroll);
        boolean hasScrollbar = maxScroll > 0;
        int usableRight = listRight - (hasScrollbar ? 4 : 0);

        graphics.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            ListRow row = rows.get(i);
            int rowTop = listTop + i * LIST_ROW_HEIGHT - (int) Math.round(listScroll);
            int rowBottom = rowTop + LIST_ROW_HEIGHT;
            if (rowBottom <= listTop || rowTop >= listBottom) {
                continue;
            }

            boolean hovered = row.enabled() && mouseX >= listLeft && mouseX < usableRight
                    && mouseY >= Math.max(rowTop, listTop) && mouseY < Math.min(rowBottom, listBottom);
            if (hovered) {
                graphics.fill(listLeft, rowTop, usableRight, rowBottom, 0x44FFFFFF);
            }

            int markX = listLeft + 2;
            int markY = rowTop + (LIST_ROW_HEIGHT - GROUP_BOX_SIZE) / 2;
            switch (row.mark()) {
                case PICKED -> graphics.fill(markX + 3, markY + 3, markX + GROUP_BOX_SIZE - 3, markY + GROUP_BOX_SIZE - 3, 0xFFFFFF55);
                case TICK_OFF -> drawCheckbox(graphics, markX, markY, false, hovered);
                case TICK_ON -> drawCheckbox(graphics, markX, markY, true, hovered);
                case NONE -> {
                }
            }

            int textY = rowTop + (LIST_ROW_HEIGHT - font.lineHeight) / 2 + 1;
            int color = row.mark() == RowMark.PICKED ? 0xFFFF55
                    : !row.enabled() ? 0x777777
                    : hovered ? 0xFFFFFF : 0xE0E0E0;
            graphics.drawString(font, row.label(), listLeft + LIST_TEXT_INDENT, textY, color, false);
        }
        graphics.disableScissor();

        if (hasScrollbar && listH > 0) {
            int barX = listRight - 2;
            graphics.fill(barX, listTop, barX + 2, listBottom, 0x55000000);
            int thumbH = Math.max(8, listH * listH / contentHeight);
            int thumbY = listTop + (int) Math.round((listH - thumbH) * (listScroll / maxScroll));
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFAAAAAA);
        }
    }

    private void renderBoxOverlay(GuiGraphics graphics, Rect imgRect, Rect visible, Preview preview) {
        if (!hasAnyBoxes() && draftBox == null) {
            drawHint(graphics, visible, Component.translatable(boxMode == BoxMode.HIGHLIGHT
                    ? (wandMode ? "screenshotutilities.popup.wand_hint" : "screenshotutilities.popup.highlight_hint")
                    : "screenshotutilities.popup.erase_hint"));
        }
        for (CropRect box : boxes()) {
            Rect s = cropOnScreen(box, preview, imgRect);
            drawBoxOutline(graphics, s, 0x9988CCFF);
        }
        if (draftBox != null) {
            Rect s = cropOnScreen(draftBox, preview, imgRect);
            graphics.fill(s.left(), s.top(), s.right(), s.bottom(), 0x4488CCFF);
            drawBoxOutline(graphics, s, 0xFFFFFFFF);
        }
    }

    private static void drawBoxOutline(GuiGraphics graphics, Rect box, int color) {
        graphics.fill(box.left(), box.top(), box.right(), box.top() + 1, color);
        graphics.fill(box.left(), box.bottom() - 1, box.right(), box.bottom(), color);
        graphics.fill(box.left(), box.top(), box.left() + 1, box.bottom(), color);
        graphics.fill(box.right() - 1, box.top(), box.right(), box.bottom(), color);
    }

    private void renderGroupPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        Rect panel = groupPanelRect();
        if (panel == null) {
            return;
        }
        int bgColor = isSidePanelDocked() ? 0x99000000 : 0xEE101010;
        graphics.fill(panel.left(), panel.top(), panel.right(), panel.bottom(), bgColor);
        graphics.fill(panel.left(), panel.top(), panel.right(), panel.top() + 1, 0xFF555555);
        graphics.fill(panel.left(), panel.bottom() - 1, panel.right(), panel.bottom(), 0xFF555555);
        graphics.fill(panel.left(), panel.top(), panel.left() + 1, panel.bottom(), 0xFF555555);
        graphics.fill(panel.right() - 1, panel.top(), panel.right(), panel.bottom(), 0xFF555555);

        Component header = Component.translatable("screenshotutilities.popup.hud_title");
        graphics.drawString(font, header, panel.left() + 5, panel.top() + 4, 0xE0E0E0, false);
        graphics.fill(panel.left() + 2, panel.top() + GROUP_HEADER_HEIGHT - 1, panel.right() - 2, panel.top() + GROUP_HEADER_HEIGHT, 0xFF444444);

        if (canHideHud()) {
            renderHideHudRow(graphics, panel, mouseX, mouseY);
        }

        Rect listArea = groupListScrollArea(panel);
        if (capture.isLayersPending()) {
            Component loading = Component.translatable("screenshotutilities.popup.loading_groups");
            graphics.drawString(font, loading, listArea.left() + 2, listArea.top() + 6, 0xAAAAAA, false);
            return;
        }

        List<String> groups = capture.groups();
        int contentHeight = groups.size() * GROUP_ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - listArea.height());
        groupScroll = Math.clamp(groupScroll, 0, maxScroll);

        boolean hasScrollbar = maxScroll > 0;
        int textMaxWidth = listArea.width() - GROUP_BOX_SIZE - 5 - (hasScrollbar ? 5 : 0);
        Component hoveredFullLabel = null;

        graphics.enableScissor(listArea.left(), listArea.top(), listArea.right(), listArea.bottom());
        for (int i = 0; i < groups.size(); i++) {
            String groupId = groups.get(i);
            int rowTop = listArea.top() + i * GROUP_ROW_HEIGHT - (int) Math.round(groupScroll);
            int rowBottom = rowTop + GROUP_ROW_HEIGHT;
            if (rowBottom <= listArea.top() || rowTop >= listArea.bottom()) {
                continue;
            }
            boolean hovered = listArea.contains(mouseX, mouseY) && mouseY >= rowTop && mouseY < rowBottom;
            if (hovered) {
                graphics.fill(listArea.left(), rowTop, listArea.right() - (hasScrollbar ? 4 : 0), rowBottom, 0x44FFFFFF);
            }

            boolean checked = view != CaptureStage.WORLD && !hiddenGroups.contains(groupId);
            int boxX = listArea.left() + 1;
            int boxY = rowTop + (GROUP_ROW_HEIGHT - GROUP_BOX_SIZE) / 2;
            drawCheckbox(graphics, boxX, boxY, checked, hovered);

            Component fullLabel = groupDisplayName(groupId);
            String labelText = fullLabel.getString();
            if (font.width(labelText) > textMaxWidth && textMaxWidth > 10) {
                labelText = font.plainSubstrByWidth(labelText, textMaxWidth - font.width("...")) + "...";
                if (hovered) {
                    hoveredFullLabel = fullLabel;
                }
            }
            int textX = boxX + GROUP_BOX_SIZE + 4;
            int textY = rowTop + (GROUP_ROW_HEIGHT - font.lineHeight) / 2 + 1;
            graphics.drawString(font, labelText, textX, textY, checked ? 0xFFFFFF : 0x999999, false);
        }
        graphics.disableScissor();

        if (hasScrollbar && listArea.height() > 0) {
            int barX = listArea.right() - 2;
            graphics.fill(barX, listArea.top(), barX + 2, listArea.bottom(), 0x55000000);
            int thumbH = Math.max(12, listArea.height() * listArea.height() / contentHeight);
            int thumbY = listArea.top() + (int) Math.round((listArea.height() - thumbH) * (groupScroll / maxScroll));
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFAAAAAA);
        }

        graphics.fill(
                panel.left() + 2,
                panel.bottom() - GROUP_FOOTER_HEIGHT,
                panel.right() - 2,
                panel.bottom() - GROUP_FOOTER_HEIGHT + 1,
                0xFF444444);
        Rect btnRect = alwaysHideButtonRect(panel);
        boolean btnActive = isAlwaysHideButtonActive();
        boolean btnHovered = btnRect.contains(mouseX, mouseY);
        int btnBg = !btnActive ? 0xFF222222 : (btnHovered ? 0xFF4A4A4A : 0xFF333333);
        int btnBorder = !btnActive ? 0xFF444444 : (btnHovered ? 0xFFFFFFFF : 0xFF777777);
        graphics.fill(btnRect.left(), btnRect.top(), btnRect.right(), btnRect.bottom(), btnBorder);
        graphics.fill(btnRect.left() + 1, btnRect.top() + 1, btnRect.right() - 1, btnRect.bottom() - 1, btnBg);
        Component btnLabel = Component.translatable("screenshotutilities.popup.always_hide");
        String btnText = btnLabel.getString();
        int maxBtnTextW = btnRect.width() - 6;
        if (font.width(btnText) > maxBtnTextW && maxBtnTextW > 10) {
            btnText = font.plainSubstrByWidth(btnText, maxBtnTextW - font.width("...")) + "...";
        }
        int btnTextW = font.width(btnText);
        int btnTextX = btnRect.left() + (btnRect.width() - btnTextW) / 2;
        int btnTextY = btnRect.top() + (btnRect.height() - font.lineHeight) / 2 + 1;
        graphics.drawString(font, btnText, btnTextX, btnTextY, btnActive ? 0xFFFFFF : 0x777777, false);

        if (hoveredFullLabel != null) {
            graphics.renderTooltip(font, hoveredFullLabel, mouseX, mouseY);
        } else if (btnHovered) {
            graphics.renderTooltip(font, Component.translatable("screenshotutilities.popup.always_hide.tooltip"), mouseX, mouseY);
        }
    }

    private void renderHideHudRow(GuiGraphics graphics, Rect panel, int mouseX, int mouseY) {
        Rect row = hideHudRowRect(panel);
        boolean hovered = row.contains(mouseX, mouseY);
        if (hovered) {
            graphics.fill(row.left(), row.top(), row.right(), row.bottom(), 0x44FFFFFF);
        }
        int boxX = row.left() + 1;
        drawCheckbox(graphics, boxX, row.top() + (GROUP_ROW_HEIGHT - GROUP_BOX_SIZE) / 2, view == CaptureStage.WORLD, hovered);
        graphics.drawString(
                font,
                Component.translatable("screenshotutilities.popup.hide_hud"),
                boxX + GROUP_BOX_SIZE + 4,
                row.top() + (GROUP_ROW_HEIGHT - font.lineHeight) / 2 + 1,
                0xFFFFFF,
                false);
        int divider = panel.top() + GROUP_HEADER_HEIGHT + HIDE_HUD_ROW_HEIGHT - 2;
        graphics.fill(panel.left() + 2, divider, panel.right() - 2, divider + 1, 0xFF444444);
    }

    private static void drawCheckbox(GuiGraphics graphics, int x, int y, boolean checked, boolean hovered) {
        int border = hovered ? 0xFFFFFFFF : 0xFFAAAAAA;
        graphics.fill(x, y, x + GROUP_BOX_SIZE, y + GROUP_BOX_SIZE, border);
        graphics.fill(x + 1, y + 1, x + GROUP_BOX_SIZE - 1, y + GROUP_BOX_SIZE - 1, 0xFF181818);
        if (checked) {
            graphics.fill(x + 3, y + 3, x + GROUP_BOX_SIZE - 3, y + GROUP_BOX_SIZE - 3, 0xFFE0E0E0);
        }
    }

    private static Component groupDisplayName(String groupId) {
        String key = HudGroups.translationKey(groupId);
        return key != null ? Component.translatable(key) : Component.literal(HudGroups.displayNameFromId(groupId));
    }

    private void renderCroppedPreview(GuiGraphics graphics, Rect picRect, Preview preview, CropRect activeCrop) {
        CropRect uv = activeCrop.scaleTo(preview.sourceWidth(), preview.sourceHeight(), preview.width(), preview.height());
        graphics.blit(preview.id(), picRect.left(), picRect.top(), picRect.width(), picRect.height(),
                uv.x(), uv.y(), uv.width(), uv.height(), preview.width(), preview.height());
    }

    /** A hint over the top middle of what shows of the picture. */
    private void drawHint(GuiGraphics graphics, Rect visible, Component hint) {
        int textW = font.width(hint);
        int pillX = visible.left() + (visible.width() - textW) / 2;
        int pillY = visible.top() + 8;
        graphics.fill(pillX - 4, pillY - 2, pillX + textW + 4, pillY + font.lineHeight + 2, 0xAA000000);
        graphics.drawString(font, hint, pillX, pillY, 0xFFFFFF, false);
    }

    /** Called with the preview area scissor on. Handles may reach a little past it, as they do at fit. */
    private void renderCropOverlay(GuiGraphics graphics, Rect imgRect, Rect visible, Rect area, Preview preview) {
        if (draftCrop == null) {
            drawHint(graphics, visible, Component.translatable("screenshotutilities.popup.crop_hint"));
            return;
        }

        Rect box = cropOnScreen(draftCrop, preview, imgRect);
        int bx0 = box.left();
        int by0 = box.top();
        int bx1 = box.right();
        int by1 = box.bottom();

        // Darken the four regions of the preview outside the crop box.
        graphics.fill(imgRect.left(), imgRect.top(), imgRect.right(), by0, 0x99000000);
        graphics.fill(imgRect.left(), by1, imgRect.right(), imgRect.bottom(), 0x99000000);
        graphics.fill(imgRect.left(), by0, bx0, by1, 0x99000000);
        graphics.fill(bx1, by0, imgRect.right(), by1, 0x99000000);

        // Outline and corner/edge handles.
        graphics.fill(bx0, by0, bx1, by0 + 1, 0xFFFFFFFF);
        graphics.fill(bx0, by1 - 1, bx1, by1, 0xFFFFFFFF);
        graphics.fill(bx0, by0, bx0 + 1, by1, 0xFFFFFFFF);
        graphics.fill(bx1 - 1, by0, bx1, by1, 0xFFFFFFFF);

        int midX = (bx0 + bx1) / 2;
        int midY = (by0 + by1) / 2;
        int reach = HANDLE_HALF_SIZE + 2;
        graphics.disableScissor();
        graphics.enableScissor(area.left() - reach, area.top() - reach, area.right() + reach, area.bottom() + reach);
        drawHandle(graphics, bx0, by0);
        drawHandle(graphics, midX, by0);
        drawHandle(graphics, bx1, by0);
        drawHandle(graphics, bx1, midY);
        drawHandle(graphics, bx1, by1);
        drawHandle(graphics, midX, by1);
        drawHandle(graphics, bx0, by1);
        drawHandle(graphics, bx0, midY);

        // Pixel size label near the top-left of the box.
        String sizeLabel = draftCrop.sizeText();
        int labelW = font.width(sizeLabel);
        int labelX = Math.clamp(bx0 + 2, visible.left() + 2, Math.max(visible.left() + 2, visible.right() - labelW - 4));
        int labelY = by0 - font.lineHeight - 4 >= visible.top() ? by0 - font.lineHeight - 3 : by0 + 4;
        labelY = Math.clamp(labelY, visible.top() + 2, Math.max(visible.top() + 2, visible.bottom() - font.lineHeight - 2));
        graphics.fill(labelX - 2, labelY - 1, labelX + labelW + 2, labelY + font.lineHeight + 1, 0xB0000000);
        graphics.drawString(font, sizeLabel, labelX, labelY, 0xFFFFFF, false);
    }

    private static void drawHandle(GuiGraphics graphics, int cx, int cy) {
        graphics.fill(cx - HANDLE_HALF_SIZE - 1, cy - HANDLE_HALF_SIZE - 1,
                cx + HANDLE_HALF_SIZE + 2, cy + HANDLE_HALF_SIZE + 2, 0xFF000000);
        graphics.fill(cx - HANDLE_HALF_SIZE, cy - HANDLE_HALF_SIZE,
                cx + HANDLE_HALF_SIZE + 1, cy + HANDLE_HALF_SIZE + 1, 0xFFFFFFFF);
    }

    private void renderToolRowStatus(GuiGraphics graphics, Rect area, Rect picRect) {
        int textY = toolStatusY;
        if (overlayMessage != null && Util.getMillis() >= overlayUntilMs) {
            overlayMessage = null;
        }
        Component croppedText = !cropMode && boxMode == BoxMode.NONE && crop != null
                ? Component.translatable("screenshotutilities.popup.cropped_size", crop.sizeText())
                : null;
        String shortCropText = crop != null ? crop.sizeText() : null;
        int leftLimit = toolRowRightEdge + GAP;
        int rightLimit = toolRowCount > 1 ? width - MARGIN : area.right();

        if (croppedText != null && overlayMessage != null) {
            int croppedWidth = font.width(croppedText);
            int msgWidth = font.width(overlayMessage);
            if (leftLimit + croppedWidth + GAP * 2 + msgWidth <= rightLimit) {
                graphics.drawString(font, croppedText, leftLimit, textY, 0xCCCCCC);
                graphics.drawString(font, overlayMessage, rightLimit - msgWidth, textY, 0xFFFFFF);
            } else {
                drawStatusText(graphics, picRect, overlayMessage.getString(), null, leftLimit, rightLimit, textY, 0xFFFFFF, true);
            }
        } else if (overlayMessage != null) {
            drawStatusText(graphics, picRect, overlayMessage.getString(), null, leftLimit, rightLimit, textY, 0xFFFFFF, true);
        } else if (croppedText != null) {
            drawStatusText(graphics, picRect, croppedText.getString(), shortCropText, leftLimit, rightLimit, textY, 0xCCCCCC, false);
        }
    }

    private void drawStatusText(
            GuiGraphics graphics,
            Rect picRect,
            String text,
            @Nullable String shortFallback,
            int leftLimit,
            int rightLimit,
            int textY,
            int color,
            boolean rightAlign) {
        int w = font.width(text);
        if (leftLimit + w <= rightLimit) {
            int x = rightAlign ? rightLimit - w : leftLimit;
            graphics.drawString(font, text, x, textY, color);
            return;
        }
        if (picRect.width() < 6 || picRect.height() < font.lineHeight) {
            return;
        }
        String label = text;
        int maxInnerWidth = Math.max(1, picRect.width() - 6);
        if (font.width(label) > maxInnerWidth && shortFallback != null) {
            label = shortFallback;
        }
        if (font.width(label) > maxInnerWidth) {
            label = font.plainSubstrByWidth(label, maxInnerWidth);
        }
        int labelW = font.width(label);
        int pillX = Math.clamp(
                picRect.right() - labelW - 4,
                picRect.left() + 1,
                Math.max(picRect.left() + 1, picRect.right() - labelW - 1));
        int pillY = Math.clamp(
                picRect.bottom() - font.lineHeight - 3,
                picRect.top() + 1,
                Math.max(picRect.top() + 1, picRect.bottom() - font.lineHeight - 1));
        int bgLeft = Math.max(picRect.left(), pillX - 3);
        int bgTop = Math.max(picRect.top(), pillY - 2);
        int bgRight = Math.min(picRect.right(), pillX + labelW + 3);
        int bgBottom = Math.min(picRect.bottom(), pillY + font.lineHeight + 2);
        graphics.enableScissor(picRect.left(), picRect.top(), picRect.right(), picRect.bottom());
        graphics.fill(bgLeft, bgTop, bgRight, bgBottom, 0xB0000000);
        graphics.drawString(font, label, pillX, pillY, color, false);
        graphics.disableScissor();
    }

    private void buildPreviews() {
        for (CaptureStage stage : CaptureStage.values()) {
            if (!capture.has(stage)) {
                continue;
            }
            try {
                previews.put(stage, buildPreview(stage, capture.get(stage)));
            } catch (Throwable t) {
                LOGGER.warn("Couldn't build the {} preview", stage, t);
            }
        }
    }

    private Preview buildPreview(CaptureStage stage, NativeImage source) {
        long start = System.nanoTime();
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        int[] size = Downscale.fitWithin(srcW, srcH, PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT);
        int w = size[0];
        int h = size[1];
        int[] pixels = Downscale.box(source::getPixelRGBA, srcW, srcH, w, h);
        if (stage == CaptureStage.WORLD) {
            worldPreviewPixels = pixels;
        } else if (stage == CaptureStage.FINAL) {
            finalPreviewPixels = pixels;
        }

        NativeImage small = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        DynamicTexture texture;
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    small.setPixelRGBA(x, y, pixels[y * w + x]);
                }
            }
            texture = new DynamicTexture(small);
        } catch (Throwable t) {
            small.close();
            throw t;
        }
        texture.setFilter(true, false);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ScreenshotUtilities.MOD_ID, "popup_preview/" + nextTextureId++);
        // The texture manager closes the texture, and with it the image, on release.
        minecraft.getTextureManager().register(id, texture);
        LOGGER.debug("Preview {}x{} built in {} ms", w, h, (System.nanoTime() - start) / 1_000_000);
        return new Preview(id, w, h, srcW, srcH);
    }

    private void releasePreviews() {
        for (Preview preview : previews.values()) {
            minecraft.getTextureManager().release(preview.id());
        }
        previews.clear();
        worldPreviewPixels = null;
        finalPreviewPixels = null;
        if (composedPreview != null) {
            minecraft.getTextureManager().release(composedPreview.preview().id());
            composedPreview = null;
        }
        composedPreviewDirty = true;
        composedReady = false;
        previewGeneration++;
        releaseSharpTile();
        sharpPicture = null;
        sharpLastWanted = null;
        sharpGeneration++;
    }

    private void releaseLease() {
        if (leaseHeld) {
            leaseHeld = false;
            capture.release();
        }
    }

    @Override
    public void removed() {
        if (!openingSettings) {
            pickGeneration++;
        }
        releasePreviews();
        if (!openingSettings) {
            releaseLease();
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
