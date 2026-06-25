package com.hippo2cat.smspusher.ui;

public final class DeviceTopologySizing {
    private static final int PAGE_HORIZONTAL_PADDING_DP = 16 * 2;
    private static final int DEVICE_TAB_HORIZONTAL_PADDING_DP = 12 * 2;
    private static final int STATUS_CARD_HORIZONTAL_PADDING_DP = 18 * 2;
    private static final int ORIGINAL_CONNECTOR_WIDTH_DP = 82;
    private static final int MIN_CONNECTOR_WIDTH_DP = 50;
    private static final int ORIGINAL_LAPTOP_FRAME_WIDTH_DP = 102;
    private static final int ORIGINAL_LAPTOP_FRAME_HEIGHT_DP = 78;
    private static final int ORIGINAL_LAPTOP_BASE_WIDTH_DP = ORIGINAL_LAPTOP_FRAME_WIDTH_DP + 12;
    private static final int ORIGINAL_LAPTOP_ICON_SIZE_DP = 34;
    private static final int MIN_LAPTOP_FRAME_WIDTH_DP = 72;
    private static final int MIN_LAPTOP_ICON_SIZE_DP = 28;

    private DeviceTopologySizing() {}

    public static Layout forScreenWidthDp(int screenWidthDp) {
        int topologyWidthDp = Math.max(0, screenWidthDp
            - PAGE_HORIZONTAL_PADDING_DP
            - DEVICE_TAB_HORIZONTAL_PADDING_DP
            - STATUS_CARD_HORIZONTAL_PADDING_DP);
        int connectorWidthDp = Math.min(
            ORIGINAL_CONNECTOR_WIDTH_DP,
            Math.max(MIN_CONNECTOR_WIDTH_DP, topologyWidthDp - (ORIGINAL_LAPTOP_BASE_WIDTH_DP * 2))
        );
        int nodeWidthDp = Math.max(0, (topologyWidthDp - connectorWidthDp) / 2);
        int laptopBaseWidthDp = Math.min(ORIGINAL_LAPTOP_BASE_WIDTH_DP, nodeWidthDp);
        int laptopFrameWidthDp = Math.max(MIN_LAPTOP_FRAME_WIDTH_DP, laptopBaseWidthDp - 12);
        if (laptopFrameWidthDp > laptopBaseWidthDp) laptopFrameWidthDp = laptopBaseWidthDp;
        int laptopFrameHeightDp = Math.max(
            56,
            Math.round(laptopFrameWidthDp * (ORIGINAL_LAPTOP_FRAME_HEIGHT_DP / (float) ORIGINAL_LAPTOP_FRAME_WIDTH_DP))
        );
        int laptopIconSizeDp = Math.max(
            MIN_LAPTOP_ICON_SIZE_DP,
            Math.min(ORIGINAL_LAPTOP_ICON_SIZE_DP, Math.round(laptopFrameWidthDp / 3f))
        );
        return new Layout(
            connectorWidthDp,
            nodeWidthDp,
            laptopFrameWidthDp,
            laptopFrameHeightDp,
            laptopBaseWidthDp,
            laptopIconSizeDp
        );
    }

    public static final class Layout {
        public final int connectorWidthDp;
        public final int nodeWidthDp;
        public final int laptopFrameWidthDp;
        public final int laptopFrameHeightDp;
        public final int laptopBaseWidthDp;
        public final int laptopIconSizeDp;

        private Layout(
            int connectorWidthDp,
            int nodeWidthDp,
            int laptopFrameWidthDp,
            int laptopFrameHeightDp,
            int laptopBaseWidthDp,
            int laptopIconSizeDp
        ) {
            this.connectorWidthDp = connectorWidthDp;
            this.nodeWidthDp = nodeWidthDp;
            this.laptopFrameWidthDp = laptopFrameWidthDp;
            this.laptopFrameHeightDp = laptopFrameHeightDp;
            this.laptopBaseWidthDp = laptopBaseWidthDp;
            this.laptopIconSizeDp = laptopIconSizeDp;
        }
    }
}
