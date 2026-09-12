package org.telegram.messenger;

import java.lang.reflect.Method;
import top.canyie.pine.Pine;

/** Host-only fixture: real bridge and CallFrame; the native original is replaced. */
public final class HookFilterHarness {
    public static Object target(Object value) { return value; }

    private static Pine.CallFrame frame(Object receiver, Object[] args) throws Exception {
        Method target = HookFilterHarness.class.getMethod("target", Object.class);
        return new Pine.CallFrame(new Pine.HookRecord(target, 0), receiver, args) {
            @Override public Object invokeOriginalMethod(Object receiver, Object... args) {
                return target(args[0]);
            }
        };
    }

    public static WgtgMethodHooks.Param param(Object receiver, Object[] args, Object result) throws Exception {
        WgtgMethodHooks.Param param = new WgtgMethodHooks.Param(frame(receiver, args));
        param.setResult(result);
        return param;
    }

    public static Pine.CallFrame invoke(WgtgMethodHooks.Callback callback, Object receiver, Object[] args)
            throws Exception {
        WgtgMethodHooks.Dispatcher dispatcher = new WgtgMethodHooks.Dispatcher();
        dispatcher.add(new WgtgMethodHooks.Handle(dispatcher, callback, 50));
        Pine.CallFrame frame = frame(receiver, args);
        dispatcher.beforeCall(frame);
        return frame;
    }
}
