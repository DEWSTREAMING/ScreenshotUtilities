package com.screenshotutilities.clipboard;

import com.mojang.logging.LogUtils;
import com.sun.jna.IntegerType;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Locale;
import javax.annotation.Nullable;
import org.slf4j.Logger;

/**
 * Places a {@code CF_DIB} byte array on the Windows clipboard through JNA.
 *
 * <p>JNA interfaces live in an inner class that is only touched on Windows, and loading is guarded so a missing library or
 * non-Windows OS disables clipboard support cleanly instead of crashing.</p>
 */
public final class WindowsClipboard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CF_DIB = 8;
    private static final int GMEM_MOVEABLE = 0x0002;
    private static final long RETRY_TIMEOUT_MS = 500;
    private static final long RETRY_SLEEP_MS = 25;

    private static final boolean SUPPORTED = detectSupport();

    private WindowsClipboard() {
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }

    private static boolean detectSupport() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("win")) {
            return false;
        }
        try {
            return NativeBindings.USER32 != null && NativeBindings.KERNEL32 != null;
        } catch (Throwable t) {
            LOGGER.warn("Windows clipboard bindings unavailable", t);
            return false;
        }
    }

    /**
     * Copies a {@code CF_DIB} buffer to the Windows clipboard. Must be called from a worker thread because it retries briefly
     * if another program is holding the clipboard open.
     */
    public static boolean setDib(byte[] dib) {
        if (!SUPPORTED || dib.length == 0) {
            return false;
        }
        try {
            return NativeBindings.writeDib(dib);
        } catch (Throwable t) {
            LOGGER.warn("Clipboard write failed", t);
            return false;
        }
    }

    /** Pointer-sized unsigned integer for {@code GlobalAlloc}'s {@code SIZE_T dwBytes} parameter. */
    public static final class SizeT extends IntegerType {
        public SizeT() {
            this(0L);
        }

        public SizeT(long value) {
            super(Native.SIZE_T_SIZE, value, true);
        }
    }

    private static final class NativeBindings {
        static final User32 USER32 = Native.load("user32", User32.class);
        static final Kernel32 KERNEL32 = Native.load("kernel32", Kernel32.class);

        interface User32 extends StdCallLibrary {
            boolean OpenClipboard(@Nullable Pointer hWndNewOwner);

            boolean EmptyClipboard();

            @Nullable
            Pointer SetClipboardData(int uFormat, Pointer hMem);

            boolean CloseClipboard();
        }

        interface Kernel32 extends StdCallLibrary {
            @Nullable
            Pointer GlobalAlloc(int uFlags, SizeT dwBytes);

            @Nullable
            Pointer GlobalLock(Pointer hMem);

            boolean GlobalUnlock(Pointer hMem);

            @Nullable
            Pointer GlobalFree(Pointer hMem);
        }

        static boolean writeDib(byte[] dib) {
            Pointer hMem = KERNEL32.GlobalAlloc(GMEM_MOVEABLE, new SizeT(dib.length));
            if (hMem == null) {
                return false;
            }
            Pointer locked = KERNEL32.GlobalLock(hMem);
            if (locked == null) {
                KERNEL32.GlobalFree(hMem);
                return false;
            }
            try {
                locked.write(0, dib, 0, dib.length);
            } finally {
                KERNEL32.GlobalUnlock(hMem);
            }

            if (!openClipboard()) {
                KERNEL32.GlobalFree(hMem);
                return false;
            }
            boolean handedOff = false;
            try {
                if (USER32.EmptyClipboard()) {
                    handedOff = USER32.SetClipboardData(CF_DIB, hMem) != null;
                }
            } finally {
                USER32.CloseClipboard();
                // Windows owns the handle once SetClipboardData succeeds; free it only on failure.
                if (!handedOff) {
                    KERNEL32.GlobalFree(hMem);
                }
            }
            return handedOff;
        }

        private static boolean openClipboard() {
            long deadline = System.nanoTime() + RETRY_TIMEOUT_MS * 1_000_000L;
            while (true) {
                if (USER32.OpenClipboard(null)) {
                    return true;
                }
                if (System.nanoTime() >= deadline) {
                    return false;
                }
                try {
                    Thread.sleep(RETRY_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }
}
