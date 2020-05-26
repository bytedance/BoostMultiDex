package com.bytedance.app.boost_multidex;

import android.app.Application;
import android.content.Context;
import android.support.multidex.MultiDex;
import android.util.Log;

import com.bytedance.boost_multidex.BoostMultiDex;
import com.bytedance.boost_multidex.Result;

/**
 * Created by Xiaolin(xiaolin.gan@bytedance.com) on 2019/2/22.
 */

public class MainApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        boolean useBoostMultiDex = true;

        long start = System.currentTimeMillis();
        if (useBoostMultiDex) {
            Result result = BoostMultiDex.install(this);
            if (result != null && result.fatalThrowable != null) {
                Log.e("BMD", "exception occored " + result.fatalThrowable);
            }
        } else {
            MultiDex.install(this);
        }

        Log.i("BMD", "multidex cost time " + (System.currentTimeMillis() - start) + " ms");
    }
}

