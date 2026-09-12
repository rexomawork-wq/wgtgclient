package org.telegram.messenger;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.Text;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

/** Offline text faces. num.otf is intentionally excluded: it only contains numbers. */
public final class WgtgFontConfig {
    public static final String[] NAMES = {
            "Roboto Medium", "Roboto Italic", "Roboto Medium Italic", "Roboto ExtraBold",
            "Roboto Condensed Bold", "Roboto Mono", "Merriweather Bold", "Merriweather 24pt Bold Italic",
            "DejaVu Sans", "DejaVu Sans Bold", "DejaVu Sans Oblique", "DejaVu Sans Bold Oblique",
            "DejaVu Sans ExtraLight", "DejaVu Sans Condensed", "DejaVu Sans Condensed Bold",
            "DejaVu Sans Condensed Oblique", "DejaVu Sans Condensed Bold Oblique",
            "DejaVu Sans Mono", "DejaVu Sans Mono Bold", "DejaVu Sans Mono Oblique", "DejaVu Sans Mono Bold Oblique",
            "DejaVu Serif", "DejaVu Serif Bold", "DejaVu Serif Italic", "DejaVu Serif Bold Italic",
            "DejaVu Serif Condensed", "DejaVu Serif Condensed Bold", "DejaVu Serif Condensed Italic",
            "DejaVu Serif Condensed Bold Italic", "DejaVu Math TeX Gyre"
    };
    private static final String[] ASSETS = {
            "rmedium.ttf", "ritalic.ttf", "rmediumitalic.ttf", "rextrabold.ttf",
            "rcondensedbold.ttf", "rmono.ttf", "mw_bold.ttf", "mw_bolditalic.ttf",
            "dejavu/DejaVuSans.ttf", "dejavu/DejaVuSans-Bold.ttf", "dejavu/DejaVuSans-Oblique.ttf", "dejavu/DejaVuSans-BoldOblique.ttf",
            "dejavu/DejaVuSans-ExtraLight.ttf", "dejavu/DejaVuSansCondensed.ttf", "dejavu/DejaVuSansCondensed-Bold.ttf",
            "dejavu/DejaVuSansCondensed-Oblique.ttf", "dejavu/DejaVuSansCondensed-BoldOblique.ttf",
            "dejavu/DejaVuSansMono.ttf", "dejavu/DejaVuSansMono-Bold.ttf", "dejavu/DejaVuSansMono-Oblique.ttf", "dejavu/DejaVuSansMono-BoldOblique.ttf",
            "dejavu/DejaVuSerif.ttf", "dejavu/DejaVuSerif-Bold.ttf", "dejavu/DejaVuSerif-Italic.ttf", "dejavu/DejaVuSerif-BoldItalic.ttf",
            "dejavu/DejaVuSerifCondensed.ttf", "dejavu/DejaVuSerifCondensed-Bold.ttf", "dejavu/DejaVuSerifCondensed-Italic.ttf",
            "dejavu/DejaVuSerifCondensed-BoldItalic.ttf", "dejavu/DejaVuMathTeXGyre.ttf"
    };
    private static final Typeface[] cache = new Typeface[ASSETS.length];
    private static int selection = -2;
    private static final Typeface[][] styles = new Typeface[ASSETS.length][4];
    private static final IdentityHashMap<Typeface, Typeface> originals = new IdentityHashMap<>();
    private static final IdentityHashMap<Typeface, Integer> ordinaryStyles = new IdentityHashMap<>();
    public static volatile int generation;
    private static final WeakHashMap<Paint, FaceState> paintOriginals = new WeakHashMap<>();
    private static final WeakHashMap<TextView, FaceState> widgetOriginals = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> previews = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> roots = new WeakHashMap<>();
    private static final WeakHashMap<RecyclerView, Boolean> lists = new WeakHashMap<>();
    private static final HashMap<Class<?>, ArrayList<Field>> paintFields = new HashMap<>();

    private static final class FaceState {
        final Typeface original, applied;
        FaceState(Typeface original, Typeface applied) {
            this.original = original;
            this.applied = applied;
        }
    }

