# SESSION — Legion GPU Unlocker (technical write-up)

Reverse-engineering and implementation log for removing the game/performance-mode GPU clock
cap on a Global (ROW) Lenovo Legion tablet, generically and without hard-coded frequencies.

---

## 1. Target

| | |
|---|---|
| Device | Lenovo **Legion Tab TB323FU** (project "baldur"), Global/ROW SKU |
| OS | **Android 16**, SoC **SM8850P** (Snapdragon 8 Elite Gen 2, "canoe"), GPU **Adreno 840v2** |
| Root / hooking | Magisk + LSPosed |
| Symptom | GPU capped to ~726/826 MHz in game modes; a paid closed-source tool ("Scene") unlocks it to 1200 MHz but reverts on reboot and is overridden by the Game Helper's performance mode |
| Goal | Reach the device's true GPU max (1200 MHz) in game modes, open-source, ideally region/model-agnostic |

KGSL GPU frequency table (`/sys/class/kgsl/kgsl-3d0/freq_table_mhz`), pwrlevel 0…17:
`1200 1100 1050 1000 902 826 726 646 578 500 461 422 382 342 282 222 191 160`.
Pwrlevel **0 = 1200 MHz** (fastest) … **5 = 826**, **6 = 726**.

---

## 2. The cap chain (as found)

```
Game Helper (com.zui.game.service)
   writes Settings.Global "game_helper_game_mode" = 1/2/4/8/16  (Balance/Perf/Save/Smart/Turbo)
        │  (it does NOT touch the GPU itself — verified by decompile)
        ▼
TAssistent (com.zui.tassistent)   ← the real capper
   ContentObserver on that setting → per-mode CPU/GPU policy from /system/etc/gameperfconfig.xml
   → BoostFramework perflock (QcomPerfLock.doPerfAcquire)
        ▼
Qualcomm perf daemon (perfservice / perf2-hal)
   writes /sys/class/kgsl/kgsl-3d0/{min_pwrlevel,max_pwrlevel}; renews the lock ~1×/s
```

Empirically, Performance mode pinned **min_pwrlevel = max_pwrlevel = 5** (826 MHz). A one-shot
root write of `max_pwrlevel=0` reverted to 5 within ~1 s — the perf daemon re-pins.

---

## 3. Investigation

### 3.1 KGSL semantics (measured, not assumed)
- `max_pwrlevel` node = the **lowest allowed index** = the **frequency CEILING** (index 5 = 826).
- `min_pwrlevel` node = the **highest allowed index** = the **frequency FLOOR** (index 8 = 578).
- To unlock the ceiling to 1200 MHz, `max_pwrlevel` must become **0**.
- `thermal_pwrlevel` is a **separate** node the thermal engine uses to throttle under heat — it
  caps the GPU independently of the perflock, so raising the perflock ceiling does not disable
  thermal protection.

### 3.2 Who applies it — decompilation
- `ZuiGameHelper.apk` (`com.zui.game.service`): only writes `Settings.Global game_helper_game_mode`
  (class `XpuKt.writeSavageMode`, Android-16 branch). No KGSL/perflock code.
- `TAssistent.apk` (`com.zui.tassistent`): the capper.
  - `GameStateListener` observes `game_helper_game_mode`.
  - `GameManager.updatePerfPolicy(int[] iArr, int)` → `QcomPerfLock.doPerfAcquire(int[], int)`
    → `android.util.BoostFramework.perfLockAcquire(int, int[])`.
  - `BaseConfig.parseGameOptPolicy()` parses `/system/etc/gameperfconfig.xml`
    `<PreEnv><GPU><cluster min="0x42804000" max="0x42808000">…freqs…</cluster>` into a **static**
    `ArrayMap<String, Cluster> mGpuClusters`, where `Cluster{ int opCodeMin, int opCodeMax, long[] freqs }`.
  - The outgoing perflock array is strict `(opcode, value)` pairs; the GPU pair is
    `(opCodeMin, idx), (opCodeMax, idx)` where **idx is a pwrlevel index**, not a frequency.

### 3.3 The region gate is DATA, not code
- Perf configs `/vendor/etc/perf/*` map perflock opcodes → sysfs (`0x42804000`=min_pwrlevel,
  `0x42808000`=max_pwrlevel); `/system/etc/performanceconfig.xml` and `gameperfconfig.xml` carry
  the values; `/system/etc/gameperfconfig.xml` GPU table lists `1200000000 … 826000000 …`
  (freqs[0] is still the true max — the table is **not** truncated on ROW).
- TAssistent has **no** `row`/`prc`/`baldur`/`ro.config.lgsi.region` branch (exhaustive string
  sweep = 0). The 826-vs-1200 difference is realized by ROW firmware shipping a
  `gameperfconfig.xml` whose per-mode GPU index is lower.
- (The region key `ro.config.lgsi.region == "row"` DOES gate other Game-Helper features — LSR
  "Ultra HD Vision", 4D vibration, high-FPS game lists — via `com.zui.util.DeviceUtils.isRow()`
  and `FeaturesBaseOnRomKt.getRomFeatures()` returning `BALDUR_FEATURES` vs `BALDUR_ROW_FEATURES`.
  That is what the sibling project **GAP** flips. But it does **not** control the GPU clock.)

