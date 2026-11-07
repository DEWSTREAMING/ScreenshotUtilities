package com.screenshotutilities.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.screenshotutilities.image.ZoomView.Box;
import com.screenshotutilities.image.ZoomView.Thumb;
import org.junit.jupiter.api.Test;

class ZoomViewTest {
    /** A 16:9 picture filling a 16:9 area. */
    private static final Box AREA = new Box(10, 22, 330, 202);
    private static final Box BASE = new Box(10, 22, 330, 202);
    /** A tall area, so a 16:9 picture is thinner than it across. Base is centred top to bottom. */
    private static final Box TALL_AREA = new Box(0, 0, 160, 300);
    private static final Box TALL_BASE = new Box(0, 105, 160, 195);

    private static double fractionX(Box r, double x) {
        return (x - r.left()) / r.width();
    }

    private static double fractionY(Box r, double y) {
        return (y - r.top()) / r.height();
    }

    /** The popup's own mapping, so the test checks the same thing it does. */
    private static int toImage(double screen, int rectStart, int rectSize, int source) {
        double rel = (screen - rectStart) / Math.max(1, rectSize);
        return Math.clamp((int) Math.round(rel * source), 0, source);
    }

    private static void assertNoGap(Box r, Box area) {
        if (r.width() >= area.width()) {
            assertTrue(r.left() <= area.left() && r.right() >= area.right(), "gap across: " + r);
        }
        if (r.height() >= area.height()) {
            assertTrue(r.top() <= area.top() && r.bottom() >= area.bottom(), "gap down: " + r);
        }
    }

    @Test
    void startsAtFitAndGivesTheBaseRectBack() {
        ZoomView view = new ZoomView();
        assertFalse(view.isZoomed());
        assertEquals(100, view.percent());
        assertEquals(BASE, view.rect(BASE, AREA));
        assertEquals(TALL_BASE, view.rect(TALL_BASE, TALL_AREA));
    }

    @Test
    void atFitAScreenPointMapsToTheSamePixelAsTheOldMaths() {
        ZoomView view = new ZoomView();
        Box base = new Box(13, 25, 311, 193);
        Box r = view.rect(base, new Box(10, 22, 320, 200));
        for (int x = base.left(); x < base.right(); x += 7) {
            assertEquals(toImage(x, base.left(), base.width(), 1920), toImage(x, r.left(), r.width(), 1920));
        }
        for (int y = base.top(); y < base.bottom(); y += 5) {
            assertEquals(toImage(y, base.top(), base.height(), 1080), toImage(y, r.top(), r.height(), 1080));
        }
    }

    @Test
    void pointUnderTheMouseStaysPut() {
        ZoomView view = new ZoomView();
        double mx = 100;
        double my = 80;
        double fx = fractionX(view.rect(BASE, AREA), mx);
        double fy = fractionY(view.rect(BASE, AREA), my);
        for (int i = 0; i < 6; i++) {
            view.zoomAt(1, mx, my, BASE, AREA);
            Box r = view.rect(BASE, AREA);
            // Within one screen unit, from rounding the rect.
            assertEquals(fx * r.width(), mx - r.left(), 1.0);
            assertEquals(fy * r.height(), my - r.top(), 1.0);
        }
        for (int i = 0; i < 3; i++) {
            view.zoomAt(-1, mx, my, BASE, AREA);
            Box r = view.rect(BASE, AREA);
            assertEquals(fx * r.width(), mx - r.left(), 1.0);
            assertEquals(fy * r.height(), my - r.top(), 1.0);
        }
    }

    @Test
    void zoomingOnACornerKeepsThatCornerInPlace() {
        ZoomView view = new ZoomView();
        view.zoomAt(4, AREA.left(), AREA.top(), BASE, AREA);
        Box r = view.rect(BASE, AREA);
        assertEquals(AREA.left(), r.left());
        assertEquals(AREA.top(), r.top());
        view.reset();
        view.zoomAt(3, AREA.right(), AREA.bottom(), BASE, AREA);
        r = view.rect(BASE, AREA);
        assertEquals(AREA.right(), r.right(), 1);
        assertEquals(AREA.bottom(), r.bottom(), 1);
    }

