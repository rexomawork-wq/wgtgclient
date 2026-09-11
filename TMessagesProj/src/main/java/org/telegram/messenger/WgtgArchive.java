package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

/** Access only on the account's storage queue, before normal remote deletion. */
public final class WgtgArchive {
    public static class Entry {
        public long row;
        public String text, path, mime;
    }

    private static File directory(int account) {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "wgtg_archive/" + account);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Cannot create archive");
        return dir;
    }

    private static SQLiteDatabase open(int account) {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory(account), "archive.db"), null);
        db.execSQL("CREATE TABLE IF NOT EXISTS entries (id INTEGER PRIMARY KEY AUTOINCREMENT, dialog INTEGER, mid INTEGER, text TEXT, path TEXT, mime TEXT, UNIQUE(dialog, mid))");
        return db;
    }

    public static void capture(int account, long dialog, ArrayList<Integer> ids) {
        if (!WgtgConfig.preserveDeleted(account) || ids.isEmpty()) return;
        try (SQLiteDatabase archive = open(account)) {
            for (int id : ids) {
                SQLiteCursor cursor = MessagesStorage.getInstance(account).getDatabase().queryFinalized(
                    "SELECT uid, data FROM messages_v2 WHERE mid = ? AND " + (dialog == 0 ? "is_channel = 0" : "uid = " + dialog), id);
                try {
                    while (cursor.next()) {
                        long did = cursor.longValue(0);
                        if (DialogObject.isEncryptedDialog(did)) continue;
                        NativeByteBuffer buffer = cursor.byteBufferValue(1);
                        if (buffer == null) continue;
                        TLRPC.Message message;
                        try {
                            message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false);
                            if (message != null) message.readAttachPath(buffer, UserConfig.getInstance(account).getClientUserId());
                        } finally { buffer.reuse(); }
                        if (message == null || message.out || message.action != null || message.ttl_period != 0 ||
                            (message.media != null && message.media.ttl_seconds != 0)) continue;
                        try (Cursor existing = archive.rawQuery("SELECT id FROM entries WHERE dialog=? AND mid=?", new String[]{"" + did, "" + id})) {
                            if (existing.moveToFirst()) continue;
                        }
                        TLRPC.Document document = MessageObject.getDocument(message);
                        String mime = document != null ? document.mime_type : "image/jpeg";
                        String path = "";
                        File source = FileLoader.getInstance(account).getPathToMessage(message);
                        if (message.attachPath != null && !message.attachPath.isEmpty() && new File(message.attachPath).isFile()) source = new File(message.attachPath);
                        if (source.isFile() && source.length() > 0 && (document == null || source.length() >= document.size)) {
                            File target = new File(directory(account), did + "_" + id + (document == null ? ".jpg" : ".media"));
                            try {
                                try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
                                    byte[] bytes = new byte[65536];
                                    int count;
                                    while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                                    output.getFD().sync();
                                }
                                path = target.getAbsolutePath();
                            } catch (Exception e) { target.delete(); FileLog.e(e); }
                        }
                        long sender = message.from_id == null ? did : MessageObject.getPeerId(message.from_id);
                        MessagesController controller = MessagesController.getInstance(account);
                        TLRPC.User user = sender > 0 ? controller.getUser(sender) : null;
                        TLRPC.Chat chat = sender < 0 ? controller.getChat(-sender) : null;
                        String name = user != null ? UserObject.getUserName(user) : chat != null ? chat.title : Long.toString(sender);
                        String text = name + " [" + did + "]\n" + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(message.date * 1000L)) + "\n" + (message.message == null ? "" : message.message);
                        if (document != null) text += "\n" + FileLoader.getDocumentFileName(document) + " (" + mime + ")";
                        if (path.isEmpty() && message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)) text += "\n" + LocaleController.getString(R.string.WgtgNoMedia);
                        ContentValues values = new ContentValues();
                        values.put("dialog", did); values.put("mid", id); values.put("text", text); values.put("path", path); values.put("mime", mime);
                        archive.insertOrThrow("entries", null, values);
                    }
                } finally { cursor.dispose(); }
            }
        } catch (Exception e) { FileLog.e(e); }
    }

    public static ArrayList<Entry> load(int account, long before) {
        ArrayList<Entry> entries = new ArrayList<>();
        try (SQLiteDatabase db = open(account); Cursor cursor = db.rawQuery("SELECT id,text,path,mime FROM entries WHERE id < ? ORDER BY id DESC LIMIT 50", new String[]{"" + before})) {
            while (cursor.moveToNext()) {
                Entry entry = new Entry();
                entry.row = cursor.getLong(0); entry.text = cursor.getString(1); entry.path = cursor.getString(2); entry.mime = cursor.getString(3);
                entries.add(entry);
            }
        }
        return entries;
    }

    public static void clear(int account) {
        File[] files = directory(account).listFiles();
        if (files != null) for (File file : files) {
            if (!file.delete()) throw new IllegalStateException("Cannot delete archive file");
        }
    }
}
