/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* Apache Harmony HEADER because the code in this class comes mostly from ZipFile, ZipEntry and
 * ZipConstants from android libcore.
 */

package com.bytedance.boost_multidex;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/28.
 */
public class Utility {
    /**
     * Replace the value of a field containing a non null array, by a new array containing the
     * elements of the original array plus the elements of extraElements.
     * @param instance the instance whose field is to be modified.
     * @param fieldName the field to modify.
     * @param extraElements elements to append at the end of the array.
     */
    static void expandFieldArray(Object instance, String fieldName,
                                         Object[] extraElements) throws NoSuchFieldException, IllegalArgumentException,
            IllegalAccessException {
        Field field = findField(instance.getClass(), fieldName);
        Object[] original = (Object[]) field.get(instance);
        Object[] combined = (Object[]) Array.newInstance(
                original.getClass().getComponentType(), original.length + extraElements.length);
        System.arraycopy(original, 0, combined, 0, original.length);
        System.arraycopy(extraElements, 0, combined, original.length, extraElements.length);
        field.set(instance, combined);
    }

    static boolean isBetterUseApkBuf() {
        Runtime runtime = Runtime.getRuntime();
        long freeSpaceAfter = Environment.getDataDirectory().getFreeSpace();
        long remainedMem = runtime.maxMemory() - runtime.totalMemory();
        Monitor.get().logInfo("Memory remains " + remainedMem
                            + ", free space " + freeSpaceAfter);
        return remainedMem > Constants.MEM_THRESHOLD
                || freeSpaceAfter < Constants.SPACE_MIN_THRESHOLD;
    }

    static void clearDirFiles(File dir) {
        if (!dir.exists()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            Monitor.get().logWarning("Failed to list secondary dex dir content (" + dir.getPath() + ").");
            return;
        }
        for (File oldFile : files) {
            Monitor.get().logInfo("Trying to delete old file " + oldFile.getPath() + " of size " +
                    oldFile.length());
            if (!oldFile.delete()) {
                Monitor.get().logWarning("Failed to delete old file " + oldFile.getPath());
            } else {
                Monitor.get().logInfo("Deleted old file " + oldFile.getPath());
            }
        }
    }

    static long doFileCheckSum(File file) throws IOException {
        long result = 0;

        if (!file.exists()) {
            Monitor.get().logInfo("File is not exist: " + file.getPath());
            return result;
        }

        if (Monitor.get().isEnableNativeCheckSum()) {
            try {
                result = BoostNative.obtainCheckSum(file.getPath());
            } catch(Throwable tr) {
                Monitor.get().logWarning("Failed to native obtainCheckSum in " + file.getPath(), tr);
            }
        }

        if (result == 0) {
            Monitor.get().logWarning("Fall back to java impl");
            CheckedInputStream checkedInputStream = null;
            byte[] buf = new byte[Constants.BUFFER_SIZE];
            try {
                checkedInputStream = new CheckedInputStream(
                        new FileInputStream(file), new Adler32());

                for (;;) {
                    if (checkedInputStream.read(buf) < 0) {
                        break;
                    }
                }

                result = checkedInputStream.getChecksum().getValue();
            } finally {
                Utility.closeQuietly(checkedInputStream);
            }
        }

        return result;
    }

    /**
     * Compute crc32 of the central directory of an apk. The central directory contains
     * the crc32 of each entries in the zip so the computed result is considered valid for the whole
     * zip file. Does not support zip64 nor multidisk but it should be OK for now since ZipFile does
     * not either.
     */
    static long doZipCheckSum(File apk) throws IOException {
        RandomAccessFile raf = null;

        try {
            raf = new RandomAccessFile(apk, "r");
            long scanOffset = raf.length() - Constants.ENDHDR;
            if (scanOffset < 0) {
                throw new ZipException("File too short to be a zip file: " + raf.length());
            }

            long stopOffset = scanOffset - 0x10000 /* ".ZIP file comment"'s max length */;
            if (stopOffset < 0) {
                stopOffset = 0;
            }

            int endSig = Integer.reverseBytes(Constants.ENDSIG);
            while (true) {
                raf.seek(scanOffset);
                if (raf.readInt() == endSig) {
                    break;
                }

                scanOffset--;
                if (scanOffset < stopOffset) {
                    throw new ZipException("End Of Central Directory signature not found");
                }
            }
            // Read the End Of Central Directory. ENDHDR includes the signature
            // bytes,
            // which we've already read.

            // Pull out the information we need.
            raf.skipBytes(2); // diskNumber
            raf.skipBytes(2); // diskWithCentralDir
            raf.skipBytes(2); // numEntries
            raf.skipBytes(2); // totalNumEntries
            long size = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;
            long offset = Integer.reverseBytes(raf.readInt()) & 0xFFFFFFFFL;

            // computeCrcOfCentralDir
            CRC32 crc = new CRC32();
            long stillToRead = size;
            raf.seek(offset);
            int length = (int) Math.min(Constants.BUFFER_SIZE, stillToRead);
            byte[] buffer = new byte[Constants.BUFFER_SIZE];
            length = raf.read(buffer, 0, length);
            while (length != -1) {
                crc.update(buffer, 0, length);
                stillToRead -= length;
                if (stillToRead == 0) {
                    break;
                }
                length = (int) Math.min(Constants.BUFFER_SIZE, stillToRead);
                length = raf.read(buffer, 0, length);
            }
            return crc.getValue();
        } finally {
            closeQuietly(raf);
        }
    }

