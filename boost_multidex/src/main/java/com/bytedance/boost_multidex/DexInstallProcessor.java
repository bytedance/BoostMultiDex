package com.bytedance.boost_multidex;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/26.
 */
class DexInstallProcessor {
    private SharedPreferences mPreferences;
    private boolean mDoCheckSum;

    DexInstallProcessor() {
        Random random = new Random();
        mDoCheckSum = random.nextInt(3) == 0;
        Monitor.get().logInfo("Do checksum " + mDoCheckSum);
    }

    void doInstallation(final Context mainContext, File sourceApk, Result result) throws Exception {
        File filesDir = mainContext.getFilesDir();
        if (!filesDir.exists()) {
            Utility.mkdirChecked(filesDir);
        }
        Utility.clearDirFiles(new File(filesDir.getParent(), Constants.CODE_CACHE_SECONDARY_FOLDER_NAME));

        File rootDir = Utility.ensureDirCreated(filesDir, Constants.BOOST_MULTIDEX_DIR_NAME);
        File dexDir = Utility.ensureDirCreated(rootDir, Constants.DEX_DIR_NAME);
        File optDexDir = Utility.ensureDirCreated(rootDir, Constants.ODEX_DIR_NAME);
        File zipDir = Utility.ensureDirCreated(rootDir, Constants.ZIP_DIR_NAME);

        result.setDirs(filesDir, rootDir, dexDir, optDexDir, zipDir);

        Locker prepareLocker = new Locker(new File(rootDir, Constants.LOCK_PREPARE_FILENAME));
        prepareLocker.lock();

        Locker locker = new Locker(new File(rootDir, Constants.LOCK_INSTALL_FILENAME));

        locker.lock();
        prepareLocker.close();

        List<DexHolder> dexHolderList;
        try {
            mPreferences = mainContext.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);

            result.freeSpaceBefore = Environment.getDataDirectory().getFreeSpace();

            dexHolderList = obtainDexObjectList(sourceApk, rootDir, dexDir, optDexDir, zipDir, result);

            installSecondaryDexes(mainContext.getClassLoader(), dexHolderList);
            // Some IOException causes may be fixed by a clean extraction.
        } catch (Throwable e) {
            Monitor.get().logWarning("Failed to install extracted secondary dex files", e);
            throw e;
        } finally {
            locker.close();
        }

