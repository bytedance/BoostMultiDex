package com.bytedance.boost_multidex;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/31.
 */
class Locker {
    private RandomAccessFile lockRaf;
    private FileLock cacheLock;
    private FileChannel lockChannel;

    private File lockFile;

    Locker(File lockFile) {
        this.lockFile = lockFile;
    }

    void lock() throws IOException {
        lockRaf = new RandomAccessFile(lockFile, "rw");
        try {
            lockChannel = lockRaf.getChannel();
            try {
                Monitor.get().logInfo("Blocking on lock " + lockFile.getPath());
                cacheLock = lockChannel.lock();
            } catch (IOException e) {
                Utility.closeQuietly(lockChannel);
                throw e;
            }
            Monitor.get().logInfo("Acquired on lock " + lockFile.getPath());
        } catch (IOException e) {
            Utility.closeQuietly(lockRaf);
            throw e;
        }
    }

    boolean test() throws IOException {
        lockRaf = new RandomAccessFile(lockFile, "rw");
        lockChannel = lockRaf.getChannel();
        try {
            Monitor.get().logInfo("Blocking on lock " + lockFile.getPath());
            cacheLock = lockChannel.tryLock();
            return cacheLock != null;
        } catch (IOException e) {
            Monitor.get().logInfo("Aborting on lock " + lockFile.getPath());
            return false;
        } finally {
            Monitor.get().logInfo("Acquired on lock " + lockFile.getPath());
        }
    }

    void close() {
        if (cacheLock != null) {
            try {
                cacheLock.release();
            } catch (IOException ignored) {
            }
        }
        Monitor.get().logInfo("Released lock " + lockFile.getPath());
        Utility.closeQuietly(lockChannel);
        Utility.closeQuietly(lockRaf);
    }
}
