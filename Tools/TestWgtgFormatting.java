import com.sun.source.util.JavacTask;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.*;

// Run from the repository root with JDK 17: java Tools/TestWgtgFormatting.java
class TestWgtgFormatting {
    static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return text; }
        };
    }

    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path root = Path.of("TMessagesProj/src/main/java/org/telegram");
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            List<Path> paths = List.of("messenger/WgtgConfig.java", "messenger/WgtgMessageFormatting.java",
                    "messenger/SendMessagesHelper.java", "ui/WgtgSettingsActivity.java",
                    "ui/ActionBar/Theme.java", "ui/ActionBar/ActionBarLayout.java",
                    "ui/recyclerview/ChatListItemAnimator.java").stream().map(root::resolve).toList();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask parser = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(paths));
            parser.parse();
            for (Diagnostic<?> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(diagnostic);
            }
            for (String locale : List.of("values", "values-ru")) {
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                        Path.of("TMessagesProj/src/main/res", locale, "wgtg_settings.xml").toFile());
            }
            ArrayList<JavaFileObject> sources = new ArrayList<>();
            manager.getJavaFileObjectsFromPaths(List.of(root.resolve("messenger/WgtgMessageFormatting.java"))).forEach(sources::add);
            sources.add(source("org.telegram.tgnet.TLRPC", """
                    package org.telegram.tgnet;
                    public class TLRPC {
                        public static class MessageEntity { public int offset, length; }
                        public static class TL_messageEntityBold extends MessageEntity {}
                    }
                    """));
            sources.add(source("FormattingChecks", """
                    import java.util.*;
                    import org.telegram.tgnet.TLRPC.*;
                    import org.telegram.messenger.WgtgMessageFormatting;
                    public class FormattingChecks {
                        static MessageEntity span(int start, int length) {
                            MessageEntity e = new MessageEntity(); e.offset = start; e.length = length; return e;
                        }
                        static void check(boolean ok) { if (!ok) throw new AssertionError(); }
                        public static void run() {
                            check(WgtgMessageFormatting.boldUnformatted(null, null) == null);
                            check(WgtgMessageFormatting.boldUnformatted("", null) == null);
                            String text = "a\\ud83d\\ude00bcdefgh";
                            var plain = WgtgMessageFormatting.boldUnformatted(text, null);
                            check(plain.size() == 1 && plain.get(0).offset == 0 && plain.get(0).length == text.length());
                            var original = new ArrayList<MessageEntity>(List.of(span(6, 2), span(1, 2), span(5, 2)));
                            var formatted = WgtgMessageFormatting.boldUnformatted(text, original);
                            check(original.size() == 3 && original.get(0).offset == 6);
                            check(formatted.containsAll(original));
                            int[] expected = {0, 1, 3, 2, 8, 2};
                            int n = 0;
                            for (var entity : formatted) {
                                if (!(entity instanceof TL_messageEntityBold)) continue;
                                check(entity.offset == expected[n++] && entity.length == expected[n++]);
                                check(entity.length > 0 && entity.offset + entity.length <= text.length());
                                for (var old : original) {
                                    check(entity.offset + entity.length <= old.offset || entity.offset >= old.offset + old.length);
                                }
                            }
                            check(n == expected.length);
                            var again = WgtgMessageFormatting.boldUnformatted(text, formatted);
                            check(again.size() == formatted.size() && again.containsAll(formatted));
                            var all = new ArrayList<MessageEntity>(List.of(span(0, text.length())));
                            check(WgtgMessageFormatting.boldUnformatted(text, all).equals(all));
                        }
                    }
                    """));
            Path output = Files.createTempDirectory(Path.of("/tmp/opencode"), "wgtg-formatting-");
            if (!compiler.getTask(null, manager, null, List.of("-d", output.toString()), null, sources).call()) {
                throw new AssertionError("Formatting compilation failed");
            }
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
                loader.loadClass("FormattingChecks").getMethod("run").invoke(null);
            }
        }
        System.out.println("PASS: Java syntax, EN/RU XML, production formatting compilation, UTF-16 offsets, existing/overlapping entities, empty text, input preservation and idempotence");
    }
}
