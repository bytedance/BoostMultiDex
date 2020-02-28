package com.bytedance.app.boost_multidex;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import scala.math.BigDecimal;

public class MainActivity extends Activity {
    static {
        System.loadLibrary("native-lib");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tv = findViewById(R.id.sample_text);
        tv.setText(stringFromJNI());

        try {
            Object object = Class.class.getDeclaredMethod("getDex").invoke(BigDecimal.class);
            Log.d("MainActivity", "dex bytes is " + object);
        } catch (Throwable tr) {
            tr.printStackTrace();
        }
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native String stringFromJNI();
}
