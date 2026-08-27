# Legion GPU Unlocker

A **modern libXposed (API 101)** LSPosed module that removes the **game/performance-mode
GPU clock cap** on Lenovo / Zui tablets (Legion Tab, Tab series), lifting the GPU to the
device's **own maximum** that the Global/ROW firmware otherwise holds back.

Verified on the **Lenovo Legion Tab (TB323FU, "baldur", Android 16, SoC SM8850P,
Adreno 840)** — Global/ROW unit capped at 826 MHz, unlocked to 1200 MHz.

## The problem

On these tablets the perf app **TAssistent** (`com.zui.tassistent`) applies the per-mode
CPU/GPU frequency policy. When a game/performance mode is selected it acquires a Qualcomm
perflock (`BoostFramework`) that pins the GPU to a frequency chosen from
`/system/etc/gameperfconfig.xml`. The **China-domestic vs Global split is data, not code** —
there is *no* region branch in TAssistent; the ROW firmware simply ships a config whose
per-mode GPU **pwrlevel index** tops out at ~826 MHz (pwrlevel 5) instead of 1200 MHz
(pwrlevel 0). Runtime unlocks that just poke sysfs lose, because TAssistent's perflock is
renewed by the perf daemon ~1×/s and re-pins the cap.

## What this does (generic, no hard-coded MHz)

It hooks the single choke point through which every cap is applied:

```
com.zui.tassistent.perflock.QcomPerfLock#doPerfAcquire(int[] iArr, int time)
  (also MTKPerfLock / LeBoostPerfLock if present)
```

`iArr` is strict `(opcode, value)` pairs, and the **GPU value is a pwrlevel INDEX**
(0 = highest frequency … N = lowest), not a frequency. The module:

1. reflects TAssistent's own parsed table
   `com.zui.tassistent.policy.BaseConfig.mGpuClusters` to learn each GPU cluster's ceiling
   opcode (`Cluster.opCodeMax`) at runtime — so no opcode or MHz is hard-coded;
2. in every outgoing `doPerfAcquire`, sets each GPU `opCodeMax` value to **0** (pwrlevel 0
   = the device's top GPU frequency, whatever it is on that model).

TAssistent's own perflock then requests the maximum, and the perf daemon holds it — the
re-pin works *for* us. The floor (`opCodeMin`) is left untouched so the GPU still scales
down under light load.

**Thermal safety is preserved:** the hardware thermal engine independently caps the GPU via
`/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel`, which this module never touches — so the GPU
reaches its max only when thermals allow.

## Why it generalizes

The GPU frequency table is **not truncated** on ROW firmware — `freqs[0]` (pwrlevel 0) is
still the true device maximum; only the chosen index is lowered. Pwrlevel `0` is always the
fastest level on any KGSL device, so "set the GPU ceiling opcode to index 0" unlocks to the
correct per-device maximum everywhere, with zero device-specific constants.

| Target | Status |
|---|---|
| Qualcomm Lenovo/Zui tablets (TAssistent + QcomPerfLock) | ✅ works as-is, no per-model constants |
| MTK (`MTKPerfLock`) / Titans (`LeBoostPerfLock`) Lenovo | ⚠️ hook is installed; opcode scheme differs — verify on such a device |
| Devices without TAssistent | ❌ different mechanism |

## Install

1. `adb install LegionGpuUnlocker.apk` (or sideload).
2. In **LSPosed Manager**: enable the module. It registers as a **modern libXposed (API 101)**
   module; its scope is fixed to **`com.zui.tassistent`** via `staticScope` (no manual scope
   selection needed).
3. **Reboot.**
4. Start a game in performance/game mode — the GPU now reaches the device maximum under load.

## Build

Self-contained, no Gradle (Android SDK build-tools 36.0.0 + a JDK). The libXposed API is
compile-only (`libs/libxposed-api.jar`, provided by LSPosed at runtime):

```powershell
./build.ps1     # -> LegionGpuUnlocker.apk (release-signed with release.keystore)
```

Module registration is the modern layout `META-INF/xposed/{module.prop, java_init.list,
scope.list}` (no legacy `assets/xposed_init`).

## Verify

```powershell
adb shell "su -c 'tail -f /data/adb/lspd/log/modules_*.log'"
```
Expected when a game/performance mode starts:
```
[LegionGPU] installed on com.zui.tassistent (doPerfAcquire hooks=3)
[LegionGPU] GPU ceiling opcodes resolved: [1115717632]
[LegionGPU] raised GPU ceiling -> pwrlevel 0; array now [..., 1115717632, 0, ...]
```
On-device: `cat /sys/class/kgsl/kgsl-3d0/max_pwrlevel` → `0` (ceiling = top freq); the GPU
clock reaches the device maximum under load.

## Notes

- "Unlocker", not overclock: it does not exceed the hardware frequency table — it only
  removes the software cap so the GPU reaches its own top pwrlevel (the value China-domestic
  firmware already allows).
- Companion in spirit to GAP (game-helper LSR/region unlock); this one is independent and
  targets TAssistent's GPU perflock instead.
