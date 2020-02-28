package com.bytedance.boost_multidex;

import android.app.IntentService;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class OptimizeService extends IntentService {
    static volatile boolean sAlreadyOpt;

    File mRootDir;
    File mDexDir;
    File mOptDexDir;
    File mZipDir;

    public OptimizeService() {
        super("OptimizeService");
        Monitor monitor = Monitor.get();
        if (monitor == null) {
            Monitor.init(null);
        }
        Monitor.get().logDebug("Starting OptimizeService");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            File filesDir = this.getFilesDir();
            if (!filesDir.exists()) {
                Utility.mkdirChecked(filesDir);
            }

            mRootDir = Utility.ensureDirCreated(filesDir, Constants.BOOST_MULTIDEX_DIR_NAME);
            mDexDir = Utility.ensureDirCreated(mRootDir, Constants.DEX_DIR_NAME);
            mOptDexDir = Utility.ensureDirCreated(mRootDir, Constants.ODEX_DIR_NAME);
            mZipDir = Utility.ensureDirCreated(mRootDir, Constants.ZIP_DIR_NAME);
        } catch (IOException e) {
            Monitor.get().logError("fail to create files", e);
            sAlreadyOpt = true;
        }
    }

    public static void startOptimizeService(Context context) {
        Intent intent = new Intent(context, OptimizeService.class);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            try {
                handleOptimize();
            } catch (IOException e) {
                Monitor.get().logError("fail to handle opt", e);
            }
        }
    }

    /**
     * Handle action Foo in the provided background thread with the provided
     * parameters.
     */
    private void handleOptimize() throws IOException {
        if (sAlreadyOpt) {
            Monitor.get().logInfo("opt had already done, skip");
            return;
        }

        sAlreadyOpt = true;

        Monitor.get().doBeforeHandleOpt();

        String keyApkDexNum = Constants.KEY_DEX_NUMBER;

        Locker locker = new Locker(new File(mRootDir, Constants.LOCK_INSTALL_FILENAME));

        locker.lock();

        try {
            ApplicationInfo applicationInfo = this.getApplicationInfo();
            if (applicationInfo == null) {
                throw new RuntimeException("No ApplicationInfo available, i.e. running on a test Context:"
                        + " BoostMultiDex support library is disabled.");
            }

            File apkFile = new File(applicationInfo.sourceDir);

            SharedPreferences preferences = this.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
            int totalDexNum = preferences.getInt(keyApkDexNum, 0);
            for (int secondaryNumber = 2; secondaryNumber <= totalDexNum; secondaryNumber++) {
                int type = preferences.getInt(Constants.KEY_DEX_OBJ_TYPE + secondaryNumber, Constants.LOAD_TYPE_APK_BUF);

                File dexFile = new File(mDexDir, secondaryNumber + Constants.DEX_SUFFIX);
                File optDexFile = new File(mOptDexDir, secondaryNumber + Constants.ODEX_SUFFIX);

                DexHolder dexHolder;
                if (type == Constants.LOAD_TYPE_APK_BUF) {
                    ZipFile apkZipFile = new ZipFile(apkFile);
                    ZipEntry dexFileEntry = apkZipFile.getEntry(Constants.DEX_PREFIX + secondaryNumber + Constants.DEX_SUFFIX);
                    byte[] bytes = Utility.obtainEntryBytesInZip(apkZipFile, dexFileEntry);
                    dexHolder = new DexHolder.ApkBuffer(secondaryNumber, bytes, dexFile, optDexFile);
                } else if (type == Constants.LOAD_TYPE_DEX_BUF) {
                    dexHolder = new DexHolder.DexBuffer(secondaryNumber, dexFile, optDexFile);
                } else if (type == Constants.LOAD_TYPE_DEX_OPT) {
                    dexHolder = new DexHolder.DexOpt(secondaryNumber, dexFile, optDexFile, false);
                } else if (type == Constants.LOAD_TYPE_ZIP_OPT) {
                    File zipFile = new File(mZipDir, secondaryNumber + Constants.ZIP_SUFFIX);
                    File zipOptFile = new File(mZipDir, secondaryNumber + Constants.ODEX_SUFFIX);
                    dexHolder = new DexHolder.ZipOpt(secondaryNumber, zipFile, zipOptFile);
                } else {
                    dexHolder = null;
                }

                Monitor.get().logInfo("Process beginning holder " + dexHolder.toString() + ", type: " + type);

                DexHolder fasterHolder = dexHolder;

                while (fasterHolder != null) {
                    long freeSpace = Environment.getDataDirectory().getFreeSpace();
                    if (freeSpace < Constants.SPACE_MIN_THRESHOLD) {
                        Monitor.get().logWarning("Free space is too small: " + freeSpace
                                + ", compare to " + Constants.SPACE_THRESHOLD);
                        return;
                    } else {
                        Monitor.get().logInfo("Free space is enough: " + freeSpace + ", continue...");
                    }

                    Monitor.get().logDebug("Process holder, " + fasterHolder);

                    try {
                        long start = System.nanoTime();

                        fasterHolder = fasterHolder.toFasterHolder(preferences);

                        if (fasterHolder != null) {
                            long cost = System.nanoTime() - start;

                            DexHolder.StoreInfo info = fasterHolder.getInfo();

                            Monitor.get().logDebug("Put info, " + info.index + " file is " + info.file.getPath());

                            long reducedSpace = Environment.getDataDirectory().getFreeSpace() - freeSpace;

                            Monitor.get().reportAfterInstall(cost, freeSpace, reducedSpace, fasterHolder.toString());
                        }
                    } catch (Throwable tr) {
                        Monitor.get().logErrorAfterInstall("Fail to be faster", tr);
                        Result.get().unFatalThrowable.add(tr);
                    }

                    Locker prepareLocker = new Locker(new File(mRootDir, Constants.LOCK_PREPARE_FILENAME));
                    if (prepareLocker.test()) {
                        prepareLocker.close();
                    } else {
                        Monitor.get().logInfo("Other process is waiting for installing");
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            Monitor.get().logWarning("Failed to install extracted secondary dex files", e);
        } finally {
            locker.close();
            Monitor.get().logInfo("Exit quietly");
            stopSelf();
            System.exit(0);
        }
    }
}
