package com.hippo2cat.smspusher.logging;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DiagnosticLogExporter {
    private static final int MAX_EXPORT_CHARS = 24_000;

    private DiagnosticLogExporter() {}

    public static String recentLogText(Context context) {
        if (context == null) return "";
        return collectRecentLogs(AppLogging.logDir(context), MAX_EXPORT_CHARS);
    }

    static String collectRecentLogs(File logDir, int maxChars) {
        if (logDir == null || maxChars <= 0 || !logDir.isDirectory()) return "";
        File[] files = logDir.listFiles(file -> file.isFile()
            && file.getName().startsWith("smspusher")
            && file.getName().endsWith(".log"));
        if (files == null || files.length == 0) return "";

        List<File> ordered = new ArrayList<>();
        Collections.addAll(ordered, files);
        ordered.sort((left, right) -> {
            int modified = Long.compare(left.lastModified(), right.lastModified());
            return modified != 0 ? modified : left.getName().compareTo(right.getName());
        });

        StringBuilder output = new StringBuilder();
        for (File file : ordered) {
            String text = readUtf8(file);
            if (text.isEmpty()) continue;
            if (output.length() > 0) output.append('\n');
            output.append(text.trim());
        }
        if (output.length() <= maxChars) return output.toString();
        return output.substring(output.length() - maxChars);
    }

    private static String readUtf8(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 256_000L)];
            int read = input.read(bytes);
            if (read <= 0) return "";
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }
}
