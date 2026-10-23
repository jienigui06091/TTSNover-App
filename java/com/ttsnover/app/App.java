package com.ttsnover.reader;

import android.app.Application;
import android.content.Context;

/** 全局 Context 持有者（Api / Store / TtsClient 都需要），避免到处传 Context。 */
public class App extends Application {

    static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
    }

    public static Context ctx() {
        return ctx;
    }
}
