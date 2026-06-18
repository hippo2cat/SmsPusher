package com.hippo2cat.smspusher.ui;

import com.hippo2cat.smspusher.sms.MessageEvent;
import com.hippo2cat.smspusher.sms.PendingMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MessageListUiState {
    public final String title;
    public final String emptyTitle;
    public final String pendingListTitle;
    public final String primaryActionLabel;
    public final String viewMessagesLabel;
    public final String failureTitle;
    public final String failureDetail;
    public final List<Row> rows;
    public final boolean retryEnabled;
    public final boolean viewMessagesVisible;
    public final boolean failureVisible;

    private MessageListUiState(
        String title,
        String emptyTitle,
        String pendingListTitle,
        String primaryActionLabel,
        String viewMessagesLabel,
        String failureTitle,
        String failureDetail,
        List<Row> rows,
        boolean retryEnabled,
        boolean viewMessagesVisible,
        boolean failureVisible
    ) {
        this.title = title;
        this.emptyTitle = emptyTitle;
        this.pendingListTitle = pendingListTitle;
        this.primaryActionLabel = primaryActionLabel;
        this.viewMessagesLabel = viewMessagesLabel;
        this.failureTitle = failureTitle;
        this.failureDetail = failureDetail;
        this.rows = Collections.unmodifiableList(rows);
        this.retryEnabled = retryEnabled;
        this.viewMessagesVisible = viewMessagesVisible;
        this.failureVisible = failureVisible;
    }

    public static MessageListUiState from(List<PendingMessage> pendingMessages, boolean paired, Copy copy) {
        ArrayList<Row> rows = new ArrayList<>();
        if (pendingMessages != null) {
            for (PendingMessage message : pendingMessages) {
                if (message == null) continue;
                rows.add(new Row(
                    nonEmpty(message.messageId, ""),
                    nonEmpty(message.sender, copy.unknownSender),
                    nonEmpty(message.body, copy.emptyBody),
                    nonEmpty(message.receivedAt, ""),
                    message.status,
                    statusText(message.status, copy),
                    message.failureReason
                ));
            }
        }
        boolean hasPending = !rows.isEmpty();
        boolean hasFailure = false;
        String failureDetail = "";
        for (Row row : rows) {
            if (row.status != MessageEvent.Status.FAILED) continue;
            hasFailure = true;
            failureDetail = nonEmpty(row.failureReason, copy.defaultFailureDetail);
            break;
        }
        return new MessageListUiState(
            hasFailure ? copy.failedStatus : hasPending ? format(copy.pendingMessagesTitleTemplate, "count", String.valueOf(rows.size())) : copy.allForwarded,
            copy.allForwarded,
            format(copy.pendingListTitleTemplate, "count", String.valueOf(rows.size())),
            hasFailure ? copy.retryNow : copy.pushNow,
            copy.viewMessages,
            copy.errorDetails,
            failureDetail,
            rows,
            paired,
            hasPending && !hasFailure,
            hasFailure
        );
    }

    public static String statusText(MessageEvent.Status status, Copy copy) {
        if (status == MessageEvent.Status.FORWARDED) return copy.forwardedStatus;
        if (status == MessageEvent.Status.FAILED) return copy.failedStatus;
        return copy.pendingStatus;
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
        public final String failureReason;

        private Row(String messageId, String sender, String bodyPreview, String receivedAt, MessageEvent.Status status, String statusText, String failureReason) {
            this.messageId = messageId;
            this.sender = sender;
            this.bodyPreview = bodyPreview;
            this.receivedAt = receivedAt;
            this.status = status;
            this.statusText = statusText;
            this.failureReason = failureReason;
        }
    }

    public static final class Copy {
        public final String unknownSender;
        public final String emptyBody;
        public final String forwardedStatus;
        public final String failedStatus;
        public final String pendingStatus;
        public final String allForwarded;
        public final String pendingMessagesTitleTemplate;
        public final String pendingListTitleTemplate;
        public final String pushNow;
        public final String retryNow;
        public final String viewMessages;
        public final String errorDetails;
        public final String defaultFailureDetail;

        public Copy(
            String unknownSender,
            String emptyBody,
            String forwardedStatus,
            String failedStatus,
            String pendingStatus,
            String allForwarded,
            String pendingMessagesTitleTemplate,
            String pendingListTitleTemplate,
            String pushNow,
            String retryNow,
            String viewMessages,
            String errorDetails,
            String defaultFailureDetail
        ) {
            this.unknownSender = unknownSender;
            this.emptyBody = emptyBody;
            this.forwardedStatus = forwardedStatus;
            this.failedStatus = failedStatus;
            this.pendingStatus = pendingStatus;
            this.allForwarded = allForwarded;
            this.pendingMessagesTitleTemplate = pendingMessagesTitleTemplate;
            this.pendingListTitleTemplate = pendingListTitleTemplate;
            this.pushNow = pushNow;
            this.retryNow = retryNow;
            this.viewMessages = viewMessages;
            this.errorDetails = errorDetails;
            this.defaultFailureDetail = defaultFailureDetail;
        }
    }

    private static String format(String template, String name, String value) {
        return template == null ? "" : template.replace("{" + name + "}", value == null ? "" : value);
    }
}
