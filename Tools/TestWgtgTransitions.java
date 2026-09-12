import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

// JDK 17: java Tools/TestWgtgTransitions.java
// Compiles the actual animation methods against small Android substitutes. No SDK required.
class TestWgtgTransitions {
    static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            public CharSequence getCharContent(boolean ignore) { return text; }
        };
    }

    static String methods(JavaCompiler compiler, StandardJavaFileManager manager, Path path, String... names) throws Exception {
        String text = Files.readString(path);
        JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, manager.getJavaFileObjects(path));
        CompilationUnitTree unit = task.parse().iterator().next();
        SourcePositions positions = Trees.instance(task).getSourcePositions();
        StringBuilder result = new StringBuilder();
        new TreeScanner<Void, Void>() {
            public Void visitMethod(MethodTree method, Void unused) {
                if (Arrays.asList(names).contains(method.getName().toString())) {
                    result.append(text, (int) positions.getStartPosition(unit, method), (int) positions.getEndPosition(unit, method)).append('\n');
                }
                return super.visitMethod(method, unused);
            }
        }.scan(unit, null);
        return result.toString();
    }

    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path root = Path.of("TMessagesProj/src/main/java/org/telegram");
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            String message = methods(compiler, manager, root.resolve("ui/recyclerview/ChatListItemAnimator.java"), "animateAdd", "animateWgtgAdd")
                    .replace("@Override", "");
            String navigation = methods(compiler, manager, root.resolve("ui/ActionBar/ActionBarLayout.java"), "applyWgtgChatTransition", "getWgtgChatTransition");
            ArrayList<JavaFileObject> sources = new ArrayList<>();
            manager.getJavaFileObjects(root.resolve("messenger/WgtgConfig.java")).forEach(sources::add);
            sources.add(source("android.content.SharedPreferences", """
                package android.content;
                public class SharedPreferences {
                    public static final java.util.Map<String,Object> values = new java.util.HashMap<>(java.util.Map.of("messageTransition", 2, "chatTransition", 3));
                    public int getInt(String k, int d) { return (int) values.getOrDefault(k,d); }
                    public boolean getBoolean(String k, boolean d) { return (boolean) values.getOrDefault(k,d); }
                    public SharedPreferences edit() { return this; }
                    public SharedPreferences putInt(String k, int v) { values.put(k,v); return this; }
                    public SharedPreferences putBoolean(String k, boolean v) { values.put(k,v); return this; }
                    public void apply() {}
                }
                """));
            sources.add(source("org.telegram.messenger.ApplicationLoader", """
                package org.telegram.messenger;
                public class ApplicationLoader {
                    public static final ApplicationLoader applicationContext = new ApplicationLoader();
                    public android.content.SharedPreferences getSharedPreferences(String k, int m) { return new android.content.SharedPreferences(); }
                }
                """));
            sources.add(source("AnimationChecks", """
                import java.util.*;
                import org.telegram.messenger.WgtgConfig;
                public class AnimationChecks {
                    static class Animator {}
                    static class AnimatorListenerAdapter {
                        public void onAnimationStart(Animator a) {} public void onAnimationCancel(Animator a) {} public void onAnimationEnd(Animator a) {}
                    }
                    static class View {
                        float alpha = 1, x, y, sx = 1, sy = 1;
                        final ViewPropertyAnimator animator = new ViewPropertyAnimator(this);
                        void setAlpha(float v) { alpha=v; } void setTranslationX(float v) { x=v; } void setTranslationY(float v) { y=v; }
                        void setScaleX(float v) { sx=v; } void setScaleY(float v) { sy=v; } float getScaleX() { return sx; }
                        ViewPropertyAnimator animate() { return animator; }
                    }
                    static class ViewPropertyAnimator {
                        final View view; AnimatorListenerAdapter listener; long duration;
                        ViewPropertyAnimator(View v) { view=v; }
                        ViewPropertyAnimator alpha(float v) { return this; } ViewPropertyAnimator translationY(float v) { return this; }
                        ViewPropertyAnimator scaleX(float v) { return this; } ViewPropertyAnimator scaleY(float v) { return this; }
                        ViewPropertyAnimator setStartDelay(long v) { return this; } ViewPropertyAnimator setDuration(long v) { duration=v; return this; }
                        ViewPropertyAnimator setInterpolator(Object v) { return this; }
                        ViewPropertyAnimator setListener(AnimatorListenerAdapter v) { listener=v; return this; }
                        void start() { listener.onAnimationStart(new Animator()); }
                        void finish(boolean cancel) { var l=listener; if(cancel) l.onAnimationCancel(new Animator()); l.onAnimationEnd(new Animator()); }
                    }
                    static class Params { boolean messageEntering; }
                    static class ChatMessageCell extends View {
                        final Params params = new Params(); Object group; final Object message = new Object();
                        Params getTransitionParams() { return params; } Object getCurrentMessagesGroup() { return group; } Object getMessageObject() { return message; }
                    }
                    static class RecyclerView { static class ViewHolder { View itemView; ViewHolder(View v) { itemView=v; } } }
                    static class BaseFragment {}
                    static class ChatActivity extends BaseFragment { Set<Object> animatingMessageObjects = new HashSet<>(); }
                    static class SharedConfig { static final int PERFORMANCE_CLASS_LOW=0; static int performance=1; static boolean enabled=true;
                        static int getDevicePerformanceClass() { return performance; } static boolean animationsEnabled() { return enabled; } }
                    static class LiteMode { static final int FLAG_CHAT_SCALE=1; static boolean enabled=true; static boolean isEnabled(int f) { return enabled; } }
                    static class AndroidUtilities { static float duration=1; static int dp(int v) { return v; } static float getAnimatorDurationScale() { return duration; } }
                    static class CubicBezierInterpolator { static final Object EASE_OUT_QUINT = new Object(); }
                    int dp(int v) { return v; }
                    int wgtgChatTransitionStyle, starts, finishes, finishedBatches;
                    RecyclerView.ViewHolder greetingsSticker;
                    ChatActivity activity = new ChatActivity();
                    List<RecyclerView.ViewHolder> mAddAnimations = new ArrayList<>();
                    List<RecyclerView.ViewHolder> mPendingAdditions = new ArrayList<>();
                    boolean shouldAnimateEnterFromBottom;
                    void resetAnimation(RecyclerView.ViewHolder h) { restoreTransitionParams(h.itemView); }
                    void dispatchAddStarting(RecyclerView.ViewHolder h) { starts++; }
                    void dispatchAddFinished(RecyclerView.ViewHolder h) { finishes++; }
                    void dispatchFinishedWhenDone() { finishedBatches++; }
                    void restoreTransitionParams(View v) { v.setAlpha(1); v.setScaleX(1); v.setScaleY(1); v.setTranslationY(0); }
                    static void check(boolean v) { if(!v) throw new AssertionError(); }
                    public static void run() {
                        check(WgtgConfig.messageTransition == 2 && WgtgConfig.chatTransition == 3);
                        WgtgConfig.setMessageTransition(1); WgtgConfig.setChatTransition(2);
                        check(android.content.SharedPreferences.values.get("messageTransition").equals(1));
                        check(android.content.SharedPreferences.values.get("chatTransition").equals(2));
                        WgtgConfig.setMessageTransition(99); WgtgConfig.setChatTransition(-1);
                        check(WgtgConfig.messageTransition == 3 && WgtgConfig.chatTransition == 0);
                        for(int style=1; style<=3; style++) for(boolean cancel : new boolean[]{false,true}) {
                            AnimationChecks a = new AnimationChecks(); ChatMessageCell c = new ChatMessageCell();
                            var h = new RecyclerView.ViewHolder(c); WgtgConfig.setMessageTransition(style);
                            a.activity.animatingMessageObjects.add(c.message);
                            check(a.animateWgtgAdd(h) && a.starts==1 && c.params.messageEntering && c.alpha==0);
                            check(c.y==(style==2?24:0) && c.sx==(style==3?0.94f:1f));
                            check(!a.activity.animatingMessageObjects.contains(c.message));
                            c.animator.finish(cancel);
                            check(c.alpha==1 && c.y==0 && c.sx==1 && c.sy==1 && !c.params.messageEntering);
                            check(a.finishes==1 && a.finishedBatches==1 && a.mAddAnimations.isEmpty() && c.animator.listener==null);
                            a.wgtgChatTransitionStyle=style;
                            a.applyWgtgChatTransition(c,1); check(c.x==0 && c.y==(style==2?32:0) && c.sx==(style==3?0.96f:1));
                            a.applyWgtgChatTransition(c,0); check(c.x==0 && c.y==0 && c.sx==1 && c.sy==1);
                        }
                        AnimationChecks a = new AnimationChecks(); WgtgConfig.setChatTransition(3);
                        check(a.getWgtgChatTransition(new BaseFragment())==0);
                        LiteMode.enabled=false; check(a.getWgtgChatTransition(new ChatActivity())==1);
                        ChatMessageCell c = new ChatMessageCell(); WgtgConfig.setMessageTransition(3);
                        check(a.animateWgtgAdd(new RecyclerView.ViewHolder(c)) && c.sx==1); c.animator.finish(true);
                        LiteMode.enabled=true; c.group=new Object();
                        check(a.animateWgtgAdd(new RecyclerView.ViewHolder(c)) && c.sx==1); c.animator.finish(false);
                        SharedConfig.enabled=false; check(a.getWgtgChatTransition(new ChatActivity())==0);
                        SharedConfig.enabled=true; AndroidUtilities.duration=0; check(a.getWgtgChatTransition(new ChatActivity())==0);
                        for(int disabled=0; disabled<3; disabled++) {
                            WgtgConfig.setSmoothMessages(disabled!=0);
                            SharedConfig.enabled=disabled!=1; AndroidUtilities.duration=disabled==2?0:1;
                            AnimationChecks off = new AnimationChecks(); ChatMessageCell cell = new ChatMessageCell();
                            cell.alpha=0; cell.sx=0.9f; cell.sy=0.9f; cell.y=24;
                            off.activity.animatingMessageObjects.add(cell.message);
                            check(!off.animateAdd(new RecyclerView.ViewHolder(cell)));
                            check(cell.alpha==1 && cell.sx==1 && cell.sy==1 && cell.y==0 && !cell.params.messageEntering);
                            check(off.finishes==1 && off.mPendingAdditions.isEmpty() && off.activity.animatingMessageObjects.isEmpty());
                        }
                    }
                """ + message + navigation + "}") );
            Path output = Files.createTempDirectory(Path.of("/tmp/opencode"), "wgtg-transitions-");
            if (!compiler.getTask(null, manager, null, List.of("-d", output.toString()), null, sources).call()) throw new AssertionError("Compilation failed");
            try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()})) {
                loader.loadClass("AnimationChecks").getMethod("run").invoke(null);
            }
        }
        System.out.println("PASS: production transition methods compile; three styles, completion/cancellation, animator callbacks, navigation endpoints, all disable controls, reduced-motion fallbacks, saved settings and bounds");
    }
}
