package com.ttsnover.reader;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/** 全局 Context 持有者（Api / Store / TtsClient 都需要），避免到处传 Context。 */
public class App extends Application {

    static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();

        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                Log.e("TTSNover", "FATAL CRASH in " + (thread != null ? thread.getName() : "unknown"), ex);
                try {
                    File dir = getFilesDir();
                    if (dir != null) {
                        File f = new File(dir, "crash.log");
                        FileOutputStream fos = new FileOutputStream(f, true);
                        String info = "=== Crash at " + System.currentTimeMillis() + " ===\n"
                                + Log.getStackTraceString(ex) + "\n\n";
                        fos.write(info.getBytes("UTF-8"));
                        fos.close();
                    }
                } catch (Exception ignored) {
                }
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, ex);
                }
            }
        });
    }

    public static Context ctx() {
        return ctx;
    }
}
