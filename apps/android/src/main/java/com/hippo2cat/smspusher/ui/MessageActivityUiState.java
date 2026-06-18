package com.hippo2cat.smspusher.ui;

import com.hippo2cat.smspusher.sms.MessageEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MessageActivityUiState {
    public final String title;
    public final String emptyTitle;
    public final List<Row> rows;

    private MessageActivityUiState(String title, String emptyTitle, List<Row> rows) {
        this.title = title;
        this.emptyTitle = emptyTitle;
        this.rows = Collections.unmodifiableList(rows);
    }

    public static MessageActivityUiState from(List<MessageEvent> events, Copy copy, MessageListUiState.Copy messageCopy) {
        ArrayList<Row> rows = new ArrayList<>();
        if (events != null) {
            for (MessageEvent event : events) {
                if (event == null) continue;
                rows.add(new Row(
                    nonEmpty(event.messageId, ""),
                    nonEmpty(event.sender, messageCopy.unknownSender),
                    nonEmpty(event.body, messageCopy.emptyBody),
                    nonEmpty(event.receivedAt, ""),
                    event.status,
                    MessageListUiState.statusText(event.status, messageCopy),
                    event.destination,
                    event.failureReason
                ));
            }
        }
        return new MessageActivityUiState(copy.title, copy.emptyTitle, rows);
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    public static final class Row {
        public final String messageId;
        public final String sender;
        public final String bodyPreview;
        public final String receivedAt;
        public final MessageEvent.Status status;
        public final String statusText;
        public final String destination;
        public final String failureReason;

        private Row(
            String messageId,
            String sender,
            String bodyPreview,
            String receivedAt,
            MessageEvent.Status status,
            String statusText,
            String destination,
            String failureReason
        ) {
            this.messageId = messageId;
            this.sender = sender;
            this.bodyPreview = bodyPreview;
            this.receivedAt = receivedAt;
            this.status = status;
            this.statusText = statusText;
            this.destination = destination;
            this.failureReason = failureReason;
        }
    }

    public static final class Copy {
        public final String title;
        public final String emptyTitle;

        public Copy(String title, String emptyTitle) {
            this.title = title;
            this.emptyTitle = emptyTitle;
        }
    }
}
