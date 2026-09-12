package org.telegram.messenger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import top.canyie.pine.Pine;

/** Host semantic tests: use the published Pine jar and replace only the native original call. */
public class TestWgtgMethodHooks {
    public static int target(int value) { return value + 1; }
    private static final Method TARGET;
    static {
        try { TARGET = TestWgtgMethodHooks.class.getMethod("target", int.class); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static class Callback implements WgtgMethodHooks.Callback {
        @Override public void beforeHookedMethod(WgtgMethodHooks.Param param) throws Throwable {}
        @Override public void afterHookedMethod(WgtgMethodHooks.Param param) throws Throwable {}
    }

    private static class Frame extends Pine.CallFrame {
        int originals;
        Throwable failure;
        Frame(int value) { super(new Pine.HookRecord(TARGET, 0), null, new Object[]{value}); }
        @Override public Object invokeOriginalMethod(Object receiver, Object... args)
                throws InvocationTargetException {
            originals++;
            if (failure != null) throw new InvocationTargetException(failure);
            check(args[0] instanceof Integer, "primitive argument must be Integer");
            return target((Integer) args[0]);
        }
    }

    private static WgtgMethodHooks.Handle add(WgtgMethodHooks.Dispatcher dispatcher,
                                             int priority, Callback callback) {
        WgtgMethodHooks.Handle handle = new WgtgMethodHooks.Handle(dispatcher, callback, priority);
        dispatcher.add(handle);
        return handle;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void orderingAndArguments() {
        WgtgMethodHooks.Dispatcher dispatcher = new WgtgMethodHooks.Dispatcher();
        List<String> events = new ArrayList<>();
        for (int priority : new int[]{10, 90, 50, 50}) {
            final String name = priority + ":" + events.size();
            events.add(name);
            add(dispatcher, priority, new Callback() {
                @Override public void beforeHookedMethod(WgtgMethodHooks.Param param) {
                    events.add("b" + name);
                    param.args[0] = 20L;
                }
                @Override public void afterHookedMethod(WgtgMethodHooks.Param param) {
                    events.add("a" + name);
                    param.setResult(((Number) param.getResult()).longValue() + 1);
                }
            });
        }
        events.clear();
        Frame frame = new Frame(1);
        dispatcher.beforeCall(frame);
        check(events.equals(Arrays.asList("b90:1", "b50:2", "b50:3", "b10:0",
                "a10:0", "a50:3", "a50:2", "a90:1")), "priority order: " + events);
        check(frame.originals == 1 && frame.getResult().equals(25), "original and after result");
    }

    private static void earlyReturnAndFailures() {
        WgtgMethodHooks.Dispatcher dispatcher = new WgtgMethodHooks.Dispatcher();
        add(dispatcher, 90, new Callback() {
            @Override public void beforeHookedMethod(WgtgMethodHooks.Param param) {
                param.setResult(8L);
                throw new IllegalStateException("ordinary before failure must reset early return");
            }
            @Override public void afterHookedMethod(WgtgMethodHooks.Param param) {
                param.setResult(99L);
                throw new IllegalStateException("ordinary after failure must restore result");
            }
        });
        add(dispatcher, 50, new Callback() {
            @Override public void beforeHookedMethod(WgtgMethodHooks.Param param) { param.setResult(42L); }
        });
        add(dispatcher, 10, new Callback() {
            @Override public void beforeHookedMethod(WgtgMethodHooks.Param param) {
                throw new AssertionError("lower priority hook must be skipped");
            }
            @Override public void afterHookedMethod(WgtgMethodHooks.Param param) { param.setResult(-1); }
        });
        Frame frame = new Frame(1);
        dispatcher.beforeCall(frame);
        check(frame.originals == 0 && frame.getResult().equals(42), "early result/failure semantics");
    }

    private static void exceptions() throws Throwable {
        WgtgMethodHooks.Dispatcher dispatcher = new WgtgMethodHooks.Dispatcher();
        Throwable original = new IllegalArgumentException("original");
        Throwable replacement = new UnsupportedOperationException("replacement");
        add(dispatcher, 50, new Callback() {
            @Override public void afterHookedMethod(WgtgMethodHooks.Param param) throws Throwable {
                check(param.hasThrowable() && param.getThrowable() == original, "unwrap original exception");
                try { param.getResultOrThrowable(); throw new AssertionError("must throw"); }
                catch (IllegalArgumentException error) { check(error == original, "exception identity"); }
                param.setResult(7L);
                check(!param.hasThrowable(), "setResult clears throwable");
                param.setThrowable(replacement);
                check(param.getResult() == null, "setThrowable clears result");
            }
        });
        Frame frame = new Frame(0);
        frame.failure = original;
        dispatcher.beforeCall(frame);
        check(frame.getThrowable() == replacement, "after replacement exception");
        WgtgMethodHooks.Dispatcher early = new WgtgMethodHooks.Dispatcher();
        add(early, 50, new Callback() {
            @Override public void beforeHookedMethod(WgtgMethodHooks.Param param) { param.setThrowable(original); }
        });
        frame = new Frame(0);
        early.beforeCall(frame);
        check(frame.originals == 0 && frame.getThrowable() == original, "before throwable skips original");
    }

    private static void recursionAndRemoval() {
        WgtgMethodHooks.Dispatcher dispatcher = new WgtgMethodHooks.Dispatcher();
        AtomicInteger afters = new AtomicInteger();
        WgtgMethodHooks.Handle handle = add(dispatcher, 50, new Callback() {
            @Override public void beforeHookedMethod(WgtgMethodHooks.Param param) {
                int depth = ((Number) param.args[0]).intValue();
                if (depth > 0) {
                    Frame inner = new Frame(depth - 1);
                    dispatcher.beforeCall(inner);
                    check(inner.getResult().equals(depth + 10), "recursive inner result");
                }
            }
            @Override public void afterHookedMethod(WgtgMethodHooks.Param param) {
                afters.incrementAndGet();
                param.setResult(((Integer) param.getResult()) + 10);
            }
        });
        Frame frame = new Frame(4);
        dispatcher.beforeCall(frame);
        check(frame.getResult().equals(15) && afters.get() == 5, "recursive outer after state");
        handle.unhook();
        handle.unhook();
        frame = new Frame(4);
        dispatcher.beforeCall(frame);
        check(frame.getResult() == null && frame.originals == 0 && afters.get() == 5,
                "empty dispatcher must let Pine invoke original");

        WgtgMethodHooks.Handle[] removing = new WgtgMethodHooks.Handle[1];
        removing[0] = add(dispatcher, 50, new Callback() {
            @Override public void beforeHookedMethod(WgtgMethodHooks.Param param) { removing[0].unhook(); }
            @Override public void afterHookedMethod(WgtgMethodHooks.Param param) { param.setResult(-1); }
        });
        frame = new Frame(1);
        dispatcher.beforeCall(frame);
        check(frame.getResult().equals(2), "unhook during call disables after callback");
    }

    private static void conversionAndGuards() throws Throwable {
        WgtgMethodHooks.Param param = new WgtgMethodHooks.Param(new Frame(0));
        param.setResult(123L);
        check(param.getResult() instanceof Integer, "Long converted to int result");
        for (Object bad : new Object[]{null, Long.MAX_VALUE, 1.5, "bad"}) {
            try { param.setResult(bad); throw new AssertionError("invalid int accepted: " + bad); }
            catch (IllegalArgumentException expected) {}
        }
        String arch = System.getProperty("os.arch");
        try {
            System.setProperty("os.arch", "x86_64");
            check(WgtgMethodHooks.getUnsupportedReason().contains("ARM"), "x86 guard");
            try { WgtgMethodHooks.hook(TARGET, new Callback(), 50); throw new AssertionError("x86 hook accepted"); }
            catch (UnsupportedOperationException expected) {}
        } finally { System.setProperty("os.arch", arch); }
    }

    public static void main(String[] args) throws Throwable {
        orderingAndArguments();
        earlyReturnAndFailures();
        exceptions();
        recursionAndRemoval();
        conversionAndGuards();
        System.out.println("WgtgMethodHooks: 5 semantic test groups passed (no ART native execution)");
    }
}