    private WgtgFontConfig() {}

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("wgtg", 0);
    }

    public static synchronized int selected() {
        if (selection != -2) return selection;
        if (ApplicationLoader.applicationContext == null) return -1;
        String asset = preferences().getString("messageFont", "");
        for (int i = 0; i < ASSETS.length; i++) {
            if (ASSETS[i].equals(asset)) return selection = i;
        }
        return selection = -1;
    }

    public static synchronized Typeface typeface(int index) {
        if (index < 0 || index >= ASSETS.length) return Typeface.DEFAULT;
        if (cache[index] == null) {
            // Load the actual asset, bypassing AndroidUtilities' system-font substitutions.
            try {
                cache[index] = Typeface.createFromAsset(ApplicationLoader.applicationContext.getAssets(), "fonts/" + ASSETS[index]);
            } catch (RuntimeException e) {
                FileLog.e(e);
                return cache[index] = Typeface.DEFAULT;
            }
        }
        return cache[index];
    }

    public static Typeface selectedTypeface() {
        return typeface(selected());
    }

    public static void select(int index) {
        if (index < -1 || index >= ASSETS.length) throw new IllegalArgumentException("font index");
        typeface(index);
        preferences().edit().putString("messageFont", index < 0 ? "" : ASSETS[index]).apply();
        synchronized (WgtgFontConfig.class) {
            selection = index;
            generation++;
        }
        applyThemeFonts();
        for (View root : new ArrayList<>(roots.keySet())) {
            applyTree(root, true);
            root.requestLayout();
            root.invalidate();
        }
    }

    // Only ordinary UI font assets participate. Mono, number and icon assets stay explicit.
    public static int assetStyle(String path) {
        if ("fonts/rmedium.ttf".equals(path) || "fonts/rbold.ttf".equals(path)
                || "fonts/rextrabold.ttf".equals(path) || "fonts/rcondensedbold.ttf".equals(path)) return Typeface.BOLD;
        if ("fonts/ritalic.ttf".equals(path)) return Typeface.ITALIC;
        if ("fonts/rmediumitalic.ttf".equals(path)) return Typeface.BOLD_ITALIC;
        return -1;
    }

    public static synchronized Typeface override(Typeface original, int style) {
        if (original != null) ordinaryStyles.put(original, style);
        int index = selected();
        if (index < 0) return original;
        style &= 3;
        if (styles[index][style] == null) {
            Typeface base = typeface(index);
            if (base == Typeface.DEFAULT) return original;
            // Keep semantic styles distinct even when the selected asset itself is bold/italic.
            // The asset supplies its glyph design; the requested style supplies UI emphasis.
            styles[index][style] = Typeface.create(base, style);
        }
        Typeface result = styles[index][style];
        // Widgets/paints keep exact originals separately; this map identifies shared helper results.
        if (!originals.containsKey(result)) originals.put(result, original);
        return result;
    }

    public static synchronized Typeface resolve(Typeface face) {
        if (originals.containsKey(face)) face = originals.get(face);
        Integer knownStyle = ordinaryStyles.get(face);
        if (knownStyle != null) return override(face, knownStyle);
        if (face == null) return override(null, Typeface.NORMAL);
        for (int style = 0; style < 4; style++) {
            if (face.equals(Typeface.create("monospace", style))) return face;
            if (face.equals(Typeface.create("sans-serif-medium", style))) return override(face, style | Typeface.BOLD);
            if (face.equals(Typeface.defaultFromStyle(style))
                    || face.equals(Typeface.create("sans-serif", style))) {
                return override(face, style | face.getStyle());
            }
        }
        return face;
    }

    public static void preservePreview(View view) {
        previews.put(view, true);
    }

    private static synchronized boolean applyPaint(Paint paint) {
        if (paint == null) return false;
        Typeface current = paint.getTypeface();
        FaceState state = paintOriginals.get(paint);
        Typeface original = state != null && state.applied == current ? state.original : current;
        Typeface replacement = resolve(original);
        if (replacement != current) {
            paintOriginals.put(paint, new FaceState(original, replacement));
            paint.setTypeface(replacement);
            return true;
        }
        return false;
    }

    // App-owned fields only, never Android's hidden Typeface or View internals. Cache discovery.
    private static synchronized ArrayList<Field> fields(Class<?> type) {
        ArrayList<Field> result = paintFields.get(type);
        if (result != null) return result;
        result = new ArrayList<>();
        for (Class<?> c = type; c != null && c.getName().startsWith("org.telegram."); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if ((field.getType() == TextPaint.class || field.getType() == TextPaint[].class
                        || field.getType() == AnimatedTextView.AnimatedTextDrawable.class || field.getType() == Text.class)
                        && !field.getName().toLowerCase(java.util.Locale.ROOT).contains("emoji")) {
                    field.setAccessible(true);
                    result.add(field);
                }
            }
        }
        paintFields.put(type, result);
        return result;
    }

    private static boolean applyPaints(Object owner, Class<?> type) {
        boolean changed = false;
        for (Field field : fields(type)) {
            if (owner == null && !Modifier.isStatic(field.getModifiers())) continue;
            try {
                Object value = field.get(owner);
                if (value instanceof TextPaint) changed |= applyPaint((TextPaint) value);
                else if (value instanceof TextPaint[]) {
                    for (TextPaint paint : (TextPaint[]) value) changed |= applyPaint(paint);
                } else if (value instanceof AnimatedTextView.AnimatedTextDrawable) {
                    AnimatedTextView.AnimatedTextDrawable drawable = (AnimatedTextView.AnimatedTextDrawable) value;
                    if (applyPaint(drawable.getPaint())) {
                        drawable.setText(drawable.getText(), false);
                        changed = true;
                    }
                } else if (value instanceof Text) {
                    Text text = (Text) value;
                    if (applyPaint(text.paint)) {
                        text.setText(text.getText());
                        changed = true;
                    }
                }
            } catch (IllegalAccessException e) {
                FileLog.e(e);
            }
        }
        return changed;
    }

    public static void applyThemeFonts() {
        applyPaints(null, Theme.class);
    }

    public static void applyTree(View view) {
        applyTree(view, false);
    }

    private static void applyTree(View view, boolean refresh) {
        if (!refresh && selected() < 0 && generation == 0) return;
        if (view == null || previews.containsKey(view)) return;
        if (view instanceof RecyclerView && !lists.containsKey(view)) {
            RecyclerView list = (RecyclerView) view;
            lists.put(list, true);
            list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
                public void onChildViewAttachedToWindow(View child) { applyTree(child); }
                public void onChildViewDetachedFromWindow(View child) {}
            });
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface current = text.getTypeface();
            FaceState state = widgetOriginals.get(text);
            Typeface original = state != null && state.applied == current ? state.original : current;
            Typeface replacement = resolve(original);
            if (current != replacement) {
                widgetOriginals.put(text, new FaceState(original, replacement));
                text.setTypeface(replacement);
            }
        } else {
            Typeface before = view instanceof SimpleTextView ? ((SimpleTextView) view).getPaint().getTypeface() : null;
            if (applyPaints(view, view.getClass())) {
                view.requestLayout();
                view.invalidate();
            }
            if (view instanceof SimpleTextView && before != ((SimpleTextView) view).getPaint().getTypeface()) {
                ((SimpleTextView) view).setText(((SimpleTextView) view).getText(), true);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i), refresh);
        }
        if (refresh && view instanceof RecyclerView) {
            RecyclerView list = (RecyclerView) view;
            if (list.getAdapter() != null) list.getAdapter().notifyDataSetChanged();
        }
    }

    public static void watch(View root) {
        if (root == null || roots.containsKey(root)) return;
        roots.put(root, true);
        // Layout events include fragment insertion and programmatic children. No frame timer or polling.
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> applyTree(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) {
                view.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                view.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                applyTree(view);
            }
            public void onViewDetachedFromWindow(View view) {
                if (view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            }
        });
        applyTree(root);
    }

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle state) { watch(activity.getWindow().getDecorView()); }
            public void onActivityResumed(Activity activity) { watch(activity.getWindow().getDecorView()); applyTree(activity.getWindow().getDecorView()); }
            public void onActivityDestroyed(Activity activity) { roots.remove(activity.getWindow().getDecorView()); }
            public void onActivityStarted(Activity activity) {}
            public void onActivityPaused(Activity activity) {}
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        });
    }
}
