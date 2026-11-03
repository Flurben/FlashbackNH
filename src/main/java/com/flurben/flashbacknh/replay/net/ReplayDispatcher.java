package com.flurben.flashbacknh.replay.net;

import net.minecraft.network.NetworkManager;

import com.flurben.flashbacknh.FlashbackNH;

import cpw.mods.fml.common.network.handshake.NetworkDispatcher;

/**
 * FML dispatcher attached to the replay connection. It is never inserted into the pipeline (there is no handshake);
 * it exists so code calling {@code NetworkDispatcher.get(manager)} works and so that a mod throwing while handling a
 * replayed packet does not tear down the replay connection.
 */
public final class ReplayDispatcher extends NetworkDispatcher {

    public ReplayDispatcher(NetworkManager manager) {
        super(manager);
    }

    @Override
    public void rejectHandshake(String result) {
        FlashbackNH.LOG.warn("A mod rejected a replayed packet: {}", result);
    }
}
