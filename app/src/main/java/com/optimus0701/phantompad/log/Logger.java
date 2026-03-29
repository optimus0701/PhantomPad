package com.optimus0701.phantompad.log;

import android.util.Log;

public class Logger {
    private static boolean sInXposed = true;

    public static void d(String message) {
        if (sInXposed) {
            try {
                de.robv.android.xposed.XposedBridge.log("(PhantomMic) " + message);
            } catch (Throwable t) {
                sInXposed = false;
            }
        }
        Log.d("PhantomMic", message != null ? message : "null");
    }

    public static void e(String message, Throwable err) {
        if (sInXposed) {
            try {
                de.robv.android.xposed.XposedBridge.log("(PhantomMic) " + message);
                de.robv.android.xposed.XposedBridge.log(err);
            } catch (Throwable t) {
                sInXposed = false;
            }
        }
        Log.e("PhantomMic", message, err);
    }

    public static void st() {
        Logger.d(Log.getStackTraceString(new Throwable()));
    }
}
