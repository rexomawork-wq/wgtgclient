"""Compile the production font controller against behavioral JVM stubs, without an SDK.

Run with JAVA_HOME pointing at a JDK. Fixtures live only in a temporary directory.
The stubs test policy/lifecycle, not Android shaping, rasterization or actual layout.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "TMessagesProj/src/main/java/org/telegram"
STUBS = {
    "android/content/SharedPreferences.java": """public interface SharedPreferences {
 String getString(String k, String d); Editor edit();
 interface Editor { Editor putString(String k, String v); void apply(); }
}""",
    "android/content/res/AssetManager.java": "public class AssetManager {}",
    "android/content/Context.java": """public class Context {
 public static String stored = "";
 public SharedPreferences getSharedPreferences(String n, int m) { return new SharedPreferences() {
  public String getString(String k, String d) { return stored; }
  public Editor edit() { return new Editor() {
   public Editor putString(String k, String v) { stored=v; return this; }
   public void apply() {}
  }; }
 }; }
 public android.content.res.AssetManager getAssets() { return new android.content.res.AssetManager(); }
}""",
    "android/graphics/Typeface.java": """public class Typeface {
 public static final int NORMAL=0, BOLD=1, ITALIC=2, BOLD_ITALIC=3;
 private static final java.util.Map<String, Typeface> cache = new java.util.HashMap<>();
 public final String family; private final int style;
 private Typeface(String f,int s) { family=f; style=s; }
 public static Typeface create(String family,int style) {
  if (family==null || family.equals("sans-serif")) family="default";
  final String f=family; return cache.computeIfAbsent(f+style,k->new Typeface(f,style));
 }
 public static Typeface create(Typeface t,int s) { return create(t==null?"default":t.family,s); }
 public static Typeface defaultFromStyle(int s) { return create("default",s); }
 public static final Typeface DEFAULT=defaultFromStyle(0), DEFAULT_BOLD=defaultFromStyle(1), MONOSPACE=create("monospace",0);
 public static Typeface createFromAsset(android.content.res.AssetManager a,String p) {
  if (p.contains("Math")) throw new RuntimeException("simulated broken asset");
  return create(p,(p.contains("Bold")?1:0) | (p.contains("Italic")||p.contains("Oblique")?2:0));
 }
 public int getStyle() { return style; }
}""",
    "android/graphics/Paint.java": """public class Paint {
 private Typeface face; public Typeface getTypeface() { return face; }
 public Typeface setTypeface(Typeface f) { face=f; return f; }
}""",
    "android/text/TextPaint.java": "public class TextPaint extends android.graphics.Paint {}",
    "android/os/Bundle.java": "public class Bundle {}",
    "android/view/ViewTreeObserver.java": """public class ViewTreeObserver {
 public interface OnGlobalLayoutListener { void onGlobalLayout(); }
 public final java.util.List<OnGlobalLayoutListener> listeners=new java.util.ArrayList<>();
 public void addOnGlobalLayoutListener(OnGlobalLayoutListener l) { listeners.add(l); }
 public void removeOnGlobalLayoutListener(OnGlobalLayoutListener l) { listeners.remove(l); }
 public boolean isAlive() { return true; }
 public void layout() { for (OnGlobalLayoutListener l:new java.util.ArrayList<>(listeners)) l.onGlobalLayout(); }
}""",
    "android/view/View.java": """public class View {
 public interface OnAttachStateChangeListener { void onViewAttachedToWindow(View v); void onViewDetachedFromWindow(View v); }
 public final java.util.List<OnAttachStateChangeListener> attach=new java.util.ArrayList<>();
 private final ViewTreeObserver observer=new ViewTreeObserver();
 public ViewTreeObserver getViewTreeObserver() { return observer; }
 public void addOnAttachStateChangeListener(OnAttachStateChangeListener l) { attach.add(l); }
 public void requestLayout() {} public void invalidate() {}
}""",
    "android/view/ViewGroup.java": """public class ViewGroup extends View {
 public final java.util.List<View> children=new java.util.ArrayList<>();
 public int getChildCount() { return children.size(); } public View getChildAt(int i) { return children.get(i); }
}""",
    "android/widget/TextView.java": """public class TextView extends android.view.View {
 private android.graphics.Typeface face=android.graphics.Typeface.DEFAULT; public int writes;
 public android.graphics.Typeface getTypeface() { return face; }
 public void setTypeface(android.graphics.Typeface f) { face=f; writes++; }
}""",
    "android/widget/EditText.java": "public class EditText extends TextView {}",
    "android/view/Window.java": """public class Window {
 private final View root=new ViewGroup(); public View getDecorView() { return root; }
}""",
    "android/app/Activity.java": """public class Activity {
 private final android.view.Window window=new android.view.Window();
 public android.view.Window getWindow() { return window; }
}""",
    "android/app/Application.java": """public class Application {
 public ActivityLifecycleCallbacks callbacks;
 public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks c) { callbacks=c; }
 public interface ActivityLifecycleCallbacks {
  void onActivityCreated(Activity a,android.os.Bundle b); void onActivityResumed(Activity a);
  void onActivityDestroyed(Activity a); void onActivityStarted(Activity a);
  void onActivityPaused(Activity a); void onActivityStopped(Activity a);
  void onActivitySaveInstanceState(Activity a,android.os.Bundle b);
 }
}""",
    "androidx/recyclerview/widget/RecyclerView.java": """public class RecyclerView extends android.view.ViewGroup {
 public interface OnChildAttachStateChangeListener { void onChildViewAttachedToWindow(android.view.View v); void onChildViewDetachedFromWindow(android.view.View v); }
 public OnChildAttachStateChangeListener listener;
 public void addOnChildAttachStateChangeListener(OnChildAttachStateChangeListener l) { listener=l; }
 public static class Adapter { public int changes; public void notifyDataSetChanged() { changes++; } }
 private final Adapter adapter=new Adapter(); public Adapter getAdapter() { return adapter; }
}""",
    "org/telegram/messenger/ApplicationLoader.java": """public class ApplicationLoader {
 public static android.content.Context applicationContext=new android.content.Context();
}""",
    "org/telegram/messenger/FileLog.java": "public class FileLog { public static void e(Exception e) {} }",
    "org/telegram/ui/ActionBar/Theme.java": """public class Theme {
 public static android.text.TextPaint message=new android.text.TextPaint(), emoji=new android.text.TextPaint(), code=new android.text.TextPaint();
 public static android.text.TextPaint[] messages={new android.text.TextPaint()};
 static { code.setTypeface(android.graphics.Typeface.MONOSPACE); }
}""",
    "org/telegram/ui/ActionBar/SimpleTextView.java": """public class SimpleTextView extends android.view.View {
 private final android.text.TextPaint textPaint=new android.text.TextPaint(); public int layouts;
 public android.text.TextPaint getPaint() { return textPaint; }
 public CharSequence getText() { return "hello"; }
 public boolean setText(CharSequence t,boolean force) { layouts++; return true; }
}""",
    "org/telegram/ui/Components/AnimatedTextView.java": """public class AnimatedTextView extends android.view.View {
 public final AnimatedTextDrawable drawable=new AnimatedTextDrawable();
 public static class AnimatedTextDrawable {
  private final android.text.TextPaint paint=new android.text.TextPaint(); public int layouts;
  public android.text.TextPaint getPaint() { return paint; }
  public CharSequence getText() { return "animated"; }
  public void setText(CharSequence s,boolean a) { layouts++; }
 }
}""",
    "org/telegram/ui/Components/Text.java": """public class Text {
 public final android.text.TextPaint paint=new android.text.TextPaint();
 public CharSequence getText() { return "text"; } public void setText(CharSequence t) {}
}""",
}

TEST = """
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.telegram.messenger.WgtgFontConfig;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.AnimatedTextView;
public class TestFonts {
 static int checks;
 static void check(boolean b,String s) { checks++; if(!b) throw new AssertionError(s); }
 static void restart() throws Exception {
  java.lang.reflect.Field f=WgtgFontConfig.class.getDeclaredField("selection"); f.setAccessible(true); f.setInt(null,-2);
 }
 public static void main(String[] args) throws Exception {
  android.content.Context.stored="not-a-font"; restart();
  check(WgtgFontConfig.selected()==-1,"unknown preference defaults");
  check(WgtgFontConfig.resolve(Typeface.DEFAULT)==Typeface.DEFAULT,"default untouched");
  Typeface medium=Typeface.create("bundled-medium",1);
  WgtgFontConfig.override(medium,1);
  ViewGroup root=new ViewGroup(); TextView regular=new TextView(), bold=new TextView(), mono=new TextView(), icon=new TextView(), sample=new TextView();
  EditText edit=new EditText(); SimpleTextView simple=new SimpleTextView(); AnimatedTextView animated=new AnimatedTextView();
  androidx.recyclerview.widget.RecyclerView list=new androidx.recyclerview.widget.RecyclerView();
  bold.setTypeface(medium); mono.setTypeface(Typeface.MONOSPACE); icon.setTypeface(Typeface.create("icon",0));
  WgtgFontConfig.preservePreview(sample);
  java.util.Collections.addAll(root.children,regular,bold,mono,icon,sample,edit,simple,animated,list);
  WgtgFontConfig.watch(root); WgtgFontConfig.watch(root);
  check(root.getViewTreeObserver().listeners.size()==1,"one listener");
  WgtgFontConfig.select(8);
  check(regular.getTypeface().family.contains("DejaVuSans"),"default TextView");
  check(edit.getTypeface()==regular.getTypeface(),"EditText");
  check(bold.getTypeface().getStyle()==1,"medium preserved");
  check(simple.getPaint().getTypeface()==regular.getTypeface() && simple.layouts>0,"simple view reflow");
  check(animated.drawable.getPaint().getTypeface()==regular.getTypeface() && animated.drawable.layouts>0,"animated reflow");
  check(Theme.message.getTypeface()==regular.getTypeface() && Theme.messages[0].getTypeface()==regular.getTypeface(),"theme arrays");
  check(Theme.emoji.getTypeface()==null && Theme.code.getTypeface()==Typeface.MONOSPACE,"emoji/code paints");
  check(mono.getTypeface()==Typeface.MONOSPACE && icon.getTypeface().family.equals("icon"),"explicit faces");
  check(sample.getTypeface()==Typeface.DEFAULT,"preview opt-out");
  int writes=regular.writes; root.getViewTreeObserver().layout();
  check(regular.writes==writes,"unchanged layouts do not reset text");
  TextView late=new TextView(); root.children.add(late); root.getViewTreeObserver().layout();
  check(late.getTypeface()==regular.getTypeface(),"dynamic child");
  TextView recycled=new TextView(); list.listener.onChildViewAttachedToWindow(recycled);
  check(recycled.getTypeface()==regular.getTypeface(),"plain RecyclerView child attachment");
  regular.setTypeface(Typeface.defaultFromStyle(2)); root.getViewTreeObserver().layout();
  check(regular.getTypeface().getStyle()==2,"rebound style");
  regular.setTypeface(Typeface.MONOSPACE); root.getViewTreeObserver().layout();
  check(regular.getTypeface()==Typeface.MONOSPACE,"rebound code");
  restart(); check(WgtgFontConfig.selected()==8,"persisted selection");
  WgtgFontConfig.select(21);
  check(edit.getTypeface().family.contains("DejaVuSerif"),"second selection");
  check(list.getAdapter().changes==2,"visible list rebind");
  root.children.remove(late);
  WgtgFontConfig.select(-1);
  check(edit.getTypeface()==Typeface.DEFAULT && bold.getTypeface()==medium,"exact widget restoration");
  check(Theme.message.getTypeface()==null && simple.getPaint().getTypeface()==null,"paint restoration");
  root.children.add(late); root.getViewTreeObserver().layout();
  check(late.getTypeface()==Typeface.DEFAULT,"detached cell restored on return");
  check(regular.getTypeface()==Typeface.MONOSPACE,"rebound code remains explicit");
  root.attach.get(0).onViewDetachedFromWindow(root);
  check(root.getViewTreeObserver().listeners.isEmpty(),"detach removes listener");
  root.attach.get(0).onViewAttachedToWindow(root);
  check(root.getViewTreeObserver().listeners.size()==1,"reattach installs listener");
  restart(); check(WgtgFontConfig.selected()==-1,"default persisted");
  WgtgFontConfig.select(9);
  check(WgtgFontConfig.resolve(Typeface.DEFAULT)!=WgtgFontConfig.override(medium,1),"intrinsically bold face retains semantic styles");
  TextView createdBold=new TextView(); createdBold.setTypeface(WgtgFontConfig.override(medium,1));
  root.children.add(createdBold); root.getViewTreeObserver().layout();
  WgtgFontConfig.select(-1);
  check(createdBold.getTypeface()==medium,"created under override restores emphasis");
  for(int i=0;i<30;i++) { WgtgFontConfig.select(i); check(WgtgFontConfig.selected()==i,"all faces selectable"); }
  check(WgtgFontConfig.resolve(Typeface.DEFAULT)==Typeface.DEFAULT,"broken asset fails safely");
  for(String p:new String[]{"fonts/rmono.ttf","fonts/num.otf","fonts/icon.ttf","fonts/mw_bold.ttf"})
   check(WgtgFontConfig.assetStyle(p)==-1,"special asset "+p);
  check(WgtgFontConfig.assetStyle("fonts/rmediumitalic.ttf")==3,"bold italic route");
  boolean rejected=false; try { WgtgFontConfig.select(30); } catch(IllegalArgumentException e) { rejected=true; }
  check(rejected,"bounds");
  android.app.Application app=new android.app.Application(); WgtgFontConfig.install(app);
  WgtgFontConfig.select(8); android.app.Activity activity=new android.app.Activity();
  app.callbacks.onActivityCreated(activity,null);
  TextView activityText=new TextView(); ((ViewGroup)activity.getWindow().getDecorView()).children.add(activityText);
  activity.getWindow().getDecorView().getViewTreeObserver().layout();
  check(activityText.getTypeface().family.contains("DejaVuSans"),"activity lifecycle");
  System.out.println("PASS: "+checks+" font policy/lifecycle assertions");
 }
}
"""

PARSER = """
import javax.tools.*;
import com.sun.source.util.JavacTask;
public class ParseFonts {
 public static void main(String[] args) throws Exception {
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
  DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
  try(StandardJavaFileManager files=compiler.getStandardFileManager(diagnostics,null,null)) {
   JavacTask task=(JavacTask)compiler.getTask(null,files,diagnostics,java.util.List.of("-proc:none"),null,
       files.getJavaFileObjectsFromStrings(java.util.List.of(args)));
   task.parse();
   for(Diagnostic<?> d:diagnostics.getDiagnostics()) if(d.getKind()==Diagnostic.Kind.ERROR) throw new AssertionError(d);
  }
  System.out.println("PASS: Java parser checked "+args.length+" production integration files");
 }
}
"""

with tempfile.TemporaryDirectory(prefix="wgtg-fonts-") as directory:
    temp = Path(directory)
    sources = []
    for name, body in STUBS.items():
        file = temp / name
        file.parent.mkdir(parents=True, exist_ok=True)
        package = name.rsplit("/", 1)[0].replace("/", ".")
        file.write_text(f"package {package};\n{body}")
        sources.append(str(file))
    test = temp / "TestFonts.java"
    test.write_text(TEST)
    parser = temp / "ParseFonts.java"
    parser.write_text(PARSER)
    jdk = Path(os.environ.get("JAVA_HOME", "/usr")) / "bin"
    subprocess.run([str(jdk / "javac"), "-d", str(temp), *sources,
                    str(SOURCE / "messenger/WgtgFontConfig.java"), str(test), str(parser)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(temp), "TestFonts"], check=True)
    integration = ["messenger/AndroidUtilities.java", "messenger/ApplicationLoader.java",
                   "messenger/MessageObject.java", "ui/WgtgFontSettingsActivity.java",
                   "ui/ActionBar/Theme.java", "ui/ActionBar/BaseFragment.java",
                   "ui/ActionBar/AlertDialog.java", "ui/ActionBar/BottomSheet.java",
                   "ui/ActionBar/ActionBarPopupWindow.java", "ui/ActionBar/SimpleTextView.java",
                   "ui/Components/AnimatedTextView.java", "ui/Components/RecyclerListView.java",
                   "ui/Components/Text.java"]
    subprocess.run([str(jdk / "java"), "-cp", str(temp), "ParseFonts",
                    *(str(SOURCE / file) for file in integration)], check=True)

config = (SOURCE / "messenger/WgtgFontConfig.java").read_text()
assets = re.findall(r'"([^"\n]+\.(?:ttf|otf))"', config.split("private static final String[] ASSETS = {")[1].split("};")[0])
assert len(assets) == 30 and len(set(assets)) == 30
for asset in assets:
    file = ROOT / "TMessagesProj/src/main/assets/fonts" / asset
    assert file.read_bytes()[:4] in (b"\x00\x01\x00\x00", b"OTTO"), file
print("PASS: 30 unique bundled font assets with valid SFNT signatures")
