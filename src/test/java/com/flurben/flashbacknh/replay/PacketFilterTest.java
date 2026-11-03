package com.flurben.flashbacknh.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.flurben.flashbacknh.replay.net.PacketFilter;

class PacketFilterTest {

    private static final class Capture implements PacketFilter.Listener {

        int joinId = -1;
        double[] pos;

        @Override
        public void onJoinGame(int originalEntityId) {
            joinId = originalEntityId;
        }

        @Override
        public void onPlayerPosLook(double x, double y, double z, float yaw, float pitch) {
            pos = new double[] { x, y, z, yaw, pitch };
        }
    }

    private static int readInt(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    @Test
    void joinGameBecomesCamera() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(0x01);
        out.writeInt(1234);
        out.writeByte(0 | 8); // survival, hardcore
        out.writeByte(0);
        out.writeByte(2);
        out.writeByte(20);
        out.writeByte(7);
        out.writeBytes("default");
        Capture c = new Capture();
        byte[] res = PacketFilter.filter(bos.toByteArray(), c);
        assertNotNull(res);
        assertEquals(1234, c.joinId);
        assertEquals(ReplayConstants.CAMERA_ENTITY_ID, readInt(res, 1));
        assertEquals(1 | 8, res[5] & 0xFF); // creative, hardcore kept
    }

    @Test
    void playerOnlyPacketsAreDropped() {
        for (int id : new int[] { 0x00, 0x06, 0x09, 0x1F, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x36, 0x39, 0x40 }) {
            assertNull(PacketFilter.filter(new byte[] { (byte) id, 0, 0, 0, 0 }, null), "packet 0x" + Integer.toHexString(id));
        }
    }

    @Test
    void worldPacketsPassThroughUnchanged() {
        byte[] blockChange = { 0x23, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };
        assertSame(blockChange, PacketFilter.filter(blockChange, null));
    }

    @Test
    void playerPositionIsReportedAndDropped() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(0x08);
        out.writeDouble(1.5);
        out.writeDouble(65.62);
        out.writeDouble(-3);
        out.writeFloat(90);
        out.writeFloat(10);
        out.writeBoolean(true);
        Capture c = new Capture();
        assertNull(PacketFilter.filter(bos.toByteArray(), c));
        assertEquals(1.5, c.pos[0], 1e-9);
        assertEquals(65.62, c.pos[1], 1e-9);
        assertEquals(90, c.pos[3], 1e-6);
    }

    @Test
    void gameStateKeepsOnlyWeather() {
        assertNotNull(PacketFilter.filter(new byte[] { 0x2B, 1, 0, 0, 0, 0 }, null));
        assertNotNull(PacketFilter.filter(new byte[] { 0x2B, 7, 0, 0, 0, 0 }, null));
        assertNull(PacketFilter.filter(new byte[] { 0x2B, 3, 0, 0, 0, 0 }, null));
        assertNull(PacketFilter.filter(new byte[] { 0x2B, 4, 0, 0, 0, 0 }, null));
    }

    @Test
    void bookAndTradePayloadsAreDropped() {
        assertNull(PacketFilter.filter(payload("MC|BOpen"), null));
        assertNull(PacketFilter.filter(payload("MC|TrList"), null));
        assertNotNull(PacketFilter.filter(payload("gregtech"), null));
    }

    private static byte[] payload(String channel) {
        byte[] name = channel.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + name.length + 2];
        out[0] = 0x3F;
        out[1] = (byte) name.length;
        System.arraycopy(name, 0, out, 2, name.length);
        return out;
    }
}