    static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (IOException e) {
            Monitor.get().logWarning("Failed to close resource", e);
        }
    }

    static void mkdirChecked(File dir) throws IOException {
        if (!dir.exists()) {
            dir.mkdirs();
        }

        if (!dir.isDirectory()) {
            File parent = dir.getParentFile();
            if (parent == null) {
                Monitor.get().logError("Failed to create dir " + dir.getPath() + ". Parent file is null.");
            } else {
                Monitor.get().logError("Failed to create dir " + dir.getPath() +
                        ". parent file is a dir " + parent.isDirectory() +
                        ", a file " + parent.isFile() +
                        ", exists " + parent.exists() +
                        ", readable " + parent.canRead() +
                        ", writable " + parent.canWrite());
            }
            throw new IOException("Failed to create directory " + dir.getPath());
        }
    }


    static Field findFieldRecursively(Class<?> targetClazz, String name) throws NoSuchFieldException {
        for (Class<?> clazz = targetClazz; clazz != null; clazz = clazz.getSuperclass()) {
            try {
                return findField(clazz, name);
            } catch (NoSuchFieldException e) {
                // ignore and search next
            }
        }

        throw new NoSuchFieldException("Field " + name + " not found in " + targetClazz);
    }

    static Field findField(Class<?> targetClazz, String name) throws NoSuchFieldException {
        Field field = targetClazz.getDeclaredField(name);
        if (!field.isAccessible()) {
            field.setAccessible(true);
        }

        return field;
    }

    static Method findMethodRecursively(Class<?> targetClazz, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Class<?> clazz = targetClazz; clazz != null; clazz = clazz.getSuperclass()) {
            try {
                return findMethod(clazz, name, parameterTypes);
            } catch (NoSuchMethodException e) {
                // ignore and search next
            }
        }

        throw new NoSuchMethodException("Method " + name + " with parameters " +
                Arrays.asList(parameterTypes) + " not found in " + targetClazz);
    }

    static Method findMethod(Class<?> targetClazz, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = targetClazz.getDeclaredMethod(name, parameterTypes);
        if (!method.isAccessible()) {
            method.setAccessible(true);
        }

        return method;
    }

    static Constructor findConstructor(Class<?> targetClazz, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Constructor constructor = targetClazz.getDeclaredConstructor(parameterTypes);
        if (!constructor.isAccessible()) {
            constructor.setAccessible(true);
        }

        return constructor;
    }

    static File ensureDirCreated(File parentDir, String dirName) throws IOException {
        File resultDir = new File(parentDir, dirName);
        mkdirChecked(resultDir);
        return resultDir;
    }

    static File obtainEntryFileInZip(ZipFile apkZipFile, ZipEntry fileEntry, File target) throws IOException {
        IOException suppressedException = null;

        int retriedCount = Constants.MAX_EXTRACT_ATTEMPTS;
        while (retriedCount > 0) {
            InputStream in = apkZipFile.getInputStream(fileEntry);
            try {
                return obtainEntryFileFromInputStream(in, target);
            } catch (IOException e) {
                suppressedException = e;
            } finally {
                closeQuietly(in);
            }

            retriedCount--;
        }

        throw suppressedException;
    }

    static File obtainEntryFileFromInputStream(InputStream in, File target) throws IOException {
        // Temp files must not start with extractedFilePrefix to get cleaned up in prepareDexDir()
        File tmp = File.createTempFile("tmp-", target.getName(),
                target.getParentFile());
        Monitor.get().logInfo("Extracting " + tmp.getPath());
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buffer = new byte[Constants.BUFFER_SIZE];
            int length = in.read(buffer);
            while (length != -1) {
                out.write(buffer, 0, length);
                length = in.read(buffer);
            }
            if (!tmp.setReadOnly()) {
                throw new IOException("Failed to mark readonly \"" + tmp.getAbsolutePath() +
                        "\" (tmp of \"" + target.getAbsolutePath() + "\")");
            }
            Monitor.get().logInfo("Renaming to " + target.getPath());
            if (!tmp.renameTo(target)) {
                throw new IOException("Failed to rename \"" + tmp.getAbsolutePath() +
                        "\" to \"" + target.getAbsolutePath() + "\"");
            }
            return target;
        } finally {
            closeQuietly(out);
            tmp.delete(); // return status ignored
        }
    }

    static byte[] obtainEntryBytesInZip(ZipFile apkZipFile, ZipEntry dexFileEntry) throws IOException {
        IOException suppressedException = null;

        int retriedCount = Constants.MAX_EXTRACT_ATTEMPTS;
        while (retriedCount > 0) {
            InputStream in = null;
            try {
                in = apkZipFile.getInputStream(dexFileEntry);
                return obtainBytesFromInputStream(in);
            } catch (IOException e) {
                suppressedException = e;
            } finally {
                closeQuietly(in);
            }
            retriedCount--;
        }

        throw suppressedException;
    }

    static byte[] obtainBytesFromInputStream(InputStream in) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[Constants.BUFFER_SIZE];
            int length;
            while ((length = in.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, length);
            }
            return byteArrayOutputStream.toByteArray();
        } finally {
            closeQuietly(byteArrayOutputStream);
        }
    }

    static void obtainZipForEntryFileInZip(ZipFile apkZipFile, ZipEntry dexFileEntry, File validZipFile) throws IOException {
        IOException suppressedException = null;

        int retriedCount = Constants.MAX_EXTRACT_ATTEMPTS;
        while (retriedCount > 0) {
            InputStream in = apkZipFile.getInputStream(dexFileEntry);

            File tmp = File.createTempFile("tmp-", Constants.ZIP_SUFFIX,
                    validZipFile.getParentFile());

            try {
                ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
                try {
                    ZipEntry classesDex = new ZipEntry("classes.dex");
                    // keep zip entry time since it is the criteria used by Dalvik
                    classesDex.setTime(dexFileEntry.getTime());
                    out.putNextEntry(classesDex);

                    byte[] buffer = new byte[Constants.BUFFER_SIZE];
                    int length = in.read(buffer);
                    while (length != -1) {
                        out.write(buffer, 0, length);
                        length = in.read(buffer);
                    }
                    out.closeEntry();
                } finally {
                    out.close();
                }
                if (!tmp.setReadOnly()) {
                    throw new IOException("Failed to mark readonly \"" + tmp.getAbsolutePath() +
                            "\" (tmp of \"" + validZipFile.getAbsolutePath() + "\")");
                }
                Monitor.get().logInfo("Renaming to " + validZipFile.getPath());
                if (!tmp.renameTo(validZipFile)) {
                    throw new IOException("Failed to rename \"" + tmp.getAbsolutePath() +
                            "\" to \"" + validZipFile.getAbsolutePath() + "\"");
                }
                return;
            } catch (IOException e) {
                suppressedException = e;
            }finally {
                Utility.closeQuietly(in);
                tmp.delete(); // return status ignored
            }

            retriedCount--;
        }

        if (suppressedException != null) {
            throw suppressedException;
        }
    }

    static byte[] obtainBytesInFile(File file) {
        RandomAccessFile randomAccessFile = null;
        try {
            randomAccessFile = new RandomAccessFile(file.getPath(), "r");
            byte[] bytes = new byte[(int)randomAccessFile.length()];
            randomAccessFile.readFully(bytes);
            return bytes;
        } catch (IOException e) {
            Monitor.get().logWarning("Fail to get bytes of file", e);
            return null;
        } finally {
            closeQuietly(randomAccessFile);
        }
    }

    static boolean storeBytesToFile(byte[] bytes, File file) {
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(file);
            fileOutputStream.write(bytes);
            return true;
        } catch (IOException e) {
            Monitor.get().logError("fail to store bytes to file", e);
            return false;
        } finally {
            closeQuietly(fileOutputStream);
        }
    }

    static boolean isOptimizeProcess(String processName) {
        return processName != null && processName.endsWith(":boost_multidex");
    }

//    static boolean isMainProcess(Context context) {
//        String processName = getCurProcessName(context);
//        if (processName != null && processName.contains(":")) {
//            return false;
//        }
//        return (processName != null && processName.equals(context.getPackageName()));
//    }

    static String getCurProcessName(Context context) {
        try{
            int pid = android.os.Process.myPid();
            ActivityManager mActivityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningAppProcessInfo appProcess : mActivityManager.getRunningAppProcesses()) {
                if (appProcess.pid == pid) {
                    return appProcess.processName;
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
