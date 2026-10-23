package com.ttsnover.reader;

import android.app.Application;
import android.content.Context;

public class App extends Application {
    public static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
    }
}
