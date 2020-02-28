package com.bytedance.app.boost_multidex;

import android.app.Application;
import android.content.Context;
import android.support.multidex.MultiDex;

import com.bytedance.boost_multidex.BoostMultiDex;
import com.bytedance.boost_multidex.Monitor;
import com.bytedance.boost_multidex.Result;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/2/22.
 */

public class MainApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        Monitor monitor = new Monitor() {
            @Override
            protected void reportAfterInstall(long cost, long freeSpace, long reducedSpace, String dexHolderInfo) {
                super.reportAfterInstall(cost, freeSpace, reducedSpace, dexHolderInfo);
            }
        };
        Result result = BoostMultiDex.install(this, monitor);
        if (result != null && result.fatalThrowable != null) {
            MultiDex.install(this);
        }
    }
}

