package com.bytedance.boost_multidex;

import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/3/3.
 */
public class Monitor {
    private static final boolean enableLog = true;

    private static Monitor sMonitor;

    private ScheduledExecutorService mExecutor = Executors.newScheduledThreadPool(1);

    private String mProcessName;

    static void init(Monitor monitor) {
        sMonitor = monitor != null ? monitor : new Monitor();
    }

    static Monitor get() {
        return sMonitor;
    }

    private ScheduledExecutorService getExecutor() {
        return mExecutor;
    }

    String getProcessName() {
        return mProcessName;
    }

    public Monitor setExecutor(ScheduledExecutorService executor) {
        mExecutor = executor;
        return this;
    }

    public Monitor setProcessName(String processName) {
        mProcessName = processName;
        return this;
    }

    protected void loadLibrary(String libName) {
        System.loadLibrary(libName);
    }

    protected void logError(String msg) {
        if (!enableLog) {
            return;
        }

        Log.println(Log.ERROR, Constants.TAG, msg);
    }

    protected void logWarning(String msg) {
        if (!enableLog) {
            return;
        }

        Log.w(Constants.TAG, msg);
    }

    protected void logInfo(String msg) {
        if (!enableLog) {
            return;
        }

        // Log.println(Log.INFO, Constants.TAG, msg);
        Log.i(Constants.TAG, msg);
    }

    protected void logDebug(String msg) {
        if (!enableLog) {
            return;
        }

        // Log.println(Log.DEBUG, Constants.TAG, msg);
        Log.d(Constants.TAG, msg);
    }

    protected void logError(String msg, Throwable tr) {
        if (!enableLog) {
            return;
        }

        Log.e(Constants.TAG, msg, tr);
    }

    protected void logWarning(String msg, Throwable tr) {
        if (!enableLog) {
            return;
        }

        Log.w(Constants.TAG, msg, tr);
    }

    protected boolean isEnableNativeCheckSum() {
        return true;
    }

    protected void logErrorAfterInstall(String msg, Throwable tr) {
        Log.e(Constants.TAG, msg, tr);
    }

    protected void reportAfterInstall(long cost, long freeSpace, long reducedSpace, String dexHolderInfo) {
        Log.println(Log.INFO, Constants.TAG, "Cost time: " + cost + ", free space: " + freeSpace
                + ", reduced space: " + reducedSpace + ", holder: " + dexHolderInfo);
    }

    protected void doBeforeHandleOpt() {
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void doAfterInstall(final Runnable optRunnable) {
        Looper.myQueue().addIdleHandler(new MessageQueue.IdleHandler() {
            @Override
            public boolean queueIdle() {
                getExecutor().schedule(optRunnable, 5_000, TimeUnit.MILLISECONDS);
                return false;
            }
        });
    }
}
