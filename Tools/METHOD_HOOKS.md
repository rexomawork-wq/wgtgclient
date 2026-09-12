# Method hook runtime contract

Integration for the agent owning `base_plugin.py` and `wgtg_plugin_engine.py`:

- Re-export `MethodHook`, `MethodReplacement`, `HookFilter`, and `hook_filters` from `hook_utils`.
- Delegate `BasePlugin.hook_method(self, member, hook, priority=50)` to
  `hook_utils.hook_method(self, member, hook, priority)`. The owner argument is required.
- `hook_utils.unhook_method(handle)` and `handle.unhook()` are idempotent.
- Call `hook_utils.unhook_all(instance)` in unload cleanup even when the plugin's
  unload callback raises. Failed initialization/load must follow the same cleanup.
  This also removes hooks registered directly through `hook_utils` and permanently
  closes the old instance to further registration. Reload must use a new instance.
- `MethodHook.before_hooked_method(param)` and `after_hooked_method(param)` default
  to no action. `MethodReplacement.replace_hooked_method(param)` returns the result.
  `BaseHook` is supplied by `base_plugin`; it accepts callable filter lists.

`param` exposes `method`, mutable `thisObject` and `args`, `getResult()`,
`setResult(value)`, `getThrowable()`, `setThrowable(java_throwable)`,
`hasThrowable()`, and `getResultOrThrowable()`. Setting a result (including `None`
for references/void) clears an exception; setting an exception clears the result.
In before callbacks either setter skips the original and lower priority callbacks.
After callbacks run in reverse order only for entered, still-active hooks. Equal
priorities preserve registration order. Normal callback failures are logged and
their result/exception changes discarded, as in Xposed. Argument changes remain.
Replacement failures become the invocation's exception: Java throwables retain
identity, other Python exceptions become `java.lang.RuntimeException` with the
Python error text. Java original exceptions are unwrapped from reflection.

Python numeric values are converted to the reflected primitive type for arguments
and results. Out-of-range integers and null primitive values are rejected. Reference
values must be assignable to the declared type; use Chaquopy Java objects/arrays for
Java references. Char accepts a Java Character or one-character string.

## Engine selection and evidence

Verified Maven Central metadata and downloaded the actual core AAR:

- https://repo.maven.apache.org/maven2/top/canyie/pine/core/maven-metadata.xml:
  latest release `0.3.0`.
- https://repo.maven.apache.org/maven2/top/canyie/pine/enhances/maven-metadata.xml:
  latest release `0.1.0`. Not needed: pending static-class hooks are out of scope.
- https://repo.maven.apache.org/maven2/top/canyie/pine/xposed/maven-metadata.xml:
  latest release `0.2.0`. Not used: its per-thread ExtData implementation does not
  stack recursive invocations. The bridge supplies Xposed-style semantics over core.
- https://github.com/canyie/pine/blob/master/README.md documents ART through Android
  15 Beta 4 and ARM/thumb-2, with device/system and concurrency caveats. This is an
  upstream claim, not device verification of this app or all API 35 builds.
- https://github.com/canyie/pine/blob/master/core/src/main/java/top/canyie/pine/Pine.java
  documents the real `hook(Member, MethodHook)` and `CallFrame.invokeOriginalMethod`
  APIs used here. Local Java tests compile against the published AAR's classes.jar.
- https://github.com/LSPosed/LSPlant documents a native hook library requiring JNI
  initialization, an inline hook provider, and ART symbol resolution. Pine core is
  the smaller embeddable integration for this Java/Python runtime. This does not
  embed LSPlant, LSPatch, or a complete Xposed module/resource-loading environment.

The published AAR contains only `jni/armeabi-v7a/libpine.so` and
`jni/arm64-v8a/libpine.so`; ARM64 ELF LOAD alignment is 0x1000 (4 KB).
The runtime allows ARM processes, API 24-35, and 4 KB pages only. This is a
conservative eligibility check, not a guarantee of device compatibility. Other
ABIs can still run the app; method hook registration throws an explicit error.
API 36+, x86/x86_64, and 16 KB pages require a different/newer native engine build.
Pine uses the Anti-996 License 1.0 as declared by upstream and Maven metadata.

## Operational limits

- Hooks affect only this process. They require a concrete reflected Method or
  Constructor, not a Python function, name string, or abstract method.
- Static hooks can initialize the declaring class immediately. Already inlined
  callers can bypass hooks; automatic caller deoptimization is not implemented.
- Initialization leaves ART hidden-API policy unchanged to avoid Pine's documented
  late-initialization policy race. Hidden framework members may remain inaccessible.
- Unhook releases callbacks and their Python references. Pine keeps native method
  records/trampolines; the empty dispatcher passes through to the original method.
  Already executing callbacks may complete; removal does not block on them. A
  callback racing with removal may already have captured its callback reference.
- Hook installation errors propagate. A failed member cannot be retried in this
  process because Pine can retain a partially initialized native hook record.
- Avoid hooks on bridge/Chaquopy/Pine internals or very hot/concurrent methods.
  Python callbacks run synchronously on the Java caller's thread and acquire the GIL.
