package com.flurben.flashbacknh.util;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.flurben.flashbacknh.FlashbackNH;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Runs small tasks on the (integrated) server thread at the start of the next server tick. */
public final class ServerTasks {

    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();

    public static void schedule(Runnable task) {
        QUEUE.add(task);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Runnable r;
        while ((r = QUEUE.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                FlashbackNH.LOG.error("Server task failed", t);
            }
        }
    }
}