    @Test
    void oneNotchIsAQuarterMore() {
        ZoomView view = new ZoomView();
        view.zoomAt(1, 50, 50, BASE, AREA);
        assertEquals(1.25, view.zoom(), 1e-9);
        assertEquals(125, view.percent());
        assertEquals(Math.round(BASE.width() * 1.25), view.rect(BASE, AREA).width());
    }

    @Test
    void neverGoesBelowFitOrAboveMax() {
        ZoomView view = new ZoomView();
        view.zoomAt(-5, 50, 50, BASE, AREA);
        assertEquals(ZoomView.MIN_ZOOM, view.zoom());
        assertEquals(BASE, view.rect(BASE, AREA));
        view.zoomAt(100, 50, 50, BASE, AREA);
        assertEquals(ZoomView.MAX_ZOOM, view.zoom());
        assertEquals(800, view.percent());
        view.zoomAt(-100, 50, 50, BASE, AREA);
        assertFalse(view.isZoomed());
        assertEquals(BASE, view.rect(BASE, AREA));
    }

    @Test
    void zoomingBackToFitCentresAgain() {
        ZoomView view = new ZoomView();
        view.zoomAt(3, AREA.left() + 5, AREA.top() + 5, BASE, AREA);
        view.panBy(-40, -30, BASE, AREA);
        view.zoomAt(-3, AREA.right() - 5, AREA.bottom() - 5, BASE, AREA);
        assertEquals(BASE, view.rect(BASE, AREA));
    }

    @Test
    void clampsAtEveryEdge() {
        ZoomView view = new ZoomView();
        view.zoomAt(5, 150, 100, BASE, AREA);
        view.panBy(10_000, 10_000, BASE, AREA);
        Box r = view.rect(BASE, AREA);
        assertEquals(AREA.left(), r.left());
        assertEquals(AREA.top(), r.top());
        view.panBy(-10_000, -10_000, BASE, AREA);
        r = view.rect(BASE, AREA);
        assertEquals(AREA.right(), r.right(), 1);
        assertEquals(AREA.bottom(), r.bottom(), 1);
        assertNoGap(r, AREA);
    }

    @Test
    void panMovesByTheScreenDistance() {
        ZoomView view = new ZoomView();
        view.zoomAt(6, 170, 112, BASE, AREA);
        Box before = view.rect(BASE, AREA);
        view.panBy(12, -7, BASE, AREA);
        Box after = view.rect(BASE, AREA);
        assertEquals(before.left() + 12, after.left(), 1);
        assertEquals(before.top() - 7, after.top(), 1);
    }

    @Test
    void panDoesNothingAtFit() {
        ZoomView view = new ZoomView();
        view.panBy(50, 50, BASE, AREA);
        assertEquals(BASE, view.rect(BASE, AREA));
    }

    @Test
    void pictureThinnerThanTheAreaStaysCentredThatWay() {
        ZoomView view = new ZoomView();
        // 1.25: 112.5 tall in a 300 tall area, still thinner, so centred. 200 wide in 160, so it can move across.
        view.zoomAt(1, 20, 150, TALL_BASE, TALL_AREA);
        Box r = view.rect(TALL_BASE, TALL_AREA);
        assertEquals(TALL_AREA.top() + (TALL_AREA.height() - r.height()) / 2.0, r.top(), 1);
        assertNoGap(r, TALL_AREA);
        view.panBy(0, 500, TALL_BASE, TALL_AREA);
        assertEquals(r.top(), view.rect(TALL_BASE, TALL_AREA).top());
        assertTrue(view.canScrollX(TALL_BASE, TALL_AREA));
        assertFalse(view.canScrollY(TALL_BASE, TALL_AREA));
        // At 4 times it is 360 tall, taller than the area, so now it clamps to the edges.
        view.zoomAt(Math.log(4 / 1.25) / Math.log(ZoomView.STEP), 20, 150, TALL_BASE, TALL_AREA);
        r = view.rect(TALL_BASE, TALL_AREA);
        assertNoGap(r, TALL_AREA);
        assertTrue(view.canScrollY(TALL_BASE, TALL_AREA));
    }

