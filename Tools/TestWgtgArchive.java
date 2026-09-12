import com.sun.source.util.JavacTask;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.*;

// Run from the repository root: java Tools/TestWgtgArchive.java
// Exercises the production archive with small Android/SQLite substitutes, not an emulator.
class TestWgtgArchive {
    static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return text; }
        };
    }

    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path root = Path.of("TMessagesProj/src/main/java/org/telegram");
        List<String> paths = List.of("messenger/WgtgArchive.java", "messenger/WgtgConfig.java", "messenger/MessagesController.java",
            "messenger/MessagesStorage.java", "ui/ChatActivity.java", "ui/Cells/ChatMessageCell.java");
        for (String locale : List.of("values", "values-ru")) {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                Path.of("TMessagesProj/src/main/res", locale, "wgtg_deleted_messages.xml").toFile());
        }
        System.out.println("New XML resources: both locales passed");
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask parse = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(paths.stream().map(root::resolve).toList()));
            parse.parse();
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(d.toString());
            }
            System.out.println("Java syntax: all six changed production files passed");
            ArrayList<JavaFileObject> sources = new ArrayList<>();
            manager.getJavaFileObjectsFromPaths(List.of(root.resolve(paths.get(0)), root.resolve(paths.get(1)))).forEach(sources::add);
            sources.add(source("android.content.SharedPreferences", """
                package android.content;
                import java.util.*;
                public class SharedPreferences {
                    public static final Map<String, Map<String,Object>> disk = new HashMap<>();
                    private final String name;
                    private final Map<String,Object> values;
                    public SharedPreferences(String name) { this.name = name; values = new HashMap<>(disk.getOrDefault(name, Map.of())); }
                    public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
                    public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
                    public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
                    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
                    public Map<String,Object> getAll() { return values; }
                    public Editor edit() { return new Editor(); }
                    public class Editor {
                        public Editor putBoolean(String k, boolean v) { values.put(k,v); return this; }
                        public Editor putInt(String k, int v) { values.put(k,v); return this; }
                        public Editor putLong(String k, long v) { values.put(k,v); return this; }
                        public Editor putString(String k, String v) { values.put(k,v); return this; }
                        public Editor remove(String k) { values.remove(k); return this; }
                        public Editor clear() { values.clear(); return this; }
                        public boolean commit() { disk.put(name, new HashMap<>(values)); return true; }
                        public void apply() { commit(); }
                    }
                }
                """));
            sources.add(source("org.json.JSONObject", """
                package org.json;
                public class JSONObject extends java.util.HashMap<String,Object> {
                    public long optLong(String k) { return ((Number)getOrDefault(k,0)).longValue(); }
                    public int optInt(String k) { return ((Number)getOrDefault(k,0)).intValue(); }
                    public String optString(String k) { return String.valueOf(getOrDefault(k,"")); }
                }
                """));
            sources.add(source("org.json.JSONArray", """
                package org.json;
                import java.util.*;
                public class JSONArray {
                    static final Map<String,List<JSONObject>> serialized = new HashMap<>();
                    final List<JSONObject> items;
                    public JSONArray(String value) { items = new ArrayList<>(serialized.getOrDefault(value,List.of())); }
                    public int length() { return items.size(); }
                    public JSONObject getJSONObject(int i) { return items.get(i); }
                    public void put(JSONObject o) { items.add(o); }
                    public void remove(int i) { items.remove(i); }
                    public String toString() { String k = "json" + serialized.size(); serialized.put(k,new ArrayList<>(items)); return k; }
                }
                """));
            sources.add(source("org.telegram.tgnet.TLRPC", """
                package org.telegram.tgnet;
                public class TLRPC {
                    public static class Message {
                        public int ttl, ttl_period, expire_date, id;
                        public boolean out;
                        public Object action;
                        public Peer peer_id = new Peer();
                        public Media media;
                        public String message = "saved text";
                        public static Message TLdeserialize(NativeByteBuffer b, int constructor, boolean strict) { return b.message; }
                        public void readAttachPath(NativeByteBuffer b, long user) {}
                    }
                    public static class Peer { public long channel_id; }
                    public static class Document { public long size; public String mime_type; }
                    public static class Media { public int ttl_seconds; }
                    public static class TL_messageMediaEmpty extends Media {}
                }
                """));
            sources.add(source("org.telegram.tgnet.NativeByteBuffer", """
                package org.telegram.tgnet;
                public class NativeByteBuffer {
                    public final TLRPC.Message message;
                    public NativeByteBuffer(TLRPC.Message m) { message = m; }
                    public int readInt32(boolean strict) { return 0; }
                    public void reuse() {}
                }
                """));
            sources.add(source("org.telegram.SQLite.SQLiteCursor", """
                package org.telegram.SQLite;
                import org.telegram.tgnet.*;
                public class SQLiteCursor {
                    final long dialog; final TLRPC.Message message; final int ttl; boolean read;
                    public SQLiteCursor(long d, TLRPC.Message m, int t) { dialog=d; message=m; ttl=t; }
                    public boolean next() { if(read || message==null) return false; read=true; return true; }
                    public long longValue(int i) { return dialog; }
                    public int intValue(int i) { return ttl; }
                    public NativeByteBuffer byteBufferValue(int i) { return new NativeByteBuffer(message); }
                    public void dispose() {}
                }
                """));
            sources.add(source("org.telegram.messenger.ArchiveTest", """
                package org.telegram.messenger;
                import java.io.*;
                import java.util.*;
                import org.telegram.tgnet.*;
                import org.telegram.SQLite.*;
                import android.content.SharedPreferences;
                class ApplicationLoader {
                    static Context applicationContext = new Context();
                    static File getFilesDirFixed() { return new File(System.getProperty("java.io.tmpdir")); }
                    static class Context {
                        Map<String,SharedPreferences> cache = new HashMap<>();
                        SharedPreferences getSharedPreferences(String name, int mode) { return cache.computeIfAbsent(name,SharedPreferences::new); }
                    }
                }
                class MessagesStorage {
                    static final MessagesStorage instance = new MessagesStorage();
                    static long dialog = 7; static int ttl; static TLRPC.Message message;
                    static MessagesStorage getInstance(int account) { return instance; }
                    MessagesStorage getDatabase() { return this; }
                    SQLiteCursor queryFinalized(String sql, int id) { return new SQLiteCursor(dialog,message,ttl); }
                }
                class UserConfig { static UserConfig getInstance(int a) { return new UserConfig(); } long getClientUserId() { return 1; } }
                class DialogObject { static boolean isEncryptedDialog(long d) { return d == (1L << 32); } }
                class MessageObject {
                    static TLRPC.Document getDocument(TLRPC.Message m) { return null; }
                    static boolean isEphemeral(TLRPC.Message m) { return m.id == 999; }
                }
                class FileLoader {
                    static FileLoader getInstance(int a) { return new FileLoader(); }
                    File getPathToMessage(TLRPC.Message m) { return new File("/nonexistent-wgtg-test-media"); }
                    static String getDocumentFileName(TLRPC.Document d) { return "file"; }
                }
                class FileLog { static void e(Exception e) { throw new AssertionError(e); } }
                class LocaleController { static String getString(int id) { return "missing media"; } }
                class R { static class string { static final int WgtgNoMedia = 1; } }
                public class ArchiveTest {
                    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
                    static void capture(int id) { WgtgArchive.capture(0,0,new ArrayList<>(List.of(id))); }
                    public static void run() throws Exception {
                        MessagesStorage.message = new TLRPC.Message();
                        capture(10);
                        check(!WgtgArchive.isDeleted(0,7,10), "disabled setting");
                        WgtgConfig.setPreserveDeleted(0,true);
                        capture(10);
                        check(WgtgArchive.isDeleted(0,7,10) && WgtgArchive.isDeleted(0,0,10), "private message and global alias");
                        check(!WgtgArchive.isDeleted(1,7,10) && !WgtgArchive.isDeleted(0,8,10), "account and dialog isolation");
                        ApplicationLoader.applicationContext = new ApplicationLoader.Context();
                        check(WgtgArchive.isDeleted(0,7,10), "marker survives preferences reload");
                        capture(10);
                        check(WgtgArchive.load(0,Long.MAX_VALUE).size()==1, "duplicate server update");
                        MessagesStorage.message.out = true;
                        capture(11);
                        check(WgtgArchive.isDeleted(0,7,11), "outgoing remotely deleted message");
                        for(int kind=0; kind<8; kind++) {
                            MessagesStorage.message = new TLRPC.Message(); MessagesStorage.dialog=7; MessagesStorage.ttl=0;
                            switch(kind) {
                                case 0 -> MessagesStorage.message.ttl=5;
                                case 1 -> MessagesStorage.message.ttl_period=5;
                                case 2 -> { MessagesStorage.message.media=new TLRPC.Media(); MessagesStorage.message.media.ttl_seconds=Integer.MAX_VALUE; }
                                case 3 -> MessagesStorage.dialog=1L<<32;
                                case 4 -> MessagesStorage.ttl=5;
                                case 5 -> MessagesStorage.message.expire_date=100;
                                case 6 -> MessagesStorage.message.id=999;
                                case 7 -> MessagesStorage.message.action=new Object();
                            }
                            capture(20+kind);
                            check(!WgtgArchive.isDeleted(0,MessagesStorage.dialog,20+kind), "excluded message kind " + kind);
                        }
                        MessagesStorage.message = new TLRPC.Message(); MessagesStorage.dialog=-42; MessagesStorage.ttl=0;
                        MessagesStorage.message.peer_id.channel_id=42;
                        WgtgArchive.capture(0,-42,new ArrayList<>(List.of(10)));
                        check(WgtgArchive.channelId(0,-42,10)==42 && WgtgArchive.channelId(0,7,10)==0, "local event channel routing without cached chat");
                        check(WgtgArchive.preservedIdsSql(0,-42).equals("0,10"), "channel overwrite exclusion");
                        WgtgArchive.forget(0,-42,10);
                        check(WgtgArchive.isDeleted(0,7,10) && WgtgArchive.isDeleted(0,0,10), "channel removal preserves colliding private ID");
                        var ids = new ArrayList<>(List.of(10,11,12));
                        check(WgtgArchive.excludingDeleted(0,0,ids).equals(List.of(12)) && ids.size()==3, "mixed batch filtering without mutation");
                        WgtgConfig.setPreserveDeleted(0,false);
                        check(WgtgArchive.isDeleted(0,7,10), "disabling capture preserves existing markers");
                        WgtgArchive.forget(0,7,10);
                        ApplicationLoader.applicationContext = new ApplicationLoader.Context();
                        check(!WgtgArchive.isDeleted(0,7,10) && !WgtgArchive.isDeleted(0,0,10), "local removal survives reload");
                        check(WgtgArchive.load(0,Long.MAX_VALUE).size()==1, "local removal removes archive entry");
                        WgtgArchive.resetDeleted(0);
                        check(!WgtgArchive.isDeleted(0,7,11), "logout resets markers");
                        System.out.println("Archive behavior: capture, TTL exclusions, isolation, reload, duplicates, mixed deletion and cleanup passed");
                    }
                }
                """));
            Path classes = Files.createTempDirectory("wgtg-archive-test-");
            if (!compiler.getTask(null, manager, null, List.of("-d", classes.toString(), "-proc:none"), null, sources).call()) {
                throw new AssertionError("Archive compilation failed");
            }
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
                loader.loadClass("org.telegram.messenger.ArchiveTest").getMethod("run").invoke(null);
            }
        }
    }
}
