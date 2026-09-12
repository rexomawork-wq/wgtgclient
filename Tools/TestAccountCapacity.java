import com.sun.source.util.JavacTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import javax.tools.*;

// Run from the root: java Tools/TestAccountCapacity.java (requires a JDK and c++).
class TestAccountCapacity {
    static String method(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError(signature);
        int brace = source.indexOf('{', start), depth = 1, end = brace + 1;
        while (depth > 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            if (c == '}') depth--;
        }
        return source.substring(start, end);
    }

    static void run(String... command) throws Exception {
        if (new ProcessBuilder(command).inheritIO().start().waitFor() != 0) {
            throw new AssertionError(String.join(" ", command));
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("TMessagesProj/src/main/java/org/telegram");
        String config = Files.readString(root.resolve("messenger/UserConfig.java"));
        var capacity = Pattern.compile("MAX_ACCOUNT_COUNT = (\\d+);").matcher(config);
        if (!capacity.find()) throw new AssertionError("Missing fixed capacity");
        int count = Integer.parseInt(capacity.group(1));
        if (count < 100) throw new AssertionError("Practical account capacity");
        String defines = Files.readString(Path.of("TMessagesProj/jni/tgnet/Defines.h"));
        if (!Pattern.compile("#define MAX_ACCOUNT_COUNT " + count + "\\b").matcher(defines).find()) {
            throw new AssertionError("Java/native capacities disagree");
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var files = List.of("messenger/UserConfig.java", "messenger/ApplicationLoader.java",
                "messenger/NotificationsController.java", "tgnet/ConnectionsManager.java",
                "messenger/LocationController.java", "messenger/LocationSharingService.java",
                "messenger/StopLiveLocationReceiver.java", "ui/Components/Premium/DoubledLimitsBottomSheet.java",
                "ui/MainTabsActivity.java", "ui/UserInfoActivity.java", "ui/LogoutActivity.java");
            ((JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(files.stream().map(root::resolve).toList()))).parse();
            for (var d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(d.toString());
            }
        }
        for (String ui : List.of("MainTabsActivity", "UserInfoActivity", "LogoutActivity")) {
            String text = Files.readString(root.resolve("ui/" + ui + ".java"));
            if (!text.contains("UserConfig.getFreeAccount()") || text.contains("TYPE_ACCOUNTS")) {
                throw new AssertionError("Account entry point still gated: " + ui);
            }
        }
        String notifications = Files.readString(root.resolve("messenger/NotificationsController.java"));
        if (Pattern.compile("notificationManager\\.(?:notify|cancel)\\((?:notificationId|id|wearNotificationsIds)").matcher(notifications).find()) {
            throw new AssertionError("Notification post/cancel must use matching account tags");
        }
        Path temp = Files.createTempDirectory("account-capacity-test-");
        Path java = temp.resolve("AccountSlots.java");
        Files.writeString(java, """
            class AccountSlots {
                static final int MAX_ACCOUNT_COUNT = %d;
                static final boolean[] active = new boolean[MAX_ACCOUNT_COUNT];
                static int queried;
                static AccountSlots getInstance(int a) { queried = a; return new AccountSlots(); }
                boolean isClientActivated() { return active[queried]; }
                %s
                %s
                public static void main(String[] args) {
                    if (getMaxAccountCount() != active.length) throw new AssertionError();
                    for (int a = 0; a < active.length; a++) {
                        if (getFreeAccount() != a) throw new AssertionError("allocation " + a);
                        active[a] = true;
                    }
                    if (getFreeAccount() != -1) throw new AssertionError("full capacity");
                    active[2] = false;
                    if (getFreeAccount() != 2) throw new AssertionError("reuse logout hole");
                    System.out.println("Java slots: allocation past old cap, full capacity, hole reuse passed");
                }
            }
            """.formatted(count, method(config, "public static int getFreeAccount()"),
                method(config, "public static int getMaxAccountCount()")));
        run("java", java.toString());
        String nativeSource = Files.readString(Path.of("TMessagesProj/jni/tgnet/ConnectionsManager.cpp"));
        Path cpp = temp.resolve("accounts.cpp");
        Files.writeString(cpp, """
            #include <array>
            #include <memory>
            #include <mutex>
            #include <thread>
            #include <vector>
            #include <atomic>
            #include <cassert>
            #include <cstdlib>
            #define MAX_ACCOUNT_COUNT %d
            std::atomic<int> constructed{0};
            struct ConnectionsManager {
                int id;
                explicit ConnectionsManager(int n): id(n) { ++constructed; }
                static ConnectionsManager& getInstance(int32_t);
            };
            %s
            int main() {
                assert(constructed == 0);
                std::vector<std::thread> threads;
                for (int t = 0; t < 8; ++t) threads.emplace_back([] {
                    for (int a = 0; a < MAX_ACCOUNT_COUNT; ++a) {
                        auto& instance = ConnectionsManager::getInstance(a);
                        assert(instance.id == a);
                        assert(&instance == &ConnectionsManager::getInstance(a));
                    }
                });
                for (auto& t : threads) t.join();
                assert(constructed == MAX_ACCOUNT_COUNT);
            }
            """.formatted(count, method(nativeSource, "ConnectionsManager& ConnectionsManager::getInstance(")));
        Path binary = temp.resolve("accounts");
        run("c++", "-std=c++14", "-pthread", cpp.toString(), "-o", binary.toString());
        run(binary.toString());
        System.out.println("Native slots: unique identities, lazy construction and concurrent access passed; Java syntax passed");
    }
}
