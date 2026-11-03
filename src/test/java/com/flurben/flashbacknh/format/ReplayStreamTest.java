package com.flurben.flashbacknh.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayStreamTest {

    @TempDir
    File tmp;

    private static List<ReplayStream.Record> read(ReplayStream s, int tick) throws IOException {
        List<ReplayStream.Record> out = new ArrayList<>();
        s.readTick(tick, out::add);
        return out;
    }

    @Test
    void roundTripTicksAndRecords() throws IOException {
        File f = new File(tmp, "stream.fbnh");
        try (RecordWriter w = new RecordWriter(f, false)) {
            w.writeRecord(ReplayFormat.REC_PACKET, new byte[] { 1, 2, 3 });
            w.writeRecord(ReplayFormat.REC_LOCAL_PLAYER, new byte[] { 0, 0, 0, 7 });
            w.writeTick();
            w.writeTick(); // empty tick
            byte[] big = new byte[200_000];
            for (int i = 0; i < big.length; i++) big[i] = (byte) i;
            w.writeRecord(ReplayFormat.REC_PACKET, big);
            w.writeTick();
            assertEquals(3, w.getTicks());
        }
        try (ReplayStream s = ReplayStream.open(f)) {
            assertEquals(3, s.getTickCount());
            List<ReplayStream.Record> t0 = read(s, 0);
            assertEquals(2, t0.size());
            assertArrayEquals(new byte[] { 1, 2, 3 }, t0.get(0).data);
            assertEquals(ReplayFormat.REC_LOCAL_PLAYER, t0.get(1).type);
            assertEquals(0, read(s, 1).size());
            List<ReplayStream.Record> t2 = read(s, 2);
            assertEquals(1, t2.size());
            assertEquals(200_000, t2.get(0).data.length);
            assertEquals((byte) 12345, t2.get(0).data[12345]);
            // random access back to the start
            assertEquals(2, read(s, 0).size());
        }
    }

    @Test
    void truncatedRecordingIsRecoverable() throws IOException {
        File f = new File(tmp, "stream.fbnh");
        try (RecordWriter w = new RecordWriter(f, false)) {
            for (int t = 0; t < 10; t++) {
                w.writeRecord(ReplayFormat.REC_PACKET, new byte[] { (byte) t, 9, 9, 9 });
                w.writeTick();
            }
            w.writeRecord(ReplayFormat.REC_PACKET, new byte[1000]);
        }
        // simulate a crash in the middle of the last record
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(raf.length() - 500);
        }
        try (ReplayStream s = ReplayStream.open(f)) {
            assertEquals(10, s.getTickCount());
            assertEquals(9, read(s, 9).get(0).data[0]);
        }
    }

    @Test
    void archiveWriteAndUpdate() throws IOException {
        File stream = new File(tmp, "stream.fbnh");
        try (RecordWriter w = new RecordWriter(stream, false)) {
            w.writeTick();
        }
        ReplayMeta meta = new ReplayMeta();
        meta.name = "Test Replay";
        meta.durationTicks = 1;
        meta.markers.add(new ReplayMeta.Marker(0, 0xFFFF0000, "start"));
        Map<String, Object> extra = new HashMap<>();
        extra.put(ReplayFormat.ENTRY_THUMBNAIL, new byte[] { 1, 2 });
        File zip = new File(tmp, "test.zip");
        ReplayArchive.write(zip, meta, stream, extra);

        ReplayMeta read = ReplayArchive.readMeta(zip);
        assertEquals("Test Replay", read.name);
        assertEquals(1, read.markers.size());
        assertEquals("start", read.markers.get(0).description);

        Map<String, byte[]> upd = new HashMap<>();
        upd.put(ReplayFormat.ENTRY_EDITOR, "{}".getBytes(StandardCharsets.UTF_8));
        ReplayArchive.update(zip, upd);
        assertNotNull(ReplayArchive.readEntry(zip, ReplayFormat.ENTRY_EDITOR));
        assertArrayEquals(new byte[] { 1, 2 }, ReplayArchive.readEntry(zip, ReplayFormat.ENTRY_THUMBNAIL));

        File extracted = new File(tmp, "out/stream.fbnh");
        ReplayArchive.extractEntry(zip, ReplayFormat.ENTRY_STREAM, extracted);
        assertArrayEquals(Files.readAllBytes(stream.toPath()), Files.readAllBytes(extracted.toPath()));
    }

    @Test
    void appendKeepsHeader() throws IOException {
        File f = new File(tmp, "s.fbnh");
        try (RecordWriter w = new RecordWriter(f, false)) {
            w.writeTick();
        }
        try (RecordWriter w = new RecordWriter(f, true)) {
            w.writeTick();
        }
        try (ReplayStream s = ReplayStream.open(f)) {
            assertEquals(2, s.getTickCount());
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] head = new byte[4];
            assertEquals(4, in.read(head));
            assertEquals(ReplayFormat.MAGIC, ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF));
        }
    }
}
