package com.hippo2cat.smspusher.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConnectionDetailsUiState {
    public final boolean connected;
    public final String title;
    public final String securityText;
    public final List<Row> rows;

    private ConnectionDetailsUiState(boolean connected, String title, String securityText, List<Row> rows) {
        this.connected = connected;
        this.title = title;
        this.securityText = securityText;
        this.rows = Collections.unmodifiableList(rows);
    }

    public static ConnectionDetailsUiState from(
        boolean connected,
        String localIp,
        String macBaseUrl,
        boolean bonjourFound,
        String deviceId,
        String lastReceivedAt,
        String lastForwardedAt,
        String appVersion,
        Copy copy
    ) {
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(new Row(copy.connectionStatus, connected ? copy.connected : copy.disconnected));
        rows.add(new Row(copy.localIp, nonEmpty(localIp, copy.notObtained)));
        rows.add(new Row(copy.macAddress, nonEmpty(macBaseUrl, copy.unpaired)));
        rows.add(new Row(copy.backgroundListener, connected ? copy.running : copy.notRunning));
        rows.add(new Row(copy.bonjourService, bonjourFound ? copy.active : copy.notFound));
        rows.add(new Row(copy.deviceId, nonEmpty(deviceId, copy.notGenerated)));
        rows.add(new Row(copy.lastReceived, timeText(lastReceivedAt, copy)));
        rows.add(new Row(copy.lastForwarded, timeText(lastForwardedAt, copy)));
        rows.add(new Row(copy.appVersion, nonEmpty(appVersion, copy.unknown)));
        rows.add(new Row(copy.transferMethod, copy.securityText));
        return new ConnectionDetailsUiState(connected, connected ? copy.connected : copy.disconnected, copy.securityText, rows);
    }

    public String allText() {
        StringBuilder builder = new StringBuilder();
        builder.append(title).append('\n').append(securityText);
        for (Row row : rows) {
            builder.append('\n').append(row.title).append('\n').append(row.value);
        }
        return builder.toString();
    }

    private static String timeText(String value, Copy copy) {
        return value == null || value.trim().isEmpty() ? copy.noData : value;
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    public static final class Row {
        public final String title;
        public final String value;

        private Row(String title, String value) {
            this.title = title;
            this.value = value;
        }
    }

    public static final class Copy {
        public final String connectionStatus;
        public final String connected;
        public final String disconnected;
        public final String localIp;
        public final String notObtained;
        public final String macAddress;
        public final String unpaired;
        public final String backgroundListener;
        public final String running;
        public final String notRunning;
        public final String bonjourService;
        public final String active;
        public final String notFound;
        public final String deviceId;
        public final String notGenerated;
        public final String lastReceived;
        public final String lastForwarded;
        public final String appVersion;
        public final String unknown;
        public final String transferMethod;
        public final String securityText;
        public final String noData;

        public Copy(
            String connectionStatus,
            String connected,
            String disconnected,
            String localIp,
            String notObtained,
            String macAddress,
            String unpaired,
            String backgroundListener,
            String running,
            String notRunning,
            String bonjourService,
            String active,
            String notFound,
            String deviceId,
            String notGenerated,
            String lastReceived,
            String lastForwarded,
            String appVersion,
            String unknown,
            String transferMethod,
            String securityText,
            String noData
        ) {
            this.connectionStatus = connectionStatus;
            this.connected = connected;
            this.disconnected = disconnected;
            this.localIp = localIp;
            this.notObtained = notObtained;
            this.macAddress = macAddress;
            this.unpaired = unpaired;
            this.backgroundListener = backgroundListener;
            this.running = running;
            this.notRunning = notRunning;
            this.bonjourService = bonjourService;
            this.active = active;
            this.notFound = notFound;
            this.deviceId = deviceId;
            this.notGenerated = notGenerated;
            this.lastReceived = lastReceived;
            this.lastForwarded = lastForwarded;
            this.appVersion = appVersion;
            this.unknown = unknown;
            this.transferMethod = transferMethod;
            this.securityText = securityText;
            this.noData = noData;
        }
    }
}
