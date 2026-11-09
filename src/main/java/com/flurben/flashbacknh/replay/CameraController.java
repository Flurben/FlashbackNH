package com.flurben.flashbacknh.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MathHelper;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.flurben.flashbacknh.Config;
import com.flurben.flashbacknh.gui.GuiReplayEditor;

/** Frame-rate independent free camera movement (WASD + jump/sneak, scroll wheel for speed). */
public final class CameraController {

    private double vx, vy, vz;
    private long lastNanos;
    private float speedMultiplier = 1.0f;
    public boolean lockX, lockY, lockZ;
    public boolean horizontalMovement = true;

    public float getSpeed() {
        return Config.cameraBaseSpeed * speedMultiplier;
    }

    /** Sets the flying speed in blocks per second. */
    public void setSpeed(float blocksPerSecond) {
        speedMultiplier = MathHelper.clamp_float(blocksPerSecond / Math.max(0.01f, Config.cameraBaseSpeed), 0.02f, 50f);
    }

    public void adjustSpeed(int wheelDelta) {
        if (wheelDelta == 0) return;
        float factor = wheelDelta > 0 ? 1.15f : 1 / 1.15f;
        speedMultiplier = MathHelper.clamp_float(speedMultiplier * factor, 0.02f, 50f);
    }

    public void reset() {
        vx = vy = vz = 0;
        lastNanos = 0;
    }

    /** True if keyboard movement should currently move the camera. */
    public static boolean isControlActive() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == null) return mc.inGameHasFocus;
        return mc.currentScreen instanceof GuiReplayEditor && ((GuiReplayEditor) mc.currentScreen).isCameraGrabbed();
    }

    public void update(CameraEntity camera) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        dt = Math.min(dt, 0.1);
        Minecraft mc = Minecraft.getMinecraft();
        GameSettings gs = mc.gameSettings;

        double fwd = 0, strafe = 0, up = 0;
        boolean boost = false;
        if (isControlActive()) {
            if (isDown(gs.keyBindForward)) fwd += 1;
            if (isDown(gs.keyBindBack)) fwd -= 1;
            if (isDown(gs.keyBindLeft)) strafe += 1;
            if (isDown(gs.keyBindRight)) strafe -= 1;
            if (isDown(gs.keyBindJump)) up += 1;
            if (isDown(gs.keyBindSneak)) up -= 1;
            boost = isDown(gs.keyBindSprint) || Keyboard.isKeyDown(Keyboard.KEY_LCONTROL);
        }

        double speed = getSpeed() * (boost ? 3.0 : 1.0);
        double yawRad = Math.toRadians(camera.rotationYaw);
        double pitchRad = Math.toRadians(camera.rotationPitch);
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double tx, ty, tz;
        if (horizontalMovement) {
            tx = (-sin * fwd + cos * strafe);
            tz = (cos * fwd + sin * strafe);
            ty = up;
        } else {
            double cp = Math.cos(pitchRad);
            tx = (-sin * cp * fwd + cos * strafe);
            tz = (cos * cp * fwd + sin * strafe);
            ty = -Math.sin(pitchRad) * fwd + up;
        }
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len > 1) {
            tx /= len;
            ty /= len;
            tz /= len;
        }
        tx *= speed;
        ty *= speed;
        tz *= speed;
        if (lockX) tx = 0;
        if (lockY) ty = 0;
        if (lockZ) tz = 0;

        if (Config.cameraMomentum) {
            double k = 1 - Math.exp(-dt * 8.0);
            vx += (tx - vx) * k;
            vy += (ty - vy) * k;
            vz += (tz - vz) * k;
        } else {
            vx = tx;
            vy = ty;
            vz = tz;
        }
        if (Math.abs(vx) < 1e-4 && Math.abs(vy) < 1e-4 && Math.abs(vz) < 1e-4) {
            vx = vy = vz = 0;
            camera.snapInterpolation();
            camera.remember();
            return;
        }
        camera.setPosition(camera.posX + vx * dt, camera.posY + vy * dt, camera.posZ + vz * dt);
        camera.snapInterpolation();
        camera.remember();
    }

    public static boolean isDown(KeyBinding kb) {
        int code = kb.getKeyCode();
        if (code == 0) return false;
        if (code < 0) return Mouse.isButtonDown(code + 100);
        return code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }
}
