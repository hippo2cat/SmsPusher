package com.hippo2cat.smspusher.ble;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BlePeripheralControllerTest {
    @Test
    public void missingPermissionsDoNotOpenGattOrAdvertise() {
        FakeGateway gateway = new FakeGateway();
        gateway.permissions = false;
        BlePeripheralController controller = new BlePeripheralController(gateway, new FakeAckSink());

        BlePeripheralState state = controller.start();

        assertEquals(BlePeripheralState.PERMISSION_REQUIRED, state);
        assertTrue(gateway.operations.isEmpty());
    }

    @Test
    public void unavailableAdapterDoesNotOpenGattOrAdvertise() {
        FakeGateway gateway = new FakeGateway();
        gateway.adapterAvailable = false;
        BlePeripheralController controller = new BlePeripheralController(gateway, new FakeAckSink());

        BlePeripheralState state = controller.start();

        assertEquals(BlePeripheralState.ADAPTER_UNAVAILABLE, state);
        assertTrue(gateway.operations.isEmpty());
    }

    @Test
    public void startOpensSmsPusherGattProfileBeforeAdvertising() {
        FakeGateway gateway = new FakeGateway();
        BlePeripheralController controller = new BlePeripheralController(gateway, new FakeAckSink());

        BlePeripheralState state = controller.start();

        assertEquals(BlePeripheralState.ADVERTISING, state);
        assertEquals(BlePeripheralState.ADVERTISING, controller.state());
        assertEquals("openGatt", gateway.operations.get(0));
        assertEquals("advertise", gateway.operations.get(1));
        assertEquals(BleConstants.SERVICE_UUID, gateway.openedProfile.serviceUuid());
        assertEquals(BleConstants.MESSAGE_CHARACTERISTIC_UUID, gateway.openedProfile.messageCharacteristicUuid());
        assertEquals(BleConstants.ACK_CHARACTERISTIC_UUID, gateway.openedProfile.ackCharacteristicUuid());
        assertEquals(BleConstants.METADATA_CHARACTERISTIC_UUID, gateway.openedProfile.metadataCharacteristicUuid());
    }

    @Test
    public void publishMessageNotifiesAllEncodedChunks() throws Exception {
        FakeGateway gateway = new FakeGateway();
        BlePeripheralController controller = new BlePeripheralController(gateway, new FakeAckSink());
        controller.start();
        byte[] payload = "{\"messageId\":\"msg_1\",\"body\":\"hello world\"}".getBytes(StandardCharsets.UTF_8);

        controller.publishMessage("msg_1", payload, 16);

        assertTrue(gateway.notifiedFrames.size() > 1);
        for (int index = 0; index < gateway.notifiedFrames.size(); index += 1) {
            BleFrame frame = gateway.notifiedFrames.get(index);
            assertEquals("msg_1", frame.messageId());
            assertEquals(index, frame.chunkIndex());
            assertEquals(gateway.notifiedFrames.size(), frame.chunkCount());
            assertTrue(frame.payload().length <= 16);
        }
    }

    @Test
    public void ackWriteDecodesMessageIdAndCallsSink() throws Exception {
        FakeAckSink ackSink = new FakeAckSink();
        BlePeripheralController controller = new BlePeripheralController(new FakeGateway(), ackSink);

        controller.handleAckWrite(BleAckCodec.encode(new BleAck(BleFrameCodec.VERSION, "msg_42")));

        assertEquals(1, ackSink.acceptedMessageIds.size());
        assertEquals("msg_42", ackSink.acceptedMessageIds.get(0));
    }

    @Test
    public void stopStopsAdvertisingAndClosesGatt() {
        FakeGateway gateway = new FakeGateway();
        BlePeripheralController controller = new BlePeripheralController(gateway, new FakeAckSink());
        controller.start();

        BlePeripheralState state = controller.stop();

        assertEquals(BlePeripheralState.STOPPED, state);
        assertEquals(BlePeripheralState.STOPPED, controller.state());
        assertEquals("stopAdvertising", gateway.operations.get(2));
        assertEquals("closeGatt", gateway.operations.get(3));
    }

    private static final class FakeGateway implements BlePeripheralController.Gateway {
        boolean permissions = true;
        boolean adapterAvailable = true;
        BleGattProfile openedProfile;
        final List<String> operations = new ArrayList<>();
        final List<BleFrame> notifiedFrames = new ArrayList<>();

        @Override
        public boolean hasRequiredPermissions() {
            return permissions;
        }

        @Override
        public boolean isAdapterAvailable() {
            return adapterAvailable;
        }

        @Override
        public void openGattServer(BleGattProfile profile) {
            openedProfile = profile;
            operations.add("openGatt");
        }

        @Override
        public void startAdvertising(BleGattProfile profile) {
            operations.add("advertise");
        }

        @Override
        public void notifyMessage(BleFrame frame) {
            notifiedFrames.add(frame);
        }

        @Override
        public void stopAdvertising() {
            operations.add("stopAdvertising");
        }

        @Override
        public void closeGattServer() {
            operations.add("closeGatt");
        }
    }

    private static final class FakeAckSink implements BlePeripheralController.AckSink {
        final List<String> acceptedMessageIds = new ArrayList<>();

        @Override
        public void accept(String messageId) {
            acceptedMessageIds.add(messageId);
        }
    }
}
