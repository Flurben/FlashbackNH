package com.flurben.flashbacknh.export;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Receives exported frames (RGB, bottom-up rows as read from OpenGL). */
public interface FrameEncoder extends AutoCloseable {

    /**
     * Frames are RGB without alpha: a quarter less to copy and to pipe to ffmpeg, which also converts RGB to YUV
     * faster than RGBA.
     */
    int BYTES_PER_PIXEL = 3;

    /** Called on the client thread. The buffer is only valid during the call. */
    void encode(ByteBuffer rgb, int width, int height) throws IOException;

    /** Finishes the output. Blocks until everything is written. */
    @Override
    void close() throws IOException;

    /** Aborts and deletes partial output. */
    void abort();

    /** A line on where the encoder's time went, for the log, or null. */
    default String stats() {
        return null;
    }

    /** Non-null if the encoder failed asynchronously. */
    String getError();
}