    @Test
    void areaChangingSizeKeepsThePlaceAndClampsAgain() {
        ZoomView view = new ZoomView();
        view.zoomAt(5, 250, 150, BASE, AREA);
        Box r = view.rect(BASE, AREA);
        double middleX = fractionX(r, AREA.left() + AREA.width() / 2.0);
        double middleY = fractionY(r, AREA.top() + AREA.height() / 2.0);

        // A larger window: a larger area and a larger base, same zoom.
        Box bigArea = new Box(10, 22, 650, 382);
        Box bigBase = new Box(10, 22, 650, 382);
        Box big = view.rect(bigBase, bigArea);
        assertEquals(middleX, fractionX(big, bigArea.left() + bigArea.width() / 2.0), 0.01);
        assertEquals(middleY, fractionY(big, bigArea.top() + bigArea.height() / 2.0), 0.01);
        assertNoGap(big, bigArea);

        // The tool row wraps: the area loses height but the place is kept, then clamped.
        Box shortArea = new Box(10, 22, 330, 150);
        Box shortBase = new Box(42, 22, 298, 166);
        Box s = view.rect(shortBase, shortArea);
        assertNoGap(s, shortArea);
    }

    @Test
    void visibleIsTheZoomedRectInsideTheArea() {
        ZoomView view = new ZoomView();
        assertEquals(TALL_BASE, view.visible(TALL_BASE, TALL_AREA));
        view.zoomAt(8, 80, 150, TALL_BASE, TALL_AREA);
        assertEquals(TALL_AREA, view.visible(TALL_BASE, TALL_AREA));
    }

    @Test
    void resetGoesBackToFit() {
        ZoomView view = new ZoomView();
        view.zoomAt(4, 60, 60, BASE, AREA);
        view.reset();
        assertFalse(view.isZoomed());
        assertEquals(BASE, view.rect(BASE, AREA));
    }

    @Test
    void thumbFollowsTheViewAndDraggingItMovesTheView() {
        ZoomView view = new ZoomView();
        view.zoomAt(Math.log(2) / Math.log(ZoomView.STEP), AREA.left(), AREA.top(), BASE, AREA);
        assertEquals(2.0, view.zoom(), 1e-9);
        Thumb t = view.thumbX(BASE, AREA, AREA.left(), AREA.width());
        // At 200% the view is half of the picture, at the left edge.
        assertEquals(AREA.left(), t.start(), 0.01);
        assertEquals(AREA.width() / 2.0, t.end() - t.start(), 0.01);

        view.dragThumbX(AREA.width() / 2.0, BASE, AREA, AREA.width());
        Box r = view.rect(BASE, AREA);
        assertEquals(AREA.right(), r.right(), 1);
        t = view.thumbX(BASE, AREA, AREA.left(), AREA.width());
        assertEquals(AREA.right(), t.end(), 0.6);

        view.dragThumbY(10_000, BASE, AREA, AREA.height());
        assertEquals(AREA.bottom(), view.rect(BASE, AREA).bottom(), 1);
        view.dragThumbY(-10_000, BASE, AREA, AREA.height());
        assertEquals(AREA.top(), view.rect(BASE, AREA).top());
    }

    @Test
    void noScrollBarsAtFit() {
        ZoomView view = new ZoomView();
        assertFalse(view.canScrollX(BASE, AREA));
        assertFalse(view.canScrollY(BASE, AREA));
    }

    @Test
    void intersectOfApartBoxesIsEmpty() {
        Box a = new Box(0, 0, 10, 10);
        Box b = new Box(20, 20, 30, 30);
        assertEquals(0, a.intersect(b).width());
        assertEquals(new Box(2, 3, 10, 10), a.intersect(new Box(2, 3, 40, 40)));
    }
}
