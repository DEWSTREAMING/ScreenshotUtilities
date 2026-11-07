package com.screenshotutilities.capture;

import com.screenshotutilities.mixin.ChatComponentAccessor;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;

/**
 * Keeps chat lines that arrived after the first shot out of a shader retake, so the retake doesn't show the first shot's
 * own "Saved screenshot" line (or anything Iris said while switching). The game is paused, so the lines that were there
 * still show. Render thread only.
 */
public final class RetakeChat {
    /** Lines taken out, and how many lines the chat had right after, so lines that came in since go back above them. */
    public record Hidden(List<GuiMessage> lines, int sizeAfter) {
    }

    private RetakeChat() {
    }

    @Nullable
    static GuiMessage newest() {
        List<GuiMessage> all = messages();
        return all.isEmpty() ? null : all.get(0);
    }

    /** Takes out every line newer than {@code shot}'s newest. Null when there was nothing to take out. */
    @Nullable
    public static Hidden hideNewerThan(Capture shot) {
        if (!shot.chatMarkerKnown()) {
            return null;
        }
        List<GuiMessage> all = messages();
        GuiMessage marker = shot.chatMarker();
        int newer = all.size();
        if (marker != null) {
            newer = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i) == marker) {
                    newer = i;
                    break;
                }
            }
            if (newer < 0) {
                // Chat was cleared or that line scrolled out of the 100 kept, so there is no telling what is new.
                return null;
            }
        }
        if (newer == 0) {
            return null;
        }
        List<GuiMessage> taken = new ArrayList<>(all.subList(0, newer));
        all.subList(0, newer).clear();
        chat().rescaleChat();
        return new Hidden(taken, all.size());
    }

    public static void putBack(@Nullable Hidden hidden) {
        if (hidden == null) {
            return;
        }
        List<GuiMessage> all = messages();
        int arrivedSince = Math.max(0, Math.min(all.size(), all.size() - hidden.sizeAfter()));
        all.addAll(arrivedSince, hidden.lines());
        while (all.size() > 100) {
            all.remove(all.size() - 1);
        }
        chat().rescaleChat();
    }

    private static ChatComponent chat() {
        return Minecraft.getInstance().gui.getChat();
    }

    private static List<GuiMessage> messages() {
        return ((ChatComponentAccessor) chat()).screenshotutilities$allMessages();
    }
}
