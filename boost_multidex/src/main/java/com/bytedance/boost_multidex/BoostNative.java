package com.bytedance.boost_multidex;

import android.os.Build;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/5.
 */
final class BoostNative {
    private static volatile boolean alreadyInit;

    private static boolean supportFastLoadDex;

    static {
        Monitor.get().loadLibrary("boost_multidex");
    }

    private static void checkSupportFastLoad(Result result) {
        try {
            Method getPropertyMethod = Class.forName("android.os.SystemProperties")
                    .getDeclaredMethod("get", String.class, String.class);
            if (Build.VERSION.SDK_INT >= 19) {
                String vmLibName = (String) getPropertyMethod.invoke(null, "persist.sys.dalvik.vm.lib", null);
                result.vmLibName = vmLibName;
                Monitor.get().logInfo("VM lib is " + vmLibName);
                if ("libart.so".equals(vmLibName)) {
                    Monitor.get().logWarning("VM lib is art, skip!");
                    return;
                }
            }

            String yunosVersion = (String) getPropertyMethod.invoke(null, "ro.yunos.version", null);
            if (yunosVersion != null && !yunosVersion.isEmpty() || new File(Constants.LIB_YUNOS_PATH).exists()) {
                result.isYunOS = true;
                Monitor.get().logWarning("Yun os is " + yunosVersion + ", skip boost!");
                return;
            }

            supportFastLoadDex = initialize(Build.VERSION.SDK_INT, RuntimeException.class);

            result.supportFastLoadDex = supportFastLoadDex;
        } catch (Throwable tr) {
            result.addUnFatalThrowable(tr);
            Monitor.get().logWarning("Fail to init", tr);
        }
    }

    static synchronized boolean isSupportFastLoad() {
        if (!alreadyInit) {
            checkSupportFastLoad(Result.get());
            alreadyInit = true;
        }

        return supportFastLoadDex;
    }

    static native Object loadDirectDex(String fileName, byte[] fileContents);

    static native long obtainCheckSum(String path);

    static native void recoverAction();

    static native boolean makeOptDexFile(String filePath, String optFilePath);

    private static native boolean initialize(int sdkVersion, Class<RuntimeException> runtimeExceptionClass);
}