### 3.4 Dead ends
- **OCZ apps** (`com.lenovo.ocpl` / `com.lenovo.dsa`): Lenovo's overseas customization/provisioning
  suite (CCS/CSDK); zero GPU/perf code; CCS is ROW-only ("PRC consumer version don't support CCS
  feature!"). Not the capper.
- **Property spoof** (`ro.config.lgsi.region`→prc): would flip LSR/feature gates, but not the GPU
  cap (TAssistent has no region branch), and has broad side effects. Rejected.

---

## 4. The fix

Hook `QcomPerfLock.doPerfAcquire(int[] iArr, int)` (and MTK/LeBoost variants if present). For
each `(opcode, value)` pair whose opcode is a GPU `opCodeMax` (learned at runtime by reflecting
`BaseConfig.mGpuClusters`), set the value to **0** (pwrlevel 0 = the device's top frequency). The
floor (`opCodeMin`) is untouched → DVFS still scales down; `thermal_pwrlevel` still protects.

Because TAssistent's own perflock is renewed by the perf daemon, the daemon now holds the GPU at
the maximum — the ~1 s re-pin works *for* us instead of against us. No frequency or opcode is
hard-coded, so it generalizes to any Lenovo/Zui tablet whose TAssistent uses this schema — the
ceiling index `0` is the max on every KGSL device.

### The bug we hit (and why the fix is index 0, not a frequency)
The first attempt set the GPU value to `freqs[0]` (= 1200000, read from the config table),
assuming the perflock value was a frequency. Result: the GPU got pinned to **578 MHz** — the
perf daemon received an out-of-range "index" (1200000) and clamped it. The raw-array log proved
the GPU value is a small **pwrlevel index** (`…, 0x42808000, 5, …`), so the correct target is
index **0**, which is also cleaner and fully device-independent.

---

## 5. Module (modern libXposed API 101)

- Single entry `io.laelaps.legiongpuunlocker.LegionGpuUnlocker extends
  io.github.libxposed.api.XposedModule`; registered via `META-INF/xposed/{module.prop
  (minApiVersion/targetApiVersion=101, staticScope=true), java_init.list, scope.list=com.zui.tassistent}`.
- Hooking uses the API-101 Chain model: `hook(Method).intercept(Hooker)` where
  `Hooker.intercept(Chain)` reads `chain.getArgs()`, mutates the `int[]` in place, and returns
  `chain.proceed()`. No legacy `de.robv.android.xposed.*` / `assets/xposed_init`.
- Reflection (plain Java, app classloader) reads the static `BaseConfig.mGpuClusters` to build the
  set of GPU `opCodeMax` opcodes once; cached.

### Build & deploy notes
- No Gradle. `build.ps1`: `javac` against `android.jar` + `libs/libxposed-api.jar` (compile-only,
  API from `io.github.libxposed:api:101.0.1` aar's `classes.jar`) → `d8` (module classes only;
  libxposed stays on `--classpath` so it is not dexed) → `aapt2 link` (manifest only, no
  resources) → `jar uf` to add `classes.dex` + `META-INF/xposed/*` → `zipalign` → `apksigner`
  with `release.keystore`.
- PowerShell gotchas fixed: a single-element `(...).FullName` is a **String**, and `@srcs`
  splats a String **per character** (javac then sees `:` as a flag) — force arrays with `@( … )`;
  pass a multi-path classpath via a `$variable`, not an inline `"$a;$b"`.
- Iterating on an app-scoped module needs only `adb install -r` (same key) + `am force-stop
  com.zui.tassistent` — no reboot, no wireless-adb port change. Switching signing key (debug →
  `release.keystore`) or the module API (legacy → modern) does require uninstall + re-enable +
  reboot once.

---

## 6. Verification

```
[LegionGPU] installed on com.zui.tassistent (doPerfAcquire hooks=3)
[LegionGPU] GPU ceiling opcodes resolved: [1115717632]     (0x42808000)
[LegionGPU] doPerfAcquire raw: [..., 1115717632, 6, ...]
[LegionGPU] raised GPU ceiling -> pwrlevel 0; array now [..., 1115717632, 0, ...]
```
On-device during a game mode: `max_pwrlevel = 0` (ceiling = 1200 MHz), `min_pwrlevel` = the
mode's floor, GPU clock reaches 1200 under load; `thermal_pwrlevel` still governs heat. Confirmed
across a clean reboot.

---

## 7. Trade-offs & scope

- Raising every GPU `opCodeMax` to index 0 also overrides TAssistent's own per-TempLevel GPU
  step-downs; the hardware thermal engine (`thermal_pwrlevel`) remains the real safety net.
- Ceiling-only: the floor is left as the mode set it, so the GPU still idles down.
- Qualcomm Lenovo/Zui tablets with TAssistent + QcomPerfLock are covered generically. MTK
  (`MTKPerfLock`) / Titans (`LeBoostPerfLock`) use different opcode schemes — the hook is
  installed but needs verification on such hardware.
- No sepolicy/Magisk module and no property spoof are required.
