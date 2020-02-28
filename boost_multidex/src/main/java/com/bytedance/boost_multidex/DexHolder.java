package com.bytedance.boost_multidex;

import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexFile;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/25.
 */
abstract class DexHolder {
    File mFile;

    abstract Object toDexFile();

    protected Object toDexListElement(DexLoader.ElementConstructor elementConstructor) throws Exception {
        Object dexFile = toDexFile();
        return dexFile == null ? null : elementConstructor.newInstance(mFile, dexFile);
    }

    abstract DexHolder toFasterHolder(SharedPreferences preferences);

    abstract StoreInfo getInfo();


    private static void putTypeInfo(SharedPreferences.Editor editor, int secondaryNumber, int type) {
        editor.putInt(Constants.KEY_DEX_OBJ_TYPE + secondaryNumber, type);
    }

    private static void putZipOptInfo(SharedPreferences.Editor editor, int secondaryNumber, File zipFile) throws IOException {
        String keyCheckSum = Constants.KEY_DEX_CHECKSUM;

        long checkSum = Utility.doZipCheckSum(zipFile);
        editor.putLong(keyCheckSum + secondaryNumber, checkSum);

        String keyTime = Constants.KEY_DEX_TIME;
        long time = zipFile.lastModified();
        editor.putLong(keyTime + secondaryNumber, time);

        Monitor.get().logInfo("Put z key " + (keyCheckSum + keyTime + secondaryNumber)
                + " checksum=" + checkSum + ", time=" + time);
    }

    private static void putDexFileInfo(SharedPreferences.Editor editor, int secondaryNumber, File file) throws IOException {
        String keyCheckSum = Constants.KEY_DEX_CHECKSUM;

        long checkSum = Utility.doFileCheckSum(file);
        editor.putLong(keyCheckSum + secondaryNumber, checkSum);

        String keyTime = Constants.KEY_DEX_TIME;
        long time = file.lastModified();
        editor.putLong(keyTime + secondaryNumber, time);

        Monitor.get().logInfo("Put f key " + (keyCheckSum + keyTime + secondaryNumber)
                + " checksum=" + checkSum + ", time=" + time);
    }

    private static void putDexOptInfo(SharedPreferences.Editor editor, int secondaryNumber, File optFile) throws IOException {
        String keyCheckSum = Constants.KEY_ODEX_CHECKSUM;

        long checkSum = optFile.length();
        editor.putLong(keyCheckSum + secondaryNumber, checkSum);

        String keyTime = Constants.KEY_ODEX_TIME;
        long time = optFile.lastModified();
        editor.putLong(keyTime + secondaryNumber, time);

        Monitor.get().logInfo("Put o key " + (keyCheckSum + keyTime + secondaryNumber)
                + " checksum=" + checkSum + ", time=" + time);
    }

    static DexHolder obtainValidDexBuffer(SharedPreferences preferences, int secondaryNumber, File validDexFile, File optDexFile)
            throws IOException {
        SharedPreferences.Editor editor = preferences.edit();
        putTypeInfo(editor, secondaryNumber, Constants.LOAD_TYPE_DEX_BUF);
        putDexFileInfo(editor, secondaryNumber, validDexFile);
        editor.commit();

        return new DexHolder.DexBuffer(secondaryNumber, validDexFile, optDexFile);
    }

    static DexHolder obtainValidForceDexOpt(SharedPreferences preferences, int secondaryNumber, File dexFile, File optDexFile,
                                            ZipFile apkZipFile, ZipEntry dexFileEntry) throws IOException {
        File validDexFile = Utility.obtainEntryFileInZip(apkZipFile, dexFileEntry, dexFile);
        SharedPreferences.Editor editor = preferences.edit();
        putTypeInfo(editor, secondaryNumber, Constants.LOAD_TYPE_DEX_OPT);
        putDexFileInfo(editor, secondaryNumber, validDexFile);
        editor.commit();
        return new DexHolder.DexOpt(secondaryNumber, validDexFile, optDexFile, true);
    }

    static DexHolder obtainValidDexOpt(SharedPreferences preferences, int secondaryNumber, File validDexFile, File optDexFile) throws IOException {
        SharedPreferences.Editor editor = preferences.edit();
        putTypeInfo(editor, secondaryNumber, Constants.LOAD_TYPE_DEX_OPT);
        putDexOptInfo(editor, secondaryNumber, optDexFile);
        editor.commit();
        return new DexHolder.DexOpt(secondaryNumber, validDexFile, optDexFile, false);
    }

    static DexHolder.ZipOpt obtainValidZipDex(SharedPreferences preferences, int secondaryNumber, File validZipFile, File validZipOptFile, ZipFile apkZipFile, ZipEntry dexFileEntry) throws IOException {
        Utility.obtainZipForEntryFileInZip(apkZipFile, dexFileEntry, validZipFile);
        SharedPreferences.Editor editor = preferences.edit();
        putTypeInfo(editor, secondaryNumber, Constants.LOAD_TYPE_ZIP_OPT);
        putZipOptInfo(editor, secondaryNumber, validZipFile);
        editor.commit();
        return new DexHolder.ZipOpt(secondaryNumber, validZipFile, validZipOptFile);
    }

    static class ZipOpt extends DexHolder {
        private int mIndex;
        private File mOptFile;

        ZipOpt(int index, File file, File optFile) {
            this.mIndex = index;
            this.mFile = file;
            this.mOptFile = optFile;
        }

        @Override
        public Object toDexFile() {
            try {
                return DexFile.loadDex(mFile.getPath(), mOptFile.getPath(), 0);
            } catch (IOException e) {
                Monitor.get().logError("Fail to load dex file");
                throw new RuntimeException(e);
            }
        }

