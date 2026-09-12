package org.telegram.messenger;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

/** In-process ART interception with Xposed-style callback semantics. */
public final class WgtgMethodHooks {
    private static final Map<Member, Dispatcher> dispatchers = new HashMap<>();
    private static final Map<Member, Throwable> failures = new HashMap<>();

    private WgtgMethodHooks() {}

    public interface Callback {
        void beforeHookedMethod(Param param) throws Throwable;
        void afterHookedMethod(Param param) throws Throwable;
    }

    public static String getUnsupportedReason() {
        String arch = System.getProperty("os.arch", "");
        if (!(arch.equals("aarch64") || arch.startsWith("arm"))) {
            return "Pine 0.3.0 supports ARM processes only; process architecture is " + arch;
        }
        if (Build.VERSION.SDK_INT < 24 || Build.VERSION.SDK_INT > 35) {
            return "Method hooks are limited to API 24-35; this device is API " + Build.VERSION.SDK_INT;
        }
        if (Os.sysconf(OsConstants._SC_PAGESIZE) != 4096) {
            return "Pine 0.3.0 requires a 4 KB page-size process";
        }
        return null;
    }

    public static synchronized Handle hook(Member method, Callback callback, int priority) {
        if (!(method instanceof Method || method instanceof Constructor)
                || Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException("Expected a concrete reflected Method or Constructor");
        }
        if (callback == null) throw new NullPointerException("callback");
        String unsupported = getUnsupportedReason();
        if (unsupported != null) throw new UnsupportedOperationException(unsupported);
        if (failures.containsKey(method)) {
            // Pine retains a HookRecord after some installation failures. Retrying it can
            // appear successful without an installed native trampoline.
            throw new IllegalStateException("Previous hook installation failed; restart required", failures.get(method));
        }
        Dispatcher dispatcher = dispatchers.get(method);
        if (dispatcher == null) {
            if (!Pine.isInitialized()) {
                PineConfig.debug = false;
                // Plugins load after other threads start. Do not change ART hidden-API policy here.
                PineConfig.disableHiddenApiPolicy = false;
                PineConfig.disableHiddenApiPolicyForPlatformDomain = false;
            }
            dispatcher = new Dispatcher();
            try {
                Pine.hook(method, dispatcher);
            } catch (RuntimeException | Error error) {
                failures.put(method, error);
                throw error;
            }
            dispatchers.put(method, dispatcher);
        }
        Handle handle = new Handle(dispatcher, callback, priority);
        dispatcher.add(handle);
        return handle;
    }

    public static final class Handle {
        private final Dispatcher dispatcher;
        private final int priority;
        private volatile Callback callback;

        Handle(Dispatcher dispatcher, Callback callback, int priority) {
            this.dispatcher = dispatcher;
            this.callback = callback;
            this.priority = priority;
        }

        public void unhook() {
            callback = null;
            dispatcher.remove(this);
        }
    }

    public static final class Param {
        public final Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        Param(Pine.CallFrame frame) {
            method = frame.method;
            thisObject = frame.thisObject;
            args = frame.args;
        }

        public Object getResult() { return result; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
        public void setResult(Object value) {
            Class<?> type = method instanceof Method ? ((Method) method).getReturnType() : void.class;
            result = convert(value, type);
            throwable = null;
            returnEarly = true;
        }
        public void setThrowable(Throwable value) {
            throwable = value;
            result = null;
            returnEarly = true;
        }

        private void normalizeArgs() {
            Class<?>[] types = method instanceof Method ? ((Method) method).getParameterTypes()
                    : ((Constructor<?>) method).getParameterTypes();
            if (args == null || args.length != types.length) {
                throw new IllegalArgumentException("Hook argument count does not match method");
            }
            for (int i = 0; i < types.length; i++) args[i] = convert(args[i], types[i]);
        }
    }

    // Chaquopy boxes Python integers as Long for Object parameters. Pine requires
    // the exact primitive wrapper, including for the return value of an int method.
    private static Object convert(Object value, Class<?> type) {
        if (type == void.class) return null;
        if (!type.isPrimitive()) return value == null ? null : type.cast(value);
        if (value == null) throw new IllegalArgumentException("null for primitive " + type);
        if (type == boolean.class && value instanceof Boolean) return value;
        if (type == char.class) {
            if (value instanceof Character) return value;
            if (value instanceof String && ((String) value).length() == 1) return ((String) value).charAt(0);
        }
        if (value instanceof Number) {
            Number n = (Number) value;
            if (type == double.class) return n.doubleValue();
            if (type == float.class) return n.floatValue();
            long v = n.longValue();
            if (!(n instanceof Float || n instanceof Double)) {
                if (type == long.class) return v;
                if (type == int.class && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) return (int) v;
                if (type == short.class && v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return (short) v;
                if (type == byte.class && v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return (byte) v;
            }
        }
        throw new IllegalArgumentException("Incompatible value for primitive " + type);
    }

    static final class Dispatcher extends MethodHook {
        private volatile Handle[] callbacks = new Handle[0];

        synchronized void add(Handle handle) {
            Handle[] next = Arrays.copyOf(callbacks, callbacks.length + 1);
            next[next.length - 1] = handle;
            Arrays.sort(next, (a, b) -> Integer.compare(b.priority, a.priority));
            callbacks = next;
        }

        synchronized void remove(Handle handle) {
            ArrayList<Handle> next = new ArrayList<>(Arrays.asList(callbacks));
            next.remove(handle);
            callbacks = next.toArray(new Handle[0]);
        }

        @Override public void beforeCall(Pine.CallFrame frame) {
            Handle[] snapshot = callbacks;
            if (snapshot.length == 0) return;
            // Entire invocation stays on this Java stack, including recursive calls.
            Param param = new Param(frame);
            ArrayList<Handle> entered = new ArrayList<>();
            for (Handle handle : snapshot) {
                Callback callback = handle.callback;
                if (callback == null) continue;
                entered.add(handle);
                try {
                    callback.beforeHookedMethod(param);
                } catch (Throwable error) {
                    Log.e("WgtgMethodHooks", "before hook failed", error);
                    param.result = null;
                    param.throwable = null;
                    param.returnEarly = false;
                }
                if (param.returnEarly) break;
            }
            if (!param.returnEarly) {
                try {
                    param.normalizeArgs();
                    param.result = frame.invokeOriginalMethod(param.thisObject, param.args);
                } catch (InvocationTargetException error) {
                    param.throwable = error.getTargetException();
                } catch (Throwable error) {
                    param.throwable = error;
                }
            }
            for (int i = entered.size() - 1; i >= 0; i--) {
                Callback callback = entered.get(i).callback;
                if (callback == null) continue;
                Object result = param.result;
                Throwable throwable = param.throwable;
                try {
                    callback.afterHookedMethod(param);
                } catch (Throwable error) {
                    Log.e("WgtgMethodHooks", "after hook failed", error);
                    param.result = result;
                    param.throwable = throwable;
                }
            }
            frame.thisObject = param.thisObject;
            frame.args = param.args;
            if (param.hasThrowable()) frame.setThrowable(param.throwable);
            else frame.setResult(param.result);
        }
    }
}
