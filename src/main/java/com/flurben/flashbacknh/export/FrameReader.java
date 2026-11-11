package com.flurben.flashbacknh.export;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLContext;

import com.flurben.flashbacknh.FlashbackNH;

/**
 * Reads exported frames back from the framebuffer that is bound. Frames come out in the order they were read, each
 * exactly once, also with {@link Pbo}, which hands a frame on a few frames later.
 */
abstract class FrameReader {

    /** Receives a frame: RGB, 3 bytes a pixel, bottom-up rows. The buffer is only valid during the call. */
    interface Sink {

        void frame(ByteBuffer rgb, int width, int height) throws IOException;
    }

    /** Reads the frame just rendered. Can hand an earlier frame to the sink. */
    abstract void read(int width, int height, Sink sink) throws IOException;

    /** Hands every frame still held to the sink, in order. */
    abstract void flush(Sink sink) throws IOException;

    /** Frees GPU memory. Frames still held are dropped. */
    abstract void dispose();

    /** The non-blocking reader when the driver has pixel buffer objects, else the blocking one. */
    static FrameReader create(int width, int height) {
        try {
            ContextCapabilities caps = GLContext.getCapabilities();
            if (caps.OpenGL21 || caps.GL_ARB_pixel_buffer_object) {
                Pbo pbo = new Pbo(width, height);
                FlashbackNH.LOG.info("Export reads frames through pixel buffer objects");
                return pbo;
            }
        } catch (Throwable t) {
            FlashbackNH.LOG.warn("Pixel buffer objects are not usable, the export reads frames directly", t);
        }
        return new Direct(width, height);
    }

    /** glReadPixels into memory, which waits for the GPU to finish the frame. */
    static final class Direct extends FrameReader {

        private final ByteBuffer pixels;

        Direct(int width, int height) {
            pixels = BufferUtils.createByteBuffer(width * height * FrameEncoder.BYTES_PER_PIXEL);
        }

        @Override
        void read(int width, int height, Sink sink) throws IOException {
            pixels.clear();
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixels);
            pixels.rewind();
            sink.frame(pixels, width, height);
        }

        @Override
        void flush(Sink sink) {}

        @Override
        void dispose() {}
    }

    /**
     * glReadPixels into a ring of pixel buffer objects: the copy runs on the GPU while the next frames render, and a
     * frame is mapped and handed on {@link #DEPTH} - 1 frames later, when its copy is long done.
     */
    static final class Pbo extends FrameReader {

        static final int DEPTH = 3;

        private final int[] buffers = new int[DEPTH];
        private final int size;
        private int next;
        /** Buffers read into and not handed on yet, oldest first. */
        private final ArrayDeque<Integer> pending = new ArrayDeque<>();
        private int width, height;

        Pbo(int width, int height) {
            this.size = width * height * FrameEncoder.BYTES_PER_PIXEL;
            for (int i = 0; i < DEPTH; i++) {
                buffers[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }

        @Override
        void read(int width, int height, Sink sink) throws IOException {
            if (width * height * FrameEncoder.BYTES_PER_PIXEL != size)
                throw new IOException("Frame size changed during the export");
            this.width = width;
            this.height = height;
            // the ring is full: the oldest frame goes on first, so its buffer can be reused
            if (pending.size() == DEPTH - 1) handOn(sink);
            int buffer = buffers[next];
            next = (next + 1) % DEPTH;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            pending.addLast(buffer);
        }

        private void handOn(Sink sink) throws IOException {
            int buffer = pending.removeFirst();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
            try {
                ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, size, null);
                if (mapped == null) throw new IOException("Unable to map a frame buffer (OpenGL error)");
                mapped.rewind();
                sink.frame(mapped, width, height);
            } finally {
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            }
        }

        @Override
        void flush(Sink sink) throws IOException {
            while (!pending.isEmpty()) handOn(sink);
        }

        @Override
        void dispose() {
            pending.clear();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int b : buffers) if (b != 0) GL15.glDeleteBuffers(b);
        }
    }
}