        @Override
        public DexHolder toFasterHolder(SharedPreferences preferences) {
            return null;
        }

        @Override
        public StoreInfo getInfo() {
            return null;
        }

        @Override
        public String toString() {
            return super.toString() + ", index: " + mIndex
                    + ", [file: " + mFile.getPath() + ", size: " + mFile.length()
                    + "], [opt file: " + mOptFile + ", size: " + mOptFile.length() + "]";
        }
    }

    static class DexOpt extends DexHolder {
        private int mIndex;
        private File mOptFile;
        private boolean mForceOpt;

        DexOpt(int index, File file, File optFile, boolean forceOpt) {
            this.mIndex = index;
            this.mFile = file;
            this.mOptFile = optFile;
            this.mForceOpt = forceOpt;
        }

        @Override
        public Object toDexFile() {
            try {
                return DexFile.loadDex(mFile.getPath(), mOptFile.getPath(), 0);
            } catch (IOException e1) {
                Monitor.get().logError("Fail to load dex file first time", e1);
                try {
                    if (mForceOpt) {
                        return DexFile.loadDex(mFile.getPath(), mOptFile.getPath(), 0);
                    } else {
                        return BoostNative.loadDirectDex(mFile.getPath(), null);
                    }
                } catch (IOException e2) {
                    Monitor.get().logError("Fail to load dex file", e2);
                    throw new RuntimeException(e2);
                }
            }
        }

        @Override
        public DexHolder toFasterHolder(SharedPreferences preferences) {
            return null;
        }

        @Override
        public StoreInfo getInfo() {
            return new StoreInfo(Constants.LOAD_TYPE_DEX_OPT, mIndex, mOptFile);
        }

        @Override
        public String toString() {
            return super.toString() + ", index: " + mIndex
                    + ", [file: " + mFile.getPath() + ", size: " + mFile.length()
                    + "], [opt file: " + mOptFile + ", size: " + mOptFile.length()
                    + "], force: " + mForceOpt ;
        }
    }

    static class DexBuffer extends DexHolder {
        private int mIndex;
        private File mOptFile;

        DexBuffer(int index, File file, File optFile) {
            this.mIndex = index;
            this.mFile = file;
            this.mOptFile = optFile;
        }

        @Override
        public Object toDexFile() {
            try {
                return BoostNative.loadDirectDex(mFile.getPath(), null);
            } catch (Exception e) {
                Monitor.get().logError("Fail to create DexFile: " + toString(), e);
                Result.get().unFatalThrowable.add(e);
                return null;
            }
        }

        @Override
        public DexHolder toFasterHolder(SharedPreferences preferences) {
            try {
                if (!BoostNative.isSupportFastLoad() || !BoostNative.makeOptDexFile(mFile.getPath(), mOptFile.getPath())) {
                    Monitor.get().logWarning("Opt dex in origin way");
                    DexFile.loadDex(mFile.getPath(), mOptFile.getPath(), 0).close();
                }
                return obtainValidDexOpt(preferences, mIndex, mFile, mOptFile);
            } catch (IOException e) {
                Monitor.get().logError("Fail to opt dex finally", e);
                return null;
            }
        }

        @Override
        public StoreInfo getInfo() {
            return new StoreInfo(Constants.LOAD_TYPE_DEX_BUF, mIndex, mFile);
        }

        @Override
        public String toString() {
            return super.toString() + ", index: " + mIndex
                    + ", [file: " + mFile.getPath() + ", size: " + mFile.length()
                    + "], [opt file: " + mOptFile + ", size: " + mOptFile.length()
                    + "]";
        }
    }

    static class ApkBuffer extends DexHolder {
        private int mIndex;
        private byte[] mBytes;
        private File mOptFile;

        ApkBuffer(int index, byte[] bytes, File file, File optFile) {
            this.mIndex = index;
            this.mBytes = bytes;
            this.mFile = file;
            this.mOptFile = optFile;
        }

        @Override
        public Object toDexFile() {
            try {
                return BoostNative.loadDirectDex(null, mBytes);
            } catch (Exception e) {
                Monitor.get().logError("Fail to create DexFile: " + toString(), e);
                Result.get().unFatalThrowable.add(e);
                return null;
            }
        }

        @Override
        public Object toDexListElement(DexLoader.ElementConstructor elementConstructor) throws Exception {
            Object dexFile = toDexFile();
            if (dexFile == null) {
                return null;
            }
            return elementConstructor.newInstance(null, dexFile);
        }

        @Override
        public DexHolder toFasterHolder(SharedPreferences preferences) {
            if (Utility.storeBytesToFile(mBytes, mFile)) {
                try {
                    return DexHolder.obtainValidDexBuffer(preferences, mIndex, mFile, mOptFile);
                } catch (IOException e) {
                    Monitor.get().logError("fail to get dex buffer", e);
                    return null;
                }
            } else {
                return null;
            }
        }

        @Override
        public StoreInfo getInfo() {
            return null;
        }

        @Override
        public String toString() {
            return super.toString() + ", index: " + mIndex
                    + ", [file: " + mFile.getPath() + ", size: " + mFile.length()
                    + "], [opt file: " + mOptFile + ", size: " + mOptFile.length()
                    + "], bytes len: " + (mBytes == null ? null : mBytes.length);
        }
    }

    class StoreInfo {
        File file;
        int type;
        int index;

        StoreInfo(int type, int index, File file) {
            this.type = type;
            this.index = index;
            this.file = file;
        }
    }
}
