package org.telegram.messenger;

import android.net.Uri;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.telegram.tgnet.TLObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class WgtgPluginsController {
    private static volatile boolean ready;
    private static volatile String startupError;

    public static void initializeAsync() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (!Python.isStarted()) Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
                module().callAttr("initialize", pluginsDir().getAbsolutePath());
                ready = true;
            } catch (Throwable e) {
                startupError = e.toString();
                FileLog.e(e);
            }
        });
    }

    private static File pluginsDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "plugins");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static PyObject module() {
        return Python.getInstance().getModule("wgtg_plugin_engine");
    }

    public static boolean isReady() { return ready; }
    public static String getStartupError() { return startupError; }

    public static String listJson() {
        if (!ready) return "[]";
        try { return module().callAttr("list_plugins").toJava(String.class); }
        catch (Throwable e) { FileLog.e(e); return "[]"; }
    }

    public static String install(Uri uri) throws Exception {
        File incoming = prepareInstall(uri);
        try { return installPrepared(incoming); }
        finally { incoming.delete(); }
    }

    public static File prepareInstall(Uri uri) throws Exception {
        if (!ready) throw new IllegalStateException(startupError == null ? "Python runtime is starting" : startupError);
        File incoming = File.createTempFile(".incoming-", ".plugin", pluginsDir());
        try (InputStream input = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(incoming)) {
            if (input == null) throw new IllegalArgumentException("Cannot open plugin");
            byte[] buffer = new byte[65536]; int count; long size = 0;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > 32L * 1024 * 1024) throw new IllegalArgumentException("Plugin is larger than 32 MB");
                output.write(buffer, 0, count);
            }
        } catch (Exception e) {
            incoming.delete();
            throw e;
        }
        return incoming;
    }

    public static String inspect(File incoming) {
        return module().callAttr("inspect_plugin", incoming.getAbsolutePath()).toJava(String.class);
    }

    public static String installPrepared(File incoming) {
        return module().callAttr("install", incoming.getAbsolutePath()).toJava(String.class);
    }

    public static void setEnabled(String id, boolean enabled) {
        if (!ready) return;
        try { module().callAttr("set_enabled", id, enabled); }
        catch (Throwable e) { FileLog.e(e); }
    }

    public static void uninstall(String id) {
        if (!ready) return;
        try { module().callAttr("uninstall", id); }
        catch (Throwable e) { FileLog.e(e); }
    }

    public static TLObject beforeRequest(int account, TLObject request) {
        if (!ready || request == null) return request;
        try {
            String name = request.getClass().getSimpleName();
            Class<?> outer = request.getClass().getEnclosingClass();
            if (outer != null && outer.getSimpleName().startsWith("TL_") && !name.startsWith("TL_")) {
                name = outer.getSimpleName() + "_" + name;
            }
            PyObject result = module().callAttr("before_request", name, account, request);
            return result == null ? null : result.toJava(TLObject.class);
        }
        catch (Throwable e) { FileLog.e(e); return request; }
    }

    public static SendMessagesHelper.SendMessageParams beforeSendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        if (!ready || params == null) return params;
        try {
            PyObject result = module().callAttr("before_send_message", account, params);
            return result == null ? null : result.toJava(SendMessagesHelper.SendMessageParams.class);
        }
        catch (Throwable e) { FileLog.e(e); return params; }
    }

    public static String settingsRows(String id) {
        return module().callAttr("settings_rows", id).toJava(String.class);
    }

    public static void setSetting(String id, String key, String json) {
        module().callAttr("set_setting_json", id, key, json);
    }
}
