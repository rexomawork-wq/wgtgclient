package org.telegram.messenger;

import android.content.SharedPreferences;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

public final class WgtgArchive {
    private static SharedPreferences deletedPrefs(int account) { return ApplicationLoader.applicationContext.getSharedPreferences("wgtg_deleted_" + account, 0); }

    public static boolean isDeleted(int account, long dialog, int id) {
        return deletedPrefs(account).getBoolean(dialog + "_" + id, false);
    }

    static long channelId(int account, long dialog, int id) {
        return dialog < 0 && deletedPrefs(account).getLong("dialog_" + id, 0) != dialog ? -dialog : 0;
    }

    static String preservedIdsSql(int account, long dialog) {
        String prefix = dialog + "_";
        StringBuilder ids = new StringBuilder("0");
        for (String key : deletedPrefs(account).getAll().keySet()) {
            if (key.startsWith(prefix)) {
                try {
                    int id = Integer.parseInt(key.substring(prefix.length()));
                    if (id > 0) ids.append(',').append(id);
                } catch (NumberFormatException ignored) {}
            }
        }
        return ids.toString();
    }

    public static ArrayList<Integer> excludingDeleted(int account, long dialog, ArrayList<Integer> ids) {
        ArrayList<Integer> result = new ArrayList<>();
        for (int id : ids) if (!isDeleted(account, dialog, id)) result.add(id);
        return result;
    }

    // Called on the storage queue, after the database deletion has succeeded.
    static void forget(int account, long dialog, int id) throws Exception {
        SharedPreferences deleted = deletedPrefs(account);
        SharedPreferences.Editor editor = deleted.edit().remove(dialog + "_" + id);
        if (deleted.getLong("dialog_" + id, 0) == dialog) {
            editor.remove("0_" + id).remove("dialog_" + id);
        }
        JSONArray archive = new JSONArray(prefs(account).getString("entries", "[]"));
        for (int i = archive.length() - 1; i >= 0; i--) {
            JSONObject item = archive.getJSONObject(i);
            if (item.optLong("dialog") == dialog && item.optInt("mid") == id) {
                String path = item.optString("path");
                if (!path.isEmpty()) {
                    File file = new File(path);
                    if (file.exists() && !file.delete()) throw new IllegalStateException("Cannot delete archived media");
                }
                archive.remove(i);
            }
        }
        if (!prefs(account).edit().putString("entries", archive.toString()).commit() || !editor.commit()) {
            throw new IllegalStateException("Cannot persist local deletion");
        }
    }

    static void resetDeleted(int account) { deletedPrefs(account).edit().clear().commit(); }

    public static class Entry { public long row; public String text, path, mime; }
    private static SharedPreferences prefs(int account) { return ApplicationLoader.applicationContext.getSharedPreferences("wgtg_archive_" + account, 0); }
    private static File directory(int account) {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "wgtg_archive/" + account);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create archive");
        return dir;
    }

    public static void capture(int account, long dialog, ArrayList<Integer> ids) {
        if (!WgtgConfig.preserveDeleted(account) || ids == null || ids.isEmpty()) return;
        try {
            JSONArray archive = new JSONArray(prefs(account).getString("entries", "[]"));
            for (int id : ids) {
                SQLiteCursor cursor = MessagesStorage.getInstance(account).getDatabase().queryFinalized(
                    "SELECT uid, data, ttl FROM messages_v2 WHERE mid = ? AND " + (dialog == 0 ? "is_channel = 0" : "uid = " + dialog), id);
                try {
                    while (cursor.next()) {
                        long did = cursor.longValue(0);
                        NativeByteBuffer buffer = cursor.byteBufferValue(1);
                        if (buffer == null) continue;
                        TLRPC.Message message = null;
                        try {
                            message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false);
                            if (message != null) message.readAttachPath(buffer, UserConfig.getInstance(account).getClientUserId());
                        } finally { buffer.reuse(); }
                        if (message == null || id <= 0 || message.action != null || cursor.intValue(2) != 0
                            || message.ttl != 0 || message.ttl_period != 0 || message.expire_date != 0 || MessageObject.isEphemeral(message)
                            || (message.media != null && message.media.ttl_seconds != 0) || DialogObject.isEncryptedDialog(did)) continue;
                        SharedPreferences.Editor deleted = deletedPrefs(account).edit().putBoolean(did + "_" + id, true);
                        if (message.peer_id == null || message.peer_id.channel_id == 0) {
                            deleted.putBoolean("0_" + id, true).putLong("dialog_" + id, did);
                        }
                        if (!deleted.commit()) throw new IllegalStateException("Cannot persist deleted message marker");
                        boolean duplicate = false;
                        for (int n = 0; n < archive.length(); n++) if (archive.getJSONObject(n).optLong("dialog") == did && archive.getJSONObject(n).optInt("mid") == id) { duplicate = true; break; }
                        if (duplicate) continue;
                        TLRPC.Document document = MessageObject.getDocument(message);
                        String path = "";
                        File source = FileLoader.getInstance(account).getPathToMessage(message);
                        if (source.isFile() && source.length() > 0 && (document == null || source.length() >= document.size)) {
                            File target = new File(directory(account), did + "_" + id + ".media");
                            try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
                                byte[] bytes = new byte[65536]; int count;
                                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                                output.getFD().sync(); path = target.getAbsolutePath();
                            } catch (Exception e) { target.delete(); FileLog.e(e); }
                        }
                        String text = (message.message == null ? "" : message.message);
                        if (document != null) text += "\n" + FileLoader.getDocumentFileName(document);
                        if (path.isEmpty() && message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)) text += "\n" + LocaleController.getString(R.string.WgtgNoMedia);
                        JSONObject item = new JSONObject(); item.put("row", System.currentTimeMillis()); item.put("dialog", did); item.put("mid", id); item.put("text", text); item.put("path", path); item.put("mime", document == null ? "application/octet-stream" : document.mime_type);
                        archive.put(item);
                    }
                } finally { cursor.dispose(); }
            }
            prefs(account).edit().putString("entries", archive.toString()).apply();
        } catch (Exception e) { FileLog.e(e); }
    }

    public static ArrayList<Entry> load(int account, long before) {
        ArrayList<Entry> result = new ArrayList<>();
        try {
            JSONArray archive = new JSONArray(prefs(account).getString("entries", "[]"));
            for (int i = archive.length() - 1; i >= 0 && result.size() < 50; i--) {
                JSONObject item = archive.getJSONObject(i); if (item.optLong("row") >= before) continue;
                Entry entry = new Entry(); entry.row = item.optLong("row"); entry.text = item.optString("text"); entry.path = item.optString("path"); entry.mime = item.optString("mime"); result.add(entry);
            }
        } catch (Exception e) { FileLog.e(e); }
        return result;
    }

    public static void clear(int account) {
        File dir = directory(account); File[] files = dir.listFiles();
        if (files != null) for (File file : files) if (!file.delete()) throw new IllegalStateException("Cannot delete archive file");
        prefs(account).edit().clear().apply();
    }
}
