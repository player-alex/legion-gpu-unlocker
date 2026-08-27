package io.laelaps.legiongpuunlocker;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Legion GPU Unlocker (modern libXposed API 101).
 *
 * Removes the game/performance-mode GPU clock cap on Lenovo/Zui tablets. The cap is
 * applied by TAssistent (com.zui.tassistent): it acquires a Qualcomm perflock whose GPU
 * value is a PWRLEVEL INDEX (0 = highest frequency ... N = lowest) pulled from a per-mode
 * table in gameperfconfig.xml. On Global/ROW firmware that index tops out at ~826 MHz.
 *
 * This module hooks the single choke point -- doPerfAcquire(int[], int) on the SoC-specific
 * PerfLock impl -- and, for every GPU "ceiling" opcode (Cluster.opCodeMax, read at runtime
 * from com.zui.tassistent.policy.BaseConfig.mGpuClusters), sets the paired value to pwrlevel
 * 0 (the device's own top GPU frequency). No MHz or opcode is hard-coded, so it generalises
 * across models/regions. The floor (opCodeMin) is left untouched so DVFS still scales down,
 * and the hardware thermal engine still caps via thermal_pwrlevel independently.
 */
public class LegionGpuUnlocker extends XposedModule {

    private static final String TAG = "LegionGPU";
    private static final String TARGET_PKG = "com.zui.tassistent";
    private static final String BASECONFIG = "com.zui.tassistent.policy.BaseConfig";
    private static final String[] PERFLOCK_CLASSES = {
            "com.zui.tassistent.perflock.QcomPerfLock",
            "com.zui.tassistent.perflock.MTKPerfLock",
            "com.zui.tassistent.perflock.LeBoostPerfLock",
    };

    // Highest-performance pwrlevel index: 0 is always the top GPU frequency on any KGSL device.
    private static final int MAX_PERF_INDEX = 0;

    private volatile ClassLoader appClassLoader;
    private volatile Set<Integer> gpuMaxOpcodes; // resolved lazily from BaseConfig.mGpuClusters
    private volatile boolean loggedRaw = false;
    private volatile boolean loggedRewrite = false;

    // libXposed instantiates the module via the no-arg constructor and then calls
    // attachFramework(...) on the wrapper; no explicit constructor is required.

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        appClassLoader = param.getDefaultClassLoader();

        int hooked = 0;
        for (String className : PERFLOCK_CLASSES) {
            try {
                Class<?> cls = appClassLoader.loadClass(className);
                Method m = cls.getDeclaredMethod("doPerfAcquire", int[].class, int.class);
                hook(m).intercept(perfAcquireHooker);
                hooked++;
                log(Log.INFO, TAG, "hooked " + className + ".doPerfAcquire");
            } catch (Throwable ignore) {
                // class absent on this SoC variant
            }
        }
        log(Log.INFO, TAG, "installed on " + TARGET_PKG + " (doPerfAcquire hooks=" + hooked + ")");
    }

    private final Hooker perfAcquireHooker = new Hooker() {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            try {
                List<Object> args = chain.getArgs();
                if (!args.isEmpty() && args.get(0) instanceof int[]) {
                    int[] arr = (int[]) args.get(0);
                    Set<Integer> ops = gpuOpcodes();
                    if (ops != null && !ops.isEmpty()) {
                        if (!loggedRaw) {
                            loggedRaw = true;
                            log(Log.INFO, TAG, "doPerfAcquire raw: " + Arrays.toString(arr));
                        }
                        boolean changed = false;
                        for (int i = 0; i + 1 < arr.length; i += 2) {
                            // arr[i] is an opcode; the GPU value is a pwrlevel index, not a freq.
                            if (ops.contains(arr[i]) && arr[i + 1] != MAX_PERF_INDEX) {
                                arr[i + 1] = MAX_PERF_INDEX; // raise GPU ceiling to pwrlevel 0
                                changed = true;
                            }
                        }
                        if (changed && !loggedRewrite) {
                            loggedRewrite = true;
                            log(Log.INFO, TAG, "raised GPU ceiling -> pwrlevel 0; array now " + Arrays.toString(arr));
                        }
                    }
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "intercept error: " + t);
            }
            return chain.proceed(); // proceed with the (in-place modified) arguments
        }
    };

    /** Set of GPU ceiling opcodes (Cluster.opCodeMax), read once from BaseConfig.mGpuClusters. */
    private Set<Integer> gpuOpcodes() {
        Set<Integer> cached = gpuMaxOpcodes;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> bc = appClassLoader.loadClass(BASECONFIG);
            Field clustersField = bc.getDeclaredField("mGpuClusters");
            clustersField.setAccessible(true);
            Object obj = clustersField.get(null); // static field
            if (!(obj instanceof Map)) {
                return null;
            }
            Map<?, ?> clusters = (Map<?, ?>) obj;
            if (clusters.isEmpty()) {
                return null; // config not parsed yet; retry on a later call
            }
            Set<Integer> set = new HashSet<>();
            for (Object cluster : clusters.values()) {
                Field opMax = cluster.getClass().getDeclaredField("opCodeMax");
                opMax.setAccessible(true);
                set.add(opMax.getInt(cluster));
            }
            if (set.isEmpty()) {
                return null;
            }
            gpuMaxOpcodes = set;
            log(Log.INFO, TAG, "GPU ceiling opcodes resolved: " + set);
            return set;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "gpuOpcodes() failed: " + t);
            return null;
        }
    }
}
