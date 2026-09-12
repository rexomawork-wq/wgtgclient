"""Compile the real bridge against published MVEL/Android JSON and Android doubles.

Set WGTG_TEST_JDK, WGTG_TEST_MVEL_JAR and WGTG_TEST_JSON_JAR to run this host test.
Use Android JSON (com.vaadin.external.google:android-json), whose optString null
semantics differ from modern org.json. This checks bridge behavior, not Android
layout or the Chaquopy runtime.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


STUBS = {
    "android.content.Context": """
        public class Context {
            public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
            public String getPackageName() { return "test"; }
        }""",
    "android.content.res.Resources": """
        public class Resources {
            public int getIdentifier(String name, String type, String pkg) { return name.equals("msg_info") ? 42 : 0; }
        }""",
    "android.view.View": """
        public class View {
            public static final int GONE = 8;
            public ViewGroup parent;
            public OnClickListener listener;
            public int visibility;
            public interface OnClickListener { void onClick(View view); }
            public void setOnClickListener(OnClickListener value) { listener = value; }
            public ViewGroup getParent() { return parent; }
            public void setVisibility(int value) { visibility = value; }
            public void setMinimumWidth(int value) {}
            public void performClick() { if (listener != null) listener.onClick(this); }
        }""",
    "android.view.ViewGroup": """
        public class ViewGroup extends View {
            public final java.util.List<View> children = new java.util.ArrayList<>();
            public void addView(View view) { children.add(view); view.parent = this; }
            public void removeView(View view) { children.remove(view); view.parent = null; }
        }""",
    "androidx.annotation.Keep": "public @interface Keep {}",
    "org.telegram.ui.ActionBar.Theme": "public class Theme { public interface ResourcesProvider {} }",
    "org.telegram.ui.ActionBar.BaseFragment": """
        public class BaseFragment {
            public int account = 2;
            public boolean isFinished;
            public android.content.Context activity = new android.content.Context();
            public android.content.Context getContext() { return activity; }
            public android.content.Context getParentActivity() { return activity; }
            public int getCurrentAccount() { return account; }
            public Theme.ResourcesProvider getResourceProvider() { return null; }
        }""",
    "org.telegram.ui.ActionBar.ActionBarMenuSubItem": """
        public class ActionBarMenuSubItem extends android.view.View {
            public CharSequence text, subtext;
            public int icon, height;
            public ActionBarMenuSubItem(android.content.Context c, boolean top, boolean bottom, Theme.ResourcesProvider p) {}
            public void setTextAndIcon(CharSequence value, int drawable) { text = value; icon = drawable; }
            public void setSubtext(CharSequence value) { subtext = value; }
            public void setItemHeight(int value) { height = value; }
        }""",
    "org.telegram.ui.ActionBar.ActionBarPopupWindow": """
        public class ActionBarPopupWindow {
            public static class ActionBarPopupWindowLayout extends android.view.ViewGroup {
                public int getItemsCount() { return children.size(); }
                public android.view.View getItemAt(int index) { return children.get(index); }
            }
        }""",
    "org.telegram.ui.ActionBar.ActionBarMenuItem": """
        public class ActionBarMenuItem {
            private final ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout();
            public ActionBarPopupWindow.ActionBarPopupWindowLayout getPopupLayout() { return layout; }
            public void closeSubMenu() {}
        }""",
    "org.telegram.ui.Components.ItemOptions": """
        public class ItemOptions {
            public final android.view.ViewGroup layout = new android.view.ViewGroup();
            public boolean dismissed;
            public void add(org.telegram.ui.ActionBar.ActionBarMenuSubItem row) { layout.addView(row); }
            public void dismiss() { dismissed = true; }
        }""",
    "org.telegram.tgnet.TLRPC": """
        public class TLRPC {
            public static class User { public long id; public boolean bot; }
            public static class Chat { public long id; }
            public static class UserFull {}
            public static class ChatFull {}
            public static class EncryptedChat {}
        }""",
    "org.telegram.messenger.MessageObject": "public class MessageObject { public int getId() { return 123; } }",
    "org.telegram.messenger.ApplicationLoader": "public class ApplicationLoader { public static android.content.Context applicationContext = new android.content.Context(); }",
    "org.telegram.messenger.WgtgPluginsController": "public class WgtgPluginsController { public static boolean ready = true; public static boolean isReady() { return ready; } }",
    "org.telegram.messenger.FileLog": "public class FileLog { public static void e(Throwable error) { throw new AssertionError(error); } }",
    "org.telegram.messenger.AndroidUtilities": """
        public class AndroidUtilities {
            public static java.util.List<Runnable> pending = new java.util.ArrayList<>();
            public static int dp(int value) { return value; }
            public static void runOnUIThread(Runnable task) { pending.add(task); }
        }""",
    "com.chaquo.python.Python": """
        public class Python {
            public static String json = "[]";
            public static Object[] clickArgs;
            public static int type;
            public static Python getInstance() { return new Python(); }
            public Python getModule(String name) { return this; }
            public Python callAttr(String name, Object... args) {
                if (name.equals("visible_items")) type = (Integer) args[0];
                if (name.equals("click")) clickArgs = args;
                return this;
            }
            public <T> T toJava(Class<T> type) { return type.cast(json); }
        }""",
}


class NativeMenuBridgeTests(unittest.TestCase):
    @unittest.skipUnless(all(os.environ.get(key) for key in (
        "WGTG_TEST_JDK", "WGTG_TEST_MVEL_JAR", "WGTG_TEST_JSON_JAR")), "host Java test dependencies not configured")
    def test_native_rows_context_cleanup_and_real_mvel(self):
        root = Path(__file__).resolve().parents[1]
        jdk = Path(os.environ["WGTG_TEST_JDK"]) / "bin"
        jars = os.pathsep.join(os.environ[key] for key in ("WGTG_TEST_MVEL_JAR", "WGTG_TEST_JSON_JAR"))
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            sources = []
            for name, body in STUBS.items():
                source = temp / (name.replace(".", "/") + ".java")
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
                sources.append(str(source))
            sources += [str(root / "TMessagesProj/src/main/java/org/telegram/messenger/WgtgPluginMenus.java"),
                        str(root / "Tools/TestWgtgPluginMenus.java")]
            compile_result = subprocess.run([str(jdk / "javac"), "--release", "8", "-cp", jars,
                                             "-d", str(temp), *sources], capture_output=True, text=True)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            result = subprocess.run([str(jdk / "java"), "-cp", str(temp) + os.pathsep + jars,
                                     "org.telegram.messenger.TestWgtgPluginMenus"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
