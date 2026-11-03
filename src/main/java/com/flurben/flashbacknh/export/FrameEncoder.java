package com.flurben.flashbacknh.export;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Receives exported frames (RGBA, bottom-up rows as read from OpenGL). */
public interface FrameEncoder extends AutoCloseable {

    /** Called on the client thread. The buffer is only valid during the call. */
    void encode(ByteBuffer rgba, int width, int height) throws IOException;

    /** Finishes the output. Blocks until everything is written. */
    @Override
    void close() throws IOException;

    /** Aborts and deletes partial output. */
    void abort();

    /** Non-null if the encoder failed asynchronously. */
    String getError();
}