- Host tests validate bridge behavior, not ART native interception, Android ROM
  compatibility, APK packaging, or real Chaquopy conversion on a device.

## Host verification

`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest Tools.test_method_hooks
Tools.test_wgtg_plugin_hooks_events` (one command line) passed 17 tests, including
the collaborating engine's lifecycle tests.

`Tools/TestWgtgMethodHooks.java` passed five semantic test groups using `javac`
and `java` against the exact published `core-0.3.0.aar` classes.jar. The test is in
package `org.telegram.messenger`. Compile it with `WgtgMethodHooks.java`, Pine's
classes.jar, and an Android test runtime (or minimal host doubles for
`android.os.Build`, `android.system.Os`, `OsConstants`, and `android.util.Log`).
The isolated host build is under `/tmp/opencode/method-hooks-classes`, with those
four doubles under `/tmp/opencode/method-hooks-stubs` and the downloaded AAR under
`/tmp/opencode/pine-core-0.3.0.aar`. Tests override only CallFrame's original-method
invocation; production calls Pine's real native-backed implementation.

No Android device execution or full APK build was performed for this change.

## Documented filters

Implemented from https://plugins.exteragram.app/docs/xposed-hooking:

- `RESULT_IS_NULL`, `RESULT_NOT_NULL`, `RESULT_IS_TRUE`, `RESULT_IS_FALSE`.
- `ResultIsInstanceOf(clazz)`, `ResultEqual(value)`, `ResultNotEqual(value)`.
- `ArgumentIsNull(index)`, `ArgumentNotNull(index)`, `ArgumentIsTrue(index)`,
  `ArgumentIsFalse(index)`, `ArgumentIsInstanceOf(index, clazz)`,
  `ArgumentEqual(index, value)`, `ArgumentNotEqual(index, value)`.
- `Condition(condition, object=None)` and `Or(*filters)`.

Import `HookFilter` and `hook_filters` from `base_plugin`, `hook_utils`, or the new
`hook_filters` module. Each filter is callable with a hook param, so it works in
`BaseHook`'s existing `before_filters`/`after_filters`. `@hook_filters(*filters)`
wraps class methods or functional callbacks and evaluates predicates on every
invocation through the real Pine callback proxy. Multiple filters use short-circuit
AND; `Or` uses short-circuit OR. Empty AND allows a callback; empty OR rejects it.
Before and after filters are independent and see the current mutable invocation.

Indexes must be non-negative integers. Missing arguments never match, including
NotEqual/NotNull. Boolean filters require actual booleans, not numeric 0/1 or
truthy objects. Equality uses Python/Chaquopy equality (including Java equals for
Java objects), but keeps booleans distinct from numbers. Thus numeric 5 and 5.0
compare equal; False and 0 do not. These edge rules are explicit here because the
upstream documentation lists the helpers without defining these cases.

Java type filters accept a reflected Class, a Java class proxy, or a fully qualified
class name. They evaluate `instanceof` in Java through MVEL so Chaquopy unboxing
does not erase the original Integer/Long or other runtime type. Null never matches.
`Condition` uses the already bundled `org.mvel:mvel2:2.5.2.Final` interpreter with
`param` and `object` variables and the method receiver as `this` (null for static
calls). Each evaluation uses a fresh variable map. It does not translate MVEL into
Python eval or generate JVM bytecode. Invalid expressions propagate as hook errors.

Filtering a replacement method skips replacement entirely when predicates reject;
it does not call `setResult(None)`. A matching replacement may still return None to
replace the original with null/void. Ordinary predicate errors use the bridge's
ordinary callback error handling; errors inside a replacement use replacement
exception handling.

Additional tests:

```
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest Tools.test_hook_filters Tools.test_method_hooks Tools.test_wgtg_plugin_hooks_events
```

`Tools/test_hook_filters_jvm.py` executes real MVEL and the actual Java dispatcher,
using JPype only as a host test adapter. Compile `Tools/HookFilterHarness.java` with
the Java bridge and Pine core jar into the host classes directory described above.
Then run with JPype1 installed and `HOOK_FILTER_TEST_CLASSPATH` pointing to that
directory, Pine classes.jar, and `mvel2-2.5.2.Final.jar`. The harness substitutes
the native original-method call and registration; production Python callbacks,
filters, MVEL interpreter, Param, and dispatcher run unchanged. This is not a
Chaquopy-on-Android test.

## Android 16 and 16 KB follow-up

Rechecked Maven Central: `top.canyie.pine:core` still ends at `0.3.0`.
https://github.com/canyie/pine/issues/107 remains an open request for 16 KB alignment.
https://github.com/canyie/pine/issues/110 reports Android 16 hidden-API problems;
this is additional compatibility uncertainty, not proof that all hooks fail there.
The closed issue https://github.com/canyie/pine/issues/104 contains no published
fix or release evidence. LSPlant's current upstream README advertises newer Android
support, but still requires JNI initialization, native hook/unhook providers and
ART symbol resolution. No minimal verified dependency upgrade was identified.
The existing API/ABI/page-size guards and dependencies remain unchanged.
