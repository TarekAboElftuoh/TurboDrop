package com.turbodrop.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppLogger {
    private static final String TAG = "TurboDrop";
    private static File logFile = null;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public static void init(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                logFile = new File(dir, "turbodrop_android.log");
            }
        } catch (Exception ignored) {}
    }

    public static synchronized void i(String message) {
        Log.i(TAG, message);
        writeToFile("INFO", message, null);
    }

    public static synchronized void w(String message) {
        Log.w(TAG, message);
        writeToFile("WARN", message, null);
    }

    public static synchronized void e(String message, Throwable t) {
        Log.e(TAG, message, t);
        writeToFile("ERROR", message, t);
    }

    private static void writeToFile(String level, String msg, Throwable t) {
        if (logFile == null) return;
        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            String time = sdf.format(new Date());
            pw.println("[" + time + "] [" + level + "] " + msg);
            if (t != null) {
                t.printStackTrace(pw);
            }
        } catch (Exception ignored) {}
    }

    public static String getLogFilePath() {
        return (logFile != null) ? logFile.getAbsolutePath() : "";
    }
}
