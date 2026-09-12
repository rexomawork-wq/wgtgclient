package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Comparator;

public final class WgtgMessageFormatting {
    private WgtgMessageFormatting() {}

    public static ArrayList<TLRPC.MessageEntity> boldUnformatted(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (text == null || text.isEmpty()) {
            return entities;
        }
        ArrayList<TLRPC.MessageEntity> result = entities == null ? new ArrayList<>() : new ArrayList<>(entities);
        result.sort(Comparator.comparingInt(entity -> entity.offset));
        int cursor = 0;
        int count = result.size();
        // Entity offsets and String.length() both use UTF-16. Never overlap existing entities,
        // including code/pre blocks, links, custom emoji, or explicit user formatting.
        for (int i = 0; i <= count; i++) {
            TLRPC.MessageEntity entity = i < count ? result.get(i) : null;
            int start = entity == null ? text.length() : Math.max(0, Math.min(text.length(), entity.offset));
            if (start > cursor) {
                TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
                bold.offset = cursor;
                bold.length = start - cursor;
                result.add(bold);
            }
            if (entity != null) {
                cursor = (int) Math.max(cursor, Math.min(text.length(), (long) entity.offset + entity.length));
            }
        }
        result.sort(Comparator.comparingInt(entity -> entity.offset));
        return result;
    }
}
