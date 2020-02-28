package com.bytedance.boost_multidex;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/4/8.
 */
public class Result {
    private static Result result = new Result();

    public boolean modified;

    public long freeSpaceBefore;

    public long freeSpaceAfter;

    public String vmLibName;

    public boolean isYunOS;

    public File dataDir;

    public File rootDir;

    public File dexDir;

    public File optDexDir;

    public File zipDir;

    public Throwable fatalThrowable;

    public List<Throwable> unFatalThrowable = new ArrayList<>();

    public List<String> dexInfoList = new ArrayList<>();

    public boolean supportFastLoadDex;

    public static Result get() {
        if (result != null) {
            return result;
        } else {
            Log.w(Constants.TAG, "Avoid npe, but return a invalid tmp result");
            return new Result();
        }
    }

    private Result() {
    }

    public void setDirs(File dataDir, File rootDir, File dexDir, File optDexDir, File zipDir) {
        this.dataDir = dataDir;
        this.rootDir = rootDir;
        this.dexDir = dexDir;
        this.optDexDir = optDexDir;
        this.zipDir = zipDir;
    }

    public void setFatalThrowable(Throwable tr) {
        fatalThrowable = tr;
    }

    public void addUnFatalThrowable(Throwable tr) {
        unFatalThrowable.add(tr);
    }

    public void addDexInfo(String dexInfo) {
        dexInfoList.add(dexInfo);
    }
}
