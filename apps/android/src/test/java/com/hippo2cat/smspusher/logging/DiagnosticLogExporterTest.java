package com.hippo2cat.smspusher.logging;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiagnosticLogExporterTest {
    @Test
    public void collectRecentLogsReturnsBoundedCurrentAndRolledFiles() throws Exception {
        Path dir = Files.createTempDirectory("smspusher-logs");
        Files.write(dir.resolve("smspusher.1.log"), "old-line\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("smspusher.log"), "new-line\n".getBytes(StandardCharsets.UTF_8));

        String logs = DiagnosticLogExporter.collectRecentLogs(dir.toFile(), 64);

        assertTrue(logs.contains("old-line"));
        assertTrue(logs.contains("new-line"));
        assertTrue(logs.length() <= 64);
    }

    @Test
    public void collectRecentLogsReturnsEmptyTextForMissingDirectory() {
        String logs = DiagnosticLogExporter.collectRecentLogs(null, 1024);

        assertFalse(logs.contains("Exception"));
        assertTrue(logs.isEmpty());
    }
}