        long freeSpaceAfter = Environment.getDataDirectory().getFreeSpace();
        result.freeSpaceAfter = freeSpaceAfter;
        if (freeSpaceAfter < Constants.SPACE_MIN_THRESHOLD) {
            Monitor.get().logWarning("Free space is too small: " + freeSpaceAfter
                    + ", compare to " + Constants.SPACE_MIN_THRESHOLD);
        } else {
            for (final DexHolder dexHolder : dexHolderList) {
                if (!(dexHolder instanceof DexHolder.ZipOpt || dexHolder instanceof DexHolder.DexOpt)) {
                    Monitor.get().doAfterInstall(new Runnable() {
                        @Override
                        public void run() {
                            OptimizeService.startOptimizeService(mainContext);
                        }
                    });
                    return;
                }
            }
        }
    }

    void doInstallationInOptProcess(Context context, File apkFile) throws Exception {
        if (!BoostNative.isSupportFastLoad()) {
            Monitor.get().logError("Fast load is not supported!");
            return;
        }

        int secondaryNumber = 2;

        final ZipFile apkZipFile = new ZipFile(apkFile);
        ZipEntry dexEntry;

        List<DexHolder> dexHolderList = new ArrayList<>();
        while ((dexEntry = apkZipFile.getEntry(Constants.DEX_PREFIX + secondaryNumber + Constants.DEX_SUFFIX)) != null) {
            byte[] bytes = obtainEntryBytesInApk(apkZipFile, dexEntry);
            dexHolderList.add(new DexHolder.ApkBuffer(secondaryNumber, bytes, null, null));
            secondaryNumber++;
        }

        DexLoader.create(Build.VERSION.SDK_INT).install(context.getClassLoader(), dexHolderList);
        apkZipFile.close();

        try {
            BoostNative.recoverAction();
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private void installSecondaryDexes(ClassLoader loader, List<DexHolder> dexHolderList) throws Exception {
        DexLoader.create(Build.VERSION.SDK_INT).install(loader, dexHolderList, mPreferences);
        try {
            BoostNative.recoverAction();
        } catch (UnsatisfiedLinkError ignored) {
        }
        Monitor.get().logDebug("After install all, sp value is " + mPreferences.getAll());
    }

    @SuppressLint("ApplySharedPref")
    private List<DexHolder> obtainDexObjectList(File apkFile, File rootDir, File dexDir, File odexDir, File zipDir, Result result) throws IOException {
        long archiveCheckSum = Utility.doZipCheckSum(apkFile);
        long archiveTimeStamp = apkFile.lastModified();

        String keyApkTime = Constants.KEY_TIME_STAMP;
        String keyApkCrc = Constants.KEY_CRC;
        String keyApkDexNum = Constants.KEY_DEX_NUMBER;

        boolean isModified = (mPreferences.getLong(keyApkTime, Constants.NO_VALUE) != archiveTimeStamp)
                || (mPreferences.getLong(keyApkCrc, Constants.NO_VALUE) != archiveCheckSum);

        result.modified = isModified;

        List<DexHolder> dexHolderList = new ArrayList<>();
        if (isModified) {
            Utility.clearDirFiles(dexDir);
            Utility.clearDirFiles(odexDir);
            Utility.clearDirFiles(zipDir);

            SharedPreferences.Editor edit = mPreferences.edit();
            edit.clear();
            edit.commit();

            int secondaryNumber = 2;

            final ZipFile apkZipFile = new ZipFile(apkFile);
            ZipEntry dexEntry;

            while ((dexEntry = apkZipFile.getEntry(Constants.DEX_PREFIX + secondaryNumber + Constants.DEX_SUFFIX)) != null) {
                File dexFile = new File(dexDir, secondaryNumber + Constants.DEX_SUFFIX);
                File optDexFile = new File(odexDir, secondaryNumber + Constants.ODEX_SUFFIX);
                if (BoostNative.isSupportFastLoad()) {
                    // all in apk dex bytes
                    if (Utility.isBetterUseApkBuf()) {
                        byte[] bytes = obtainEntryBytesInApk(apkZipFile, dexEntry);
                        dexHolderList.add(new DexHolder.ApkBuffer(secondaryNumber, bytes, dexFile, optDexFile));
                    } else {
                        File validDexFile = obtainEntryFileInApk(apkZipFile, dexEntry, dexFile);
                        dexHolderList.add(DexHolder.obtainValidDexBuffer(mPreferences, secondaryNumber, validDexFile, optDexFile));
                    }
                } else {
                    // all dex or zip
                    if (Environment.getDataDirectory().getFreeSpace() > Constants.SPACE_THRESHOLD) {
                        dexHolderList.add(DexHolder.obtainValidForceDexOpt(mPreferences, secondaryNumber, dexFile, optDexFile, apkZipFile, dexEntry));
                    } else {
                        File zipFile = new File(zipDir, secondaryNumber + Constants.ZIP_SUFFIX);
                        File zipOptFile = new File(zipDir, secondaryNumber + Constants.ODEX_SUFFIX);
                        dexHolderList.add(DexHolder.obtainValidZipDex(mPreferences, secondaryNumber, zipFile, zipOptFile, apkZipFile, dexEntry));
                    }
                }
                secondaryNumber++;
            }
            apkZipFile.close();

            edit.putInt(keyApkDexNum, secondaryNumber - 1);
            edit.putLong(keyApkTime, archiveTimeStamp);
            edit.putLong(keyApkCrc, archiveCheckSum);
            edit.commit();
        } else {
            // ensure valid dex cache
            int totalDexNum = mPreferences.getInt(keyApkDexNum, 0);
            for (int secondaryNumber = 2; secondaryNumber <= totalDexNum; secondaryNumber++) {
                dexHolderList.add(obtainDexHolder(secondaryNumber,
                        apkFile, dexDir, odexDir, zipDir));
            }
        }

        return dexHolderList;
    }

    private DexHolder obtainDexHolder(int secondaryNumber, File apkFile, File dexDir, File odexDir, File zipDir)
            throws IOException {
        int type = mPreferences.getInt(Constants.KEY_DEX_OBJ_TYPE + secondaryNumber, Constants.LOAD_TYPE_INVALID);
        if (type == Constants.LOAD_TYPE_INVALID) {
            if (BoostNative.isSupportFastLoad()) {
                type = Utility.isBetterUseApkBuf()
                        ? Constants.LOAD_TYPE_APK_BUF
                        : Constants.LOAD_TYPE_DEX_BUF;
            } else {
                type = Constants.LOAD_TYPE_ZIP_OPT;
            }
        }

        if (type == Constants.LOAD_TYPE_ZIP_OPT) {
            File zipFile = new File(zipDir, secondaryNumber + Constants.ZIP_SUFFIX);
            File zipOptFile = new File(zipDir, secondaryNumber + Constants.ODEX_SUFFIX);

            if (isZipFileValid(zipFile, secondaryNumber)) {
                return new DexHolder.ZipOpt(secondaryNumber, zipFile, zipOptFile);
            } else {
                ZipFile apkZipFile = new ZipFile(apkFile);
                ZipEntry dexFileEntry = apkZipFile.getEntry(Constants.DEX_PREFIX + secondaryNumber + Constants.DEX_SUFFIX);
                DexHolder.ZipOpt zipOpt = DexHolder.obtainValidZipDex(mPreferences, secondaryNumber, zipFile, zipOptFile, apkZipFile, dexFileEntry);
                apkZipFile.close();
                return zipOpt;
            }
        }

        File dexFile = new File(dexDir, secondaryNumber + Constants.DEX_SUFFIX);
        File optDexFile = new File(odexDir, secondaryNumber + Constants.ODEX_SUFFIX);

        if (type == Constants.LOAD_TYPE_DEX_OPT) {
            File validDexFile = getValidDexFile(dexFile, secondaryNumber);
            if (validDexFile != null) {
                File validOptDexFile = getValidOptDexFile(optDexFile, secondaryNumber);
                if (validOptDexFile != null) {
                    return new DexHolder.DexOpt(secondaryNumber, validDexFile, validOptDexFile, false);
                } else {
                    if (BoostNative.isSupportFastLoad()) {
                        type = Constants.LOAD_TYPE_DEX_BUF;
                    } else {
                        return new DexHolder.DexOpt(secondaryNumber, validDexFile, optDexFile, true);
                    }
                }
            } else {
                if (BoostNative.isSupportFastLoad()) {
                    type = Constants.LOAD_TYPE_APK_BUF;
                } else {
                    ZipFile apkZipFile = new ZipFile(apkFile);
                    ZipEntry dexFileEntry = apkZipFile.getEntry(Constants.DEX_PREFIX + secondaryNumber + Constants.DEX_SUFFIX);
                    return DexHolder.obtainValidForceDexOpt(mPreferences, secondaryNumber, dexFile, optDexFile, apkZipFile, dexFileEntry);
                }
            }
        }

        if (type == Constants.LOAD_TYPE_DEX_BUF) {
            File validDexFile = getValidDexFile(dexFile, secondaryNumber);
            if (BoostNative.isSupportFastLoad()) {
                if (validDexFile != null) {
                    return new DexHolder.DexBuffer(secondaryNumber, validDexFile, optDexFile);
                } else {
                    type = Constants.LOAD_TYPE_APK_BUF;
                }
            } else {
                if (validDexFile != null) {
                    return new DexHolder.DexOpt(secondaryNumber, validDexFile, optDexFile, true);
                } else {
                    ZipFile apkZipFile = new ZipFile(apkFile);
                    ZipEntry dexFileEntry = apkZipFile.getEntry(Constants.DEX_PREFIX + secondaryNumber + Constants.DEX_SUFFIX);
                    return DexHolder.obtainValidForceDexOpt(mPreferences, secondaryNumber, dexFile, optDexFile, apkZipFile, dexFileEntry);
                }
            }
        }

        if (type == Constants.LOAD_TYPE_APK_BUF) {
            if (!BoostNative.isSupportFastLoad()) {
                Monitor.get().logError("Do not support apk buf!");
            }
            ZipFile apkZipFile = new ZipFile(apkFile);
            ZipEntry dexFileEntry = apkZipFile.getEntry(Constants.DEX_PREFIX + secondaryNumber + Constants.DEX_SUFFIX);
            byte[] bytes = obtainEntryBytesInApk(apkZipFile, dexFileEntry);
            DexHolder dexHolder = new DexHolder.ApkBuffer(secondaryNumber, bytes, dexFile, optDexFile);
            apkZipFile.close();
            return dexHolder;
        }

        return null;
    }

    private byte[] obtainEntryBytesInApk(ZipFile apkZipFile, ZipEntry dexFileEntry) throws IOException {
        return Utility.obtainEntryBytesInZip(apkZipFile, dexFileEntry);
    }

    private File obtainEntryFileInApk(ZipFile apkZipFile, ZipEntry dexFileEntry, File target) throws IOException {
        return Utility.obtainEntryFileInZip(apkZipFile, dexFileEntry, target);
    }


    private File getValidDexFile(File file, int secondaryNumber) throws IOException {
        if (!checkFileValid(secondaryNumber, Constants.KEY_DEX_CHECKSUM, Constants.KEY_DEX_TIME,
                file, false)) {
            return null;
        }

        return file;
    }

    private File getValidOptDexFile(File file, int secondaryNumber) throws IOException {
        if (!file.exists()) {
            Monitor.get().logInfo("opt file does not exist: " + file.getPath());
            return null;
        }

        if (!checkFileValid(secondaryNumber, Constants.KEY_ODEX_CHECKSUM, Constants.KEY_ODEX_TIME,
                file, false)) {
            return null;
        }

        return file;
    }

    private boolean checkFileValid(int secondaryNumber,
                                   String keyCheckSum, String keyTime,
                                   File file, boolean isZip) {
        if (!file.exists()) {
            Monitor.get().logWarning("File does not exist! " + file.getPath());
            return false;
        }

        long expectedModTime = mPreferences.getLong(keyTime + secondaryNumber, Constants.NO_VALUE);
        long lastModified = file.lastModified();
        if (expectedModTime != lastModified) {
            Monitor.get().logWarning("Invalid file: "
                    + " (key \"" + (keyCheckSum + keyTime + secondaryNumber) + "\"), expected modification time: "
                    + expectedModTime + ", modification time: " + lastModified);
            return false;
        }

        long checkSum = 0;
        boolean doCheckSum = true;
        if (Constants.KEY_DEX_CHECKSUM.equals(keyCheckSum)) {
            try {
                if (isZip) {
                    checkSum = Utility.doZipCheckSum(file);
                } else if (mDoCheckSum) {
                    checkSum = Utility.doFileCheckSum(file);
                } else {
                    doCheckSum = false;
                }
            } catch (IOException e) {
                return false;
            }
        } else if (Constants.KEY_ODEX_CHECKSUM.equals(keyCheckSum)) {
            checkSum = file.length();
        } else {
            Monitor.get().logWarning("unsupported checksum key: " + keyCheckSum);
            return false;
        }

        if (doCheckSum) {
            long expectedCheckSum = mPreferences.getLong(keyCheckSum + secondaryNumber, Constants.NO_VALUE);
            if (expectedCheckSum != checkSum) {
                Monitor.get().logWarning("Invalid file: "
                        + " (key \"" + (keyCheckSum + keyTime + secondaryNumber) + "\"), expected checksum: "
                        + expectedCheckSum + ", file checksum: " + checkSum);
                return false;
            }
        }

        return true;
    }

    private boolean isZipFileValid(File zipFile, int secondaryNumber) {
        return checkFileValid(secondaryNumber, Constants.KEY_DEX_CHECKSUM,
                Constants.KEY_DEX_TIME, zipFile, true);
    }

}
