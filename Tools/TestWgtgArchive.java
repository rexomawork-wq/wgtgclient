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
        String chat = Files.readString(root.resolve("ui/ChatActivity.java"));
        int deletionStart = chat.indexOf("private void processDeletedMessages(");
        int deletionEnd = chat.indexOf("messagesDict[loadIndex].remove(mid)", deletionStart);
        if (deletionStart < 0 || deletionEnd < 0 || chat.substring(deletionStart, deletionEnd).contains("WgtgConfig.preserveDeleted")) {
            throw new AssertionError("Local deletion must reach list/dictionary cleanup regardless of the capture setting");
        }
        if (!chat.contains("WgtgArchive.excludingDeleted(currentAccount, channelId == 0 ? 0 : -channelId, markAsDeletedMessages)")) {
            throw new AssertionError("Remote preserved messages must be filtered before list cleanup");
        }
        int eventStart = chat.indexOf("boolean scheduled = (Boolean) args[2];", chat.indexOf("} else if (id == NotificationCenter.messagesDeleted)"));
        int eventEnd = chat.indexOf("boolean update =", eventStart);
        String deletionEvent = chat.substring(eventStart, eventEnd).replace("return;", "return null;");
        String storage = Files.readString(root.resolve("messenger/MessagesStorage.java"));
        String passcodeView = Files.readString(root.resolve("ui/Components/PasscodeView.java"));
        if (!passcodeView.contains("processDone(true, session)") || !passcodeView.contains("attempt == wgtgBiometricAttempt")
                || !passcodeView.contains("WgtgPasscode.acceptBiometric(session)") || !passcodeView.contains("!isAttachedToWindow()")) {
            throw new AssertionError("Lock view must use session/attachment guards for biometric callbacks");
        }
        String baseFragment = Files.readString(root.resolve("ui/ActionBar/BaseFragment.java"));
        if (!baseFragment.contains("WgtgPrivacyUi.track(this)") || !baseFragment.contains("WgtgPrivacyUi.forget(this)")) {
            throw new AssertionError("All fragment lifetimes must participate in privacy invalidation");
        }
        String archiveUi = Files.readString(root.resolve("ui/WgtgDeletedMessagesActivity.java"));
        if (!archiveUi.contains("request != generation || !WgtgPasscode.canAccessArchive()")
                || !archiveUi.contains("WgtgArchive.clearFromUi(currentAccount)")
                || !archiveUi.contains("getParentActivity() == null || !WgtgPasscode.canAccessArchive()")) {
            throw new AssertionError("Archive callbacks, clear and media-open must recheck access");
        }
        int localStart = storage.indexOf("public ArrayList<Long> markMessagesAsDeletedLocally(");
        int localEnd = storage.indexOf("public void deletePreservedMessage(", localStart);
        String localDeletion = storage.substring(localStart, localEnd);
        List<String> paths = List.of("messenger/WgtgArchive.java", "messenger/WgtgConfig.java", "messenger/MessagesController.java",
            "messenger/MessagesStorage.java", "ui/ChatActivity.java", "ui/Cells/ChatMessageCell.java",
            "ui/WgtgDeletedMessagesActivity.java", "ui/WgtgSettingsActivity.java", "messenger/WgtgPasscode.java",
            "ui/WgtgAlternatePasscodeActivity.java", "ui/WgtgPrivacyUi.java", "ui/Components/PasscodeView.java",
            "ui/PasscodeActivity.java", "ui/ActionBar/BaseFragment.java", "ui/ActionBar/ActionBarLayout.java", "ui/Cells/DialogCell.java",
            "messenger/SharedConfig.java", "ui/BubbleActivity.java", "ui/ExternalActionActivity.java");
        for (String hook : List.of(
                "WgtgArchive.captureEdit(currentAccount, message.dialog_id, message);",
                "WgtgArchive.captureEdit(currentAccount, dialogId, message);",
                "WgtgArchive.captureEdit(currentAccount, dialog.id, message);")) {
            if (!storage.contains(hook)) throw new AssertionError("Missing storage capture: " + hook);
        }
        for (String locale : List.of("values", "values-ru")) {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                Path.of("TMessagesProj/src/main/res", locale, "wgtg_deleted_messages.xml").toFile());
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                Path.of("TMessagesProj/src/main/res", locale, "wgtg_history.xml").toFile());
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                Path.of("TMessagesProj/src/main/res", locale, "wgtg_passcode.xml").toFile());
        }
        System.out.println("New XML resources: both locales passed");
        for (String name : List.of("wgtg_passcode.xml", "wgtg_history.xml")) {
            java.util.Set<String> names = null;
            for (String locale : List.of("values", "values-ru")) {
                var nodes = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                        Path.of("TMessagesProj/src/main/res", locale, name).toFile()).getElementsByTagName("string");
                java.util.Set<String> current = new java.util.HashSet<>();
                for (int i=0;i<nodes.getLength();i++) {
                    if (!current.add(nodes.item(i).getAttributes().getNamedItem("name").getNodeValue())) throw new AssertionError("Duplicate localized key");
                }
                if (names != null && !names.equals(current)) throw new AssertionError("Locale parity: " + name);
                names=current;
            }
        }
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask parse = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(paths.stream().map(root::resolve).toList()));
            parse.parse();
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(d.toString());
            }
            System.out.println("Java syntax: all " + paths.size() + " archive/history/passcode integration files passed");
            ArrayList<JavaFileObject> sources = new ArrayList<>();
            manager.getJavaFileObjectsFromPaths(List.of(root.resolve(paths.get(0)), root.resolve(paths.get(1)))).forEach(sources::add);
            manager.getJavaFileObjectsFromPaths(List.of(root.resolve("messenger/WgtgPasscode.java"))).forEach(sources::add);
            manager.getJavaFileObjectsFromPaths(List.of(root.resolve("ui/WgtgPrivacyUi.java"))).forEach(sources::add);
            sources.add(source("android.view.View", """
                package android.view;
                public class View { public static final int INVISIBLE=4; public int visibility; public void setVisibility(int v) { visibility=v; } }
                """));
            sources.add(source("android.os.SystemClock", """
                package android.os;
                public class SystemClock { public static long now=1000; public static long elapsedRealtime() { return now; } }
                """));
            sources.add(source("org.telegram.ui.ActionBar.INavigationLayout", """
                package org.telegram.ui.ActionBar;
                public interface INavigationLayout {
                    java.util.ArrayList<BaseFragment> getFragmentStack();
                    void rebuildAllFragmentViews(boolean last, boolean show);
                }
                """));
            sources.add(source("org.telegram.ui.ActionBar.BaseFragment", """
                package org.telegram.ui.ActionBar;
                public class BaseFragment {
                    public boolean isFinished, dismissed, sheetsCleared, viewsCleared;
                    public INavigationLayout layout;
                    public android.view.View view=new android.view.View();
                    public BaseFragment() { org.telegram.ui.WgtgPrivacyUi.track(this); }
                    public INavigationLayout getParentLayout() { return layout; }
                    public android.view.View getFragmentView() { return view; }
                    public void dismissCurrentDialog() { dismissed=true; }
                    public void clearSheets() { sheetsCleared=true; }
                    public void removeSelfFromStack(boolean immediate) {
                        if (!immediate) throw new AssertionError("privacy removal must be immediate");
                        if (layout!=null) layout.getFragmentStack().remove(this);
                        isFinished=true; org.telegram.ui.WgtgPrivacyUi.forget(this);
                    }
                    public void clearViews() { viewsCleared=true; }
                }
                """));
            for (String name : List.of("DialogsActivity", "PasscodeActivity", "WgtgDeletedMessagesActivity", "WgtgAlternatePasscodeActivity")) {
                sources.add(source("org.telegram.ui."+name, "package org.telegram.ui; public class "+name+" extends org.telegram.ui.ActionBar.BaseFragment {}"));
            }
            sources.add(source("org.telegram.ui.WgtgSettingsActivity", """
                package org.telegram.ui;
                public class WgtgSettingsActivity extends org.telegram.ui.ActionBar.BaseFragment {
                    public int updates; public void updatePrivacyVisibility() { updates++; }
                }
                """));
            sources.add(source("org.telegram.messenger.MediaController", """
                package org.telegram.messenger;
                public class MediaController {
                    public static int cleanups;
                    static final MediaController instance=new MediaController();
                    public static MediaController getInstance() { return instance; }
                    public void cleanupPlayer(boolean a, boolean b) { cleanups++; }
                }
                """));
            sources.add(source("org.telegram.ui.PrivacyUiTest", """
                package org.telegram.ui;
                import org.telegram.ui.ActionBar.*;
                import org.telegram.messenger.*;
                class PhotoViewer {
                    static final PhotoViewer instance=new PhotoViewer(); static int closed;
                    static boolean hasInstance() { return true; }
                    static PhotoViewer getInstance() { return instance; }
                    boolean isVisible() { return true; }
                    void closePhoto(boolean a, boolean b) { closed++; }
                }
                public class PrivacyUiTest {
                    static class Layout implements INavigationLayout {
                        final java.util.ArrayList<BaseFragment> stack=new java.util.ArrayList<>(); int rebuilt;
                        public java.util.ArrayList<BaseFragment> getFragmentStack() { return stack; }
                        public void rebuildAllFragmentViews(boolean last, boolean show) { rebuilt++; }
                        <T extends BaseFragment> T add(T fragment) { fragment.layout=this; stack.add(fragment); return fragment; }
                    }
                    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
                    public static void run() {
                        check(WgtgPasscode.normalCodeAccepted(),"normal verification restores fixture");
                        Layout main=new Layout(), tablet=new Layout();
                        DialogsActivity root=main.add(new DialogsActivity());
                        BaseFragment chat=main.add(new BaseFragment());
                        WgtgDeletedMessagesActivity archive=main.add(new WgtgDeletedMessagesActivity());
                        WgtgSettingsActivity settings=main.add(new WgtgSettingsActivity());
                        WgtgAlternatePasscodeActivity config=main.add(new WgtgAlternatePasscodeActivity());
                        BaseFragment sideChat=tablet.add(new BaseFragment());
                        long session=WgtgPasscode.beginLock();
                        check(archive.isFinished && config.isFinished && archive.dismissed && archive.viewsCleared,"locking closes sensitive fragments even below current screen");
                        check(!chat.isFinished && !sideChat.isFinished && settings.updates>0,"normal lock leaves other screens and refreshes settings visibility");
                        check(WgtgPasscode.unlock("alternate password",session),"alternate transition");
                        check(chat.isFinished && sideChat.isFinished && settings.isFinished,"mode change clears cached screens across layouts");
                        check(chat.view.visibility==android.view.View.INVISIBLE && chat.viewsCleared,"old views hidden before rebuilding");
                        check(root.dismissed && root.sheetsCleared && main.stack.size()==1 && main.stack.get(0)==root,"root retained with dialogs and sheets dismissed");
                        check(tablet.stack.isEmpty() && main.rebuilt>0 && tablet.rebuilt>0,"all participating layouts rebuilt");
                        check(PhotoViewer.closed>0 && MediaController.cleanups>0,"media overlays stopped on mode change");
                        PasscodeActivity verify=main.add(new PasscodeActivity());
                        check(WgtgPasscode.normalCodeAccepted() && !verify.isFinished,"normal verification screen can finish its successful flow");
                        check(WgtgPasscode.unlock("alternate password",WgtgPasscode.beginLock()),"restore restricted state for process restart test");
                        check(verify.isFinished,"alternate transition also removes normal passcode management screens");
                        System.out.println("Privacy UI: production coordinator passed hidden-stack, multi-layout, immediate removal, dialog/sheet, settings refresh and media cleanup tests");
                    }
                }
                """));
            sources.add(source("android.content.SharedPreferences", """
                package android.content;
                import java.util.*;
                public class SharedPreferences {
                    public static final Map<String, Map<String,Object>> disk = new HashMap<>();
                    public static boolean failCommit;
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
                        public boolean commit() { if (failCommit) return false; disk.put(name, new HashMap<>(values)); return true; }
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
                        public int ttl, ttl_period, expire_date, id, edit_date, send_state;
                        public java.util.ArrayList<MessageEntity> entities = new java.util.ArrayList<>();
                        public MessageReplyHeader reply_to;
                        public boolean out;
                        public Object action;
                        public Peer peer_id = new Peer();
                        public Media media;
                        public String message = "saved text";
                        public static Message TLdeserialize(NativeByteBuffer b, int constructor, boolean strict) { return b.message; }
                        public void readAttachPath(NativeByteBuffer b, long user) {}
                    }
                    public static class Peer { public long channel_id; }
                    public static class MessageReplyHeader { public int reply_to_msg_id; public Peer reply_to_peer_id; }
                    public static class TL_messageEmpty extends Message {}
                    public static class MessageEntity {
                        public int offset, length;
                        public void serializeToStream(SerializedData data) { data.writeInt32(offset); data.writeInt32(length); }
                    }
                    public static class Document { public long id, size; public String mime_type; }
                    public static class Photo { public long id; }
                    public static class Media { public int ttl_seconds; public Document document; public Photo photo; }
                    public static class TL_messageMediaEmpty extends Media {}
                }
                """));
            sources.add(source("org.telegram.tgnet.SerializedData", """
                package org.telegram.tgnet;
                public class SerializedData {
                    final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    public void writeString(String text) {
                        byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        writeInt32(b.length); bytes.writeBytes(b);
                    }
                    public void writeInt32(int n) { for (int i=0;i<4;i++) bytes.write(n >>> (8*i)); }
                    public void writeInt64(long n) { for (int i=0;i<8;i++) bytes.write((int)(n >>> (8*i))); }
                    public byte[] toByteArray() { return bytes.toByteArray(); }
                    public void cleanup() {}
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
                    int currentAccount; static boolean failDeletion;
                    static MessagesStorage getInstance(int account) { return instance; }
                    MessagesStorage getDatabase() { return this; }
                    SQLiteCursor queryFinalized(String sql, int id) { return new SQLiteCursor(dialog,message,ttl); }
                    ArrayList<Long> markMessagesAsDeletedInternal(long d, ArrayList<Integer> ids, boolean files, int mode, int topic) {
                        if (failDeletion) return null;
                        message = null;
                        return new ArrayList<>(List.of(d));
                    }
                    __LOCAL_DELETION__
                }
                class ChatDeletion {
                    int currentAccount, chatMode;
                    static final int MODE_SCHEDULED = 1;
                    final ListView chatListView = new ListView();
                    static class ListView { int redraws; void invalidateViews() { redraws++; } }
                    ArrayList<Integer> removed(Object... args) {
                        __DELETION_EVENT__
                        return markAsDeletedMessages;
                    }
                }
                class UserConfig { static UserConfig getInstance(int a) { return new UserConfig(); } long getClientUserId() { return 1; } }
                class DialogObject {
                    static boolean isEncryptedDialog(long d) { return d == (1L << 32); }
                    static long getPeerDialogId(TLRPC.Peer peer) { return -peer.channel_id; }
                }
                class MessageObject {
                    public int currentAccount, id; public long dialog; public boolean scheduled, quickReply;
                    public MessageObject replyMessageObject;
                    public TLRPC.Message messageOwner;
                    public boolean isQuickReply() { return quickReply; }
                    public long getDialogId() { return dialog; }
                    public int getId() { return id; }
                    static TLRPC.Document getDocument(TLRPC.Message m) { return m.media == null ? null : m.media.document; }
                    static TLRPC.Photo getPhoto(TLRPC.Message m) { return m.media == null ? null : m.media.photo; }
                    static boolean isEphemeral(TLRPC.Message m) { return m.id == 999; }
                }
                class FileLoader {
                    static File source = new File("/nonexistent-wgtg-test-media");
                    static FileLoader getInstance(int a) { return new FileLoader(); }
                    File getPathToMessage(TLRPC.Message m) { return source; }
                    static String getDocumentFileName(TLRPC.Document d) { return "file"; }
                }
                class FileLog { static void e(Exception e) { throw new AssertionError(e); } }
                class LocaleController { static String getString(int id) { return "missing media"; } }
                class R { static class string { static final int WgtgNoMedia = 1; } }
                class SharedConfig {
                    static final int PASSCODE_TYPE_PIN=0, PASSCODE_TYPE_PASSWORD=1;
                    static String passcodeHash="", normalPassword="1234";
                    static int passcodeType, badPasscodeTries;
                    static long passcodeRetryInMs, lastUptimeMillis;
                    static boolean appLocked, isWaitingForPasscodeEnter;
                    static boolean checkPasscode(String code) { return !passcodeHash.isEmpty() && normalPassword.equals(code); }
                    static void increaseBadPasscodeTries() { badPasscodeTries++; if (badPasscodeTries>=3) passcodeRetryInMs=5000; }
                    static void saveConfig() {}
                }
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
                        ChatDeletion chat = new ChatDeletion();
                        check(chat.removed(ids,0L,false).equals(List.of(12)), "remote chat event keeps preserved rows");
                        check(chat.chatListView.redraws==1, "remote chat event immediately invalidates deleted styling");
                        check(chat.removed(ids,0L,false,false,false,0,null,true).equals(ids), "local chat event bypasses concurrent capture markers");
                        chat.chatMode = ChatDeletion.MODE_SCHEDULED;
                        check(chat.removed(ids,0L,true).equals(ids), "scheduled IDs bypass regular message markers");
                        chat.chatMode = 0;
                        MessagesStorage.dialog = 7; MessagesStorage.message = new TLRPC.Message();
                        var racingIds = new ArrayList<>(List.of(30));
                        check(chat.removed(racingIds,0L,false,false,false,0,null,true).equals(racingIds), "local row removal before queued capture");
                        capture(30);
                        check(WgtgArchive.isDeleted(0,7,30), "queued capture commits before local storage deletion");
                        MessagesStorage.failDeletion = true;
                        MessagesStorage.instance.markMessagesAsDeletedLocally(7,racingIds,true,0);
                        check(WgtgArchive.isDeleted(0,7,30), "failed database deletion keeps preserved copy");
                        MessagesStorage.failDeletion = false;
                        MessagesStorage.instance.markMessagesAsDeletedLocally(7,racingIds,true,0);
                        check(!WgtgArchive.isDeleted(0,7,30) && !WgtgArchive.isDeleted(0,0,30), "local storage deletion clears raced capture and global alias");
                        capture(30);
                        check(!WgtgArchive.isDeleted(0,7,30), "late remote update cannot recapture removed row");
                        check(WgtgArchive.load(0,Long.MAX_VALUE).size()==2, "raced archive copy removed");
                        check(WgtgArchive.search(0,"",7,0,0).size()==2, "filter by chat");
                        check(WgtgArchive.search(0,"",8,0,0).isEmpty(), "different chat");
                        check(WgtgArchive.search(1,"",7,0,0).isEmpty(), "search account isolation");
                        check(WgtgArchive.search(0,"absent needle",0,0,0).isEmpty(), "text search");
                        check(WgtgArchive.search(0,"",0,1,0).size()==2, "no saved file filter");
                        check(WgtgArchive.search(0,"",0,2,0).isEmpty(), "saved file filter");
                        check(WgtgArchive.search(0,"",0,3,0).isEmpty(), "audio filter");
                        check(WgtgArchive.search(0,"",0,4,0).isEmpty(), "video filter");
                        check(WgtgArchive.search(0,"",0,0,1).size()==1, "offset pagination");
                        WgtgConfig.setPreserveDeleted(0,false);
                        check(WgtgArchive.isDeleted(0,7,10), "disabling capture preserves existing markers");
                        WgtgArchive.forget(0,7,10);
                        ApplicationLoader.applicationContext = new ApplicationLoader.Context();
                        check(!WgtgArchive.isDeleted(0,7,10) && !WgtgArchive.isDeleted(0,0,10), "local removal survives reload");
                        check(WgtgArchive.load(0,Long.MAX_VALUE).size()==1, "local removal removes archive entry");
                        org.json.JSONArray sample = new org.json.JSONArray("[]");
                        for (int i=0; i<120; i++) {
                            org.json.JSONObject item = new org.json.JSONObject();
                            item.put("row", 123L); item.put("dialog", i%2==0?7L:8L); item.put("mid", i+1);
                            item.put("text", "Track " + i); item.put("path", i%2==0?"/cached":"");
                            item.put("mime", i%2==0?"audio/mpeg":"video/mp4"); sample.put(item);
                        }
                        ApplicationLoader.applicationContext.getSharedPreferences("wgtg_archive_0",0).edit().putString("entries",sample.toString()).apply();
                        var first = WgtgArchive.search(0,"TRACK",7,3,0);
                        var secondPage = WgtgArchive.search(0,"track",7,3,50);
                        check(first.size()==50 && secondPage.size()==10, "filtered pages keep identical timestamps");
                        java.util.Set<Integer> seen = new java.util.HashSet<>();
                        for (var entry : first) check(seen.add(entry.mid), "unique first page");
                        for (var entry : secondPage) check(seen.add(entry.mid), "no repeated page entries");
                        check(WgtgArchive.search(0,"track",8,4,50).size()==10, "positive video and chat filter");
                        check(WgtgArchive.search(0,"track",8,2,0).isEmpty(), "combined saved-file and chat filter");
                        WgtgArchive.resetDeleted(0);
                        check(!WgtgArchive.isDeleted(0,7,11), "logout resets markers");
                        WgtgArchive.clear(0);
                        MessagesStorage.dialog = 7; MessagesStorage.ttl = 0;
                        TLRPC.Message original = new TLRPC.Message(); original.id = 70; original.message = "original caption";
                        TLRPC.Message edited = new TLRPC.Message(); edited.id = 70; edited.message = "edited caption"; edited.edit_date = 100;
                        MessagesStorage.message = original;
                        WgtgArchive.captureEdit(0,7,edited);
                        check(WgtgArchive.search(0,"",0,5,0).isEmpty(), "history disabled by default");
                        WgtgConfig.setPreserveEdits(0,true);
                        WgtgArchive.captureEdit(0,7,edited);
                        var history = WgtgArchive.search(0,"original caption",7,5,0);
                        check(history.size()==1 && history.get(0).history && history.get(0).mid==70, "captures old stored caption and identity before overwrite");
                        check(!WgtgArchive.isDeleted(0,7,70) && WgtgArchive.search(0,"",0,6,0).isEmpty(), "history never marks messages deleted");
                        WgtgArchive.captureEdit(0,7,edited);
                        check(WgtgArchive.search(0,"",0,5,0).size()==1, "duplicate edit update before storage replacement");
                        MessagesStorage.message = edited;
                        WgtgArchive.captureEdit(0,7,edited);
                        check(WgtgArchive.search(0,"",0,5,0).size()==1, "same cached content does not create a version");
                        TLRPC.Message second = new TLRPC.Message(); second.id=70; second.message="second edit"; second.edit_date=100;
                        WgtgArchive.captureEdit(0,7,second);
                        check(WgtgArchive.search(0,"edited caption",7,5,0).size()==1, "same-second text edits are retained");
                        WgtgArchive.captureEdit(0,7,original);
                        check(WgtgArchive.search(0,"",0,5,0).size()==2, "stale server version ignored");
                        original.send_state=3;
                        WgtgArchive.captureEdit(0,7,original);
                        check(WgtgArchive.search(0,"",0,5,0).size()==3, "optimistic local edit captured even before server timestamp");
                        TLRPC.Message formatted = new TLRPC.Message(); formatted.id=70; formatted.message=edited.message; formatted.edit_date=101;
                        TLRPC.MessageEntity bold = new TLRPC.MessageEntity(); bold.length=6; formatted.entities.add(bold);
                        WgtgArchive.captureEdit(0,7,formatted);
                        check(WgtgArchive.search(0,"",0,5,0).size()==4, "formatting-only edits captured");
                        TLRPC.Message mediaEdit = new TLRPC.Message(); mediaEdit.id=70; mediaEdit.message=edited.message; mediaEdit.edit_date=101;
                        mediaEdit.media = new TLRPC.Media(); mediaEdit.media.photo = new TLRPC.Photo(); mediaEdit.media.photo.id=123;
                        WgtgArchive.captureEdit(0,7,mediaEdit);
                        check(WgtgArchive.search(0,"",0,5,0).size()==5, "photo replacement with unchanged caption captured");
                        WgtgArchive.captureEdit(1,7,second);
                        check(WgtgArchive.search(1,"",0,5,0).isEmpty(), "history setting and data isolated by account");
                        WgtgConfig.setPreserveEdits(0,false);
                        WgtgArchive.captureEdit(0,7,second);
                        check(WgtgArchive.search(0,"",0,5,0).size()==5, "disabling history keeps existing versions");
                        ApplicationLoader.applicationContext = new ApplicationLoader.Context();
                        check(WgtgArchive.search(0,"",0,5,0).size()==5, "history survives preference reload");
                        WgtgConfig.setPreserveEdits(0,true);
                        for(int kind=0; kind<8; kind++) {
                            TLRPC.Message excluded = new TLRPC.Message(); excluded.id=80+kind; excluded.message="excluded";
                            MessagesStorage.dialog=7; MessagesStorage.ttl=0;
                            switch(kind) {
                                case 0 -> excluded.ttl=5;
                                case 1 -> excluded.ttl_period=5;
                                case 2 -> { excluded.media=new TLRPC.Media(); excluded.media.ttl_seconds=5; }
                                case 3 -> MessagesStorage.dialog=1L<<32;
                                case 4 -> MessagesStorage.ttl=5;
                                case 5 -> excluded.expire_date=100;
                                case 6 -> excluded.id=999;
                                case 7 -> excluded.action=new Object();
                            }
                            MessagesStorage.message=excluded;
                            WgtgArchive.captureEdit(0,MessagesStorage.dialog,second);
                            check(WgtgArchive.search(0,"",0,5,0).size()==5, "old excluded history kind " + kind);
                            MessagesStorage.message=edited;
                            WgtgArchive.captureEdit(0,MessagesStorage.dialog,excluded);
                            check(WgtgArchive.search(0,"",0,5,0).size()==5, "new excluded history kind " + kind);
                        }
                        MessagesStorage.dialog=7; MessagesStorage.ttl=0; MessagesStorage.message=edited;
                        WgtgConfig.setPreserveDeleted(0,true);
                        capture(70);
                        check(WgtgArchive.search(0,"",0,6,0).size()==1 && WgtgArchive.search(0,"",0,5,0).size()==5, "later deletion coexists with history");
                        WgtgArchive.forget(0,7,70);
                        check(WgtgArchive.search(0,"",0,0,0).isEmpty(), "local removal clears all versions for the message");
                        MessagesStorage.message = null;
                        WgtgArchive.captureEdit(0,7,second);
                        check(WgtgArchive.search(0,"",0,0,0).isEmpty(), "no cached version cannot create history");
                        MessagesStorage.message = edited;
                        TLRPC.Message empty = new TLRPC.TL_messageEmpty(); empty.id=70;
                        WgtgArchive.captureEdit(0,7,empty);
                        second.id=-70;
                        WgtgArchive.captureEdit(0,7,second);
                        second.id=70;
                        WgtgArchive.captureEdit(0,0,second);
                        check(WgtgArchive.search(0,"",0,0,0).isEmpty(), "empty message, pending ID and unknown dialog ignored");
                        TLRPC.Message metadata = new TLRPC.Message(); metadata.id=70; metadata.message=edited.message; metadata.edit_date=101; metadata.out=true;
                        WgtgArchive.captureEdit(0,7,metadata);
                        check(WgtgArchive.search(0,"",0,0,0).isEmpty(), "metadata-only update does not create history");
                        java.nio.file.Path mediaFile = java.nio.file.Files.createTempFile("wgtg-history-media-", ".bin");
                        FileLoader.source=mediaFile.toFile();
                        java.nio.file.Files.writeString(mediaFile,"first cached file");
                        WgtgArchive.captureEdit(0,7,second);
                        var savedFile = WgtgArchive.search(0,"",7,2,0).get(0);
                        check(java.nio.file.Files.readString(java.nio.file.Path.of(savedFile.path)).equals("first cached file"), "history copies available old media");
                        java.nio.file.Files.writeString(mediaFile,"replacement file");
                        MessagesStorage.message=second;
                        metadata.message="third edit"; metadata.edit_date=102;
                        WgtgArchive.captureEdit(0,7,metadata);
                        var replacementFile = WgtgArchive.search(0,"",7,2,0).get(0);
                        check(!replacementFile.path.equals(savedFile.path), "each version has its own media file");
                        check(java.nio.file.Files.readString(java.nio.file.Path.of(savedFile.path)).equals("first cached file"), "later edit cannot overwrite earlier media");
                        WgtgArchive.clear(0);
                        check(WgtgArchive.search(0,"",0,0,0).isEmpty() && !new File(savedFile.path).exists() && !new File(replacementFile.path).exists(), "clear removes history and copied media");
                        java.nio.file.Files.delete(mediaFile);
                        check(!WgtgPasscode.configure("1234","5678","5678"), "alternate requires enabled normal code");
                        SharedConfig.passcodeHash="normal-hash";
                        check(!WgtgPasscode.configure("0000","5678","5678") && SharedConfig.badPasscodeTries==1, "configuration verifies normal code and shares retry counter");
                        check(!WgtgPasscode.configure("1234","1234","1234"), "alternate must differ from normal");
                        check(!WgtgPasscode.configure("1234","5678","5679"), "alternate confirmation required");
                        check(!WgtgPasscode.configure("1234","abc","abc"), "PIN must fit lock screen input");
                        check(WgtgPasscode.configure("1234","5678","5678") && WgtgPasscode.hasAlternate(), "configure separate credential");
                        var privacyPrefs=ApplicationLoader.applicationContext.getSharedPreferences("wgtg_passcode",0);
                        check(privacyPrefs.getString("hash","").length()==64 && privacyPrefs.getString("salt","").length()==64, "salted derived credential persisted without plaintext");
                        MessagesStorage.message=edited; MessagesStorage.dialog=7;
                        capture(70);
                        int beforeRestriction=WgtgArchive.search(0,"",0,0,0).size();
                        check(beforeRestriction==1,"fixture contains preserved message");
                        MessageObject inline=new MessageObject(); inline.id=70; inline.dialog=7;
                        SharedConfig.appLocked=true;
                        long session=WgtgPasscode.beginLock();
                        check(WgtgPasscode.beginLock()==session,"overlay views share lock session");
                        check(!WgtgPasscode.canAccessArchive() && WgtgArchive.load(0,Long.MAX_VALUE).isEmpty(),"archive blocked during normal lock");
                        check(!WgtgPasscode.unlock("9999",session) && WgtgPasscode.isCurrentSession(session),"wrong code does not consume session");
                        SharedConfig.passcodeRetryInMs=5000;
                        check(!WgtgPasscode.unlock("5678",session),"alternate respects retry delay");
                        SharedConfig.passcodeRetryInMs=0;
                        check(WgtgPasscode.unlock("5678",session),"alternate opens lock");
                        SharedConfig.appLocked=false;
                        check(WgtgPasscode.isRestricted() && Boolean.TRUE.equals(SharedPreferences.disk.get("wgtg_passcode").get("restricted")),"restriction persisted before reveal");
                        check(!WgtgPasscode.unlock("1234",session) && !WgtgPasscode.acceptBiometric(session),"late normal and biometric callbacks cannot change completed session");
                        check(WgtgArchive.search(0,"",0,0,0).isEmpty() && WgtgArchive.load(0,Long.MAX_VALUE).isEmpty(),"both archive read entrypoints blocked");
                        check(WgtgArchive.hiddenContent(inline),"preserved inline message blocked");
                        MessageObject reply=new MessageObject(); reply.replyMessageObject=inline;
                        check(WgtgArchive.hiddenContent(reply),"quote of preserved message blocked");
                        reply.replyMessageObject=null; reply.dialog=7; reply.messageOwner=new TLRPC.Message();
                        reply.messageOwner.reply_to=new TLRPC.MessageReplyHeader(); reply.messageOwner.reply_to.reply_to_msg_id=70;
                        check(WgtgArchive.hiddenContent(reply),"quote without loaded reply object is blocked by original message ID");
                        inline.scheduled=true;
                        check(!WgtgArchive.hiddenMessage(inline),"scheduled ID collision is not a preserved message");
                        inline.scheduled=false;
                        try { WgtgArchive.clearFromUi(0); throw new AssertionError("restricted clear allowed"); }
                        catch (SecurityException expected) {}
                        check(!WgtgPasscode.configure("1234","9876","9876") && !WgtgPasscode.remove("1234"),"configuration entrypoints blocked while restricted");
                        WgtgArchive.captureEdit(0,7,second);
                        SharedConfig.appLocked=true;
                        long nextSession=WgtgPasscode.beginLock();
                        check(!WgtgPasscode.acceptBiometric(session),"old biometric callback rejected after relock");
                        check(WgtgPasscode.acceptBiometric(nextSession),"current biometric can unlock");
                        SharedConfig.appLocked=false;
                        check(WgtgPasscode.isRestricted() && !WgtgPasscode.canAccessArchive(),"biometric never clears restriction");
                        SharedConfig.appLocked=true;
                        nextSession=WgtgPasscode.beginLock();
                        SharedPreferences.failCommit=true;
                        check(!WgtgPasscode.unlock("1234",nextSession) && WgtgPasscode.isRestricted() && WgtgPasscode.isCurrentSession(nextSession),"failed persistent write cannot restore archive access");
                        SharedPreferences.failCommit=false;
                        check(WgtgPasscode.unlock("1234",nextSession),"normal code unlock succeeds");
                        SharedConfig.appLocked=false;
                        check(!WgtgPasscode.isRestricted() && Boolean.FALSE.equals(SharedPreferences.disk.get("wgtg_passcode").get("restricted")),"normal code clears persistent restriction");
                        check(WgtgArchive.search(0,"",0,0,0).size()==beforeRestriction+1 && WgtgArchive.isDeleted(0,7,70),"restriction deletes nothing and capture continues");
                        check(!WgtgArchive.hiddenContent(inline),"normal unlock restores inline content");
                        SharedConfig.isWaitingForPasscodeEnter=true;
                        check(!WgtgPasscode.canAccessArchive(),"pending external unlock blocks reads");
                        SharedConfig.isWaitingForPasscodeEnter=false;
                        SharedConfig.passcodeHash="changed-normal-hash";
                        check(!WgtgPasscode.hasAlternate(),"normal credential change invalidates alternate");
                        check(WgtgPasscode.configure("1234","9876","9876"),"alternate can be configured for changed normal code");
                        SharedConfig.passcodeType=SharedConfig.PASSCODE_TYPE_PASSWORD;
                        check(!WgtgPasscode.hasAlternate(),"normal input type change invalidates alternate");
                        SharedConfig.normalPassword="normal password";
                        check(WgtgPasscode.configure("normal password","alternate password","alternate password"),"password configuration supported");
                        check(WgtgPasscode.remove("normal password") && !WgtgPasscode.hasAlternate(),"normal verification can remove alternate credential");
                        SharedConfig.passcodeRetryInMs=5000; SharedConfig.lastUptimeMillis=android.os.SystemClock.now;
                        check(!WgtgPasscode.configure("normal password","alternate password","alternate password"),"configuration respects retry delay");
                        android.os.SystemClock.now+=5001;
                        check(WgtgPasscode.configure("normal password","alternate password","alternate password"),"prepare restart restriction");
                        SharedConfig.appLocked=true;
                        check(WgtgPasscode.unlock("alternate password",WgtgPasscode.beginLock()),"password alternate unlock");
                        SharedConfig.appLocked=false;
                        System.out.println("Passcode: configuration, salted credential, retry gate, persistent restriction, overlay sessions, stale callbacks, biometric, normal recovery, archive/inline gates and no data deletion passed");
                        org.telegram.ui.PrivacyUiTest.run();
                        System.out.println("Edit history: old snapshots, same-second edits, local edits, formatting, media identity, deduplication, stale updates, isolation, exclusions, reload and cleanup passed");
                        System.out.println("Archive behavior: capture, TTL exclusions, isolation, reload, duplicates, mixed deletion, chat redraw, local capture race and cleanup passed");
                    }
                }
                """.replace("__LOCAL_DELETION__", localDeletion).replace("__DELETION_EVENT__", deletionEvent)));
            Path classes = Files.createTempDirectory("wgtg-archive-test-");
            if (!compiler.getTask(null, manager, null, List.of("-d", classes.toString(), "-proc:none"), null, sources).call()) {
                throw new AssertionError("Archive compilation failed");
            }
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
                loader.loadClass("org.telegram.messenger.ArchiveTest").getMethod("run").invoke(null);
                Object disk=loader.loadClass("android.content.SharedPreferences").getField("disk").get(null);
                try (URLClassLoader restarted = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
                    ((java.util.Map)restarted.loadClass("android.content.SharedPreferences").getField("disk").get(null)).putAll((java.util.Map)disk);
                    Class<?> privacy=restarted.loadClass("org.telegram.messenger.WgtgPasscode");
                    if (!(Boolean)privacy.getMethod("isRestricted").invoke(null) || (Boolean)privacy.getMethod("canAccessArchive").invoke(null)) {
                        throw new AssertionError("Process restart must retain restricted mode even without lock state");
                    }
                    System.out.println("Fresh classloader restart: persistent restricted mode remains blocked");
                }
            }
        }
    }
}
