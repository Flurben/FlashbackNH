package com.flurben.flashbacknh.dev;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.flurben.flashbacknh.FlashbackNH;
import com.flurben.flashbacknh.editor.Interpolation;
import com.flurben.flashbacknh.editor.TrackType;
import com.flurben.flashbacknh.editor.Visuals;
import com.flurben.flashbacknh.export.ChunkWaiter;
import com.flurben.flashbacknh.export.ExportSettings;
import com.flurben.flashbacknh.export.Exporter;
import com.flurben.flashbacknh.export.FFmpegEncoder;
import com.flurben.flashbacknh.recording.Recorder;
import com.flurben.flashbacknh.recording.RecordingManager;
import com.flurben.flashbacknh.replay.CameraEntity;
import com.flurben.flashbacknh.replay.ReplayHandler;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

/**
 * Test harness for driving the game without a human, used to verify the mod end to end. Only active when the game is
 * started with {@code -Dflashbacknh.dev=true}. Commands are read line by line from
 * {@code <game dir>/fbnh-dev/commands.txt} (append to it while the game runs) and results go to {@code results.log} in
 * the same folder; screenshots are written as PNG next to them.
 */
public final class DevDriver {

    public static boolean enabled() {
        return Boolean.getBoolean("flashbacknh.dev");
    }

    private final Minecraft mc = Minecraft.getMinecraft();
    private final File dir = new File(mc.mcDataDir, "fbnh-dev");
    private final File commands = new File(dir, "commands.txt");
    private final File results = new File(dir, "results.log");
    private final Deque<String> pending = new ArrayDeque<>();
    private long readOffset;
    private long nextPoll;
    private long waitUntil;
    private String waitCondition;
    private long waitDeadline;
    private long lastFrame;

    public DevDriver() {
        dir.mkdirs();
        // commands left over from an earlier run must not execute again
        readOffset = commands.exists() ? commands.length() : 0;
        log("driver ready");
    }

    /**
     * Writes the state of every tile entity without a description packet, sorted, to compare a replay with the game.
     */
    private void dumpTiles(String name, int blockX, int blockZ) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int cx = (blockX >> 4) - 40; cx <= (blockX >> 4) + 40; cx++) {
            for (int cz = (blockZ >> 4) - 40; cz <= (blockZ >> 4) + 40; cz++) {
                net.minecraft.world.chunk.Chunk chunk = mc.theWorld.getChunkProvider()
                    .provideChunk(cx, cz);
                if (chunk == null || chunk instanceof net.minecraft.world.chunk.EmptyChunk) continue;
                for (Object o : new ArrayList<Object>(chunk.chunkTileEntityMap.values())) {
                    net.minecraft.tileentity.TileEntity te = (net.minecraft.tileentity.TileEntity) o;
                    if (te.isInvalid() || te.getDescriptionPacket() != null) continue;
                    net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
                    te.writeToNBT(tag);
                    lines.add(te.xCoord + " " + te.yCoord + " " + te.zCoord + " " + tag);
                }
            }
        }
        java.util.Collections.sort(lines);
        try (PrintWriter w = new PrintWriter(new File(dir, "tiles_" + name + ".txt"))) {
            for (String line : lines) w.println(line);
        }
        log("tiledump " + name + " " + lines.size());
    }

    private void log(String line) {
        FlashbackNH.LOG.info("[dev] {}", line);
        try (PrintWriter w = new PrintWriter(new FileWriter(results, true))) {
            w.println(System.currentTimeMillis() + " " + line);
        } catch (IOException ignored) {}
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long frameNow = System.nanoTime();
        if (lastFrame != 0 && mc.theWorld != null && frameNow - lastFrame > 100_000_000L) {
            log("long frame " + (frameNow - lastFrame) / 1_000_000L + " ms");
        }
        lastFrame = frameNow;
        try {
            poll();
            step();
        } catch (Throwable t) {
            FlashbackNH.LOG.error("[dev] command failed", t);
            log("ERROR " + t);
        }
    }

    @SubscribeEvent
    public void onGuiOpen(net.minecraftforge.client.event.GuiOpenEvent event) {
        FlashbackNH.LOG.info(
            "[dev] gui -> {}",
            event.gui == null ? "null"
                : event.gui.getClass()
                    .getName());
    }

    private void poll() throws IOException {
        long now = System.currentTimeMillis();
        if (now < nextPoll) return;
        nextPoll = now + 100;
        if (!commands.exists() || commands.length() <= readOffset) return;
        try (RandomAccessFile f = new RandomAccessFile(commands, "r")) {
            f.seek(readOffset);
            String line;
            while ((line = f.readLine()) != null) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) pending.add(s);
            }
            readOffset = f.getFilePointer();
        }
    }

    private void step() throws Exception {
        long now = System.currentTimeMillis();
        if (now < waitUntil) return;
        if (waitCondition != null) {
            if (!check(waitCondition)) {
                if (now < waitDeadline) return;
                log("TIMEOUT waiting for " + waitCondition + " | " + state());
            } else {
                log("reached " + waitCondition);
            }
            waitCondition = null;
        }
        // one command per frame, except pure waits
        String cmd = pending.poll();
        if (cmd == null) return;
        log("> " + cmd);
        run(cmd);
    }

    private boolean check(String c) {
        switch (c) {
            case "world":
                return mc.theWorld != null && mc.thePlayer != null && mc.currentScreen == null;
            case "noworld":
                return mc.theWorld == null;
            case "replay":
                return ReplayHandler.isReplaying() && mc.theWorld != null;
            case "noreplay":
                return !ReplayHandler.isReplaying();
            case "noexport":
                return !Exporter.isExporting();
            case "chunks":
                return ChunkWaiter.isComplete();
            case "finishedsaving":
                return true;
            default:
                if (c.startsWith("gui:")) {
                    String name = c.substring(4);
                    GuiScreen s = mc.currentScreen;
                    return s == null ? name.equals("none")
                        : s.getClass()
                            .getSimpleName()
                            .equals(name);
                }
                if (c.startsWith("tick:")) {
                    ReplayHandler h = ReplayHandler.get();
                    return h != null && h.getCurrentTick() >= Integer.parseInt(c.substring(5));
                }
                if (c.startsWith("file:")) {
                    return new File(mc.mcDataDir, c.substring(5)).exists();
                }
                throw new IllegalArgumentException("Unknown condition " + c);
        }
    }

    private void run(String line) throws Exception {
        String[] a = line.split("\\s+");
        ReplayHandler h = ReplayHandler.get();
        switch (a[0]) {
            case "wait":
                waitUntil = System.currentTimeMillis() + Long.parseLong(a[1]);
                break;
            case "until":
                waitCondition = a[1];
                waitDeadline = System.currentTimeMillis() + (a.length > 2 ? Long.parseLong(a[2]) : 120000);
                break;
            case "state":
                log(state());
                break;
            case "shot":
                screenshot(a[1]);
                break;
            case "world": {
                // world <folder> [flat|default] [creative|survival]
                WorldType type = a.length > 2 && a[2].equals("default") ? WorldType.DEFAULT : WorldType.FLAT;
                WorldSettings.GameType mode = a.length > 3 && a[3].equals("survival") ? WorldSettings.GameType.SURVIVAL
                    : WorldSettings.GameType.CREATIVE;
                WorldSettings settings = new WorldSettings(12345L, mode, true, false, type);
                settings.func_82750_a("");
                settings.enableCommands();
                mc.launchIntegratedServer(a[1], a[1], settings);
                break;
            }
            case "leave":
                if (mc.theWorld != null) mc.theWorld.sendQuittingDisconnectingPacket();
                mc.loadWorld(null);
                mc.displayGuiScreen(new GuiMainMenu());
                break;
            case "connect":
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .setupServerList();
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .connectToServer(
                        new net.minecraft.client.gui.GuiMainMenu(),
                        new net.minecraft.client.multiplayer.ServerData("test", a[1] + ":" + a[2]));
                break;
            case "chat":
                mc.thePlayer.sendChatMessage(line.substring(5));
                break;
            case "look":
                mc.thePlayer.rotationYaw = Float.parseFloat(a[1]);
                mc.thePlayer.rotationPitch = Float.parseFloat(a[2]);
                break;
            case "key":
                key(a[1], a[2].equals("down"));
                break;
            case "rec":
                record(a);
                break;
            case "gui":
                gui(a, line);
                break;
            case "open":
                ReplayHandler.open(replayFile(a[1]));
                break;
            case "tiledump":
                dumpTiles(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                break;
            case "listreplays":
                log(
                    Arrays.toString(
                        RecordingManager.replaysDir()
                            .list()));
                break;
            case "exit":
                h.exit();
                break;
            case "play":
                h.setPaused(false);
                break;
            case "pause":
                h.setPaused(true);
                break;
            case "speed":
                h.setSpeed(Float.parseFloat(a[1]));
                break;
            case "seek":
                h.seek(Integer.parseInt(a[1]));
                break;
            case "camera":
                h.getCamera()
                    .setCameraPosition(
                        Double.parseDouble(a[1]),
                        Double.parseDouble(a[2]),
                        Double.parseDouble(a[3]),
                        Float.parseFloat(a[4]),
                        Float.parseFloat(a[5]));
                break;
            case "spectate":
                spectate(h, a);
                break;
            case "kf":
                keyframe(h, a);
                break;
            case "visual":
                visual(h, a);
                break;
            case "export":
                export(h, a);
                break;
            case "pausemenu":
                mc.displayInGameMenu();
                break;
            case "dim": {
                // moves the player to another dimension through the integrated server
                final int target = Integer.parseInt(a[1]);
                final String name = mc.thePlayer.getCommandSenderName();
                com.flurben.flashbacknh.util.ServerTasks.schedule(() -> {
                    net.minecraft.entity.player.EntityPlayerMP player = net.minecraft.server.MinecraftServer.getServer()
                        .getConfigurationManager()
                        .func_152612_a(name);
                    if (player != null) player.travelToDimension(target);
                });
                break;
            }
            case "hidegui":
                mc.gameSettings.hideGUI = a[1].equals("on");
                break;
            case "resize":
                ((com.flurben.flashbacknh.mixins.early.AccessorMinecraft) mc)
                    .flashbacknh$resize(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                break;
            case "shaderreload":
                try {
                    Class.forName("net.coderbot.iris.Iris")
                        .getMethod("reload")
                        .invoke(null);
                } catch (ReflectiveOperationException e) {
                    log("shader reload failed: " + e);
                }
                break;
            case "halt":
                log("halting the JVM to simulate a crash");
                Runtime.getRuntime()
                    .halt(137);
                break;
            case "quit":
                mc.shutdown();
                break;
            default:
                log("unknown command: " + line);
        }
    }

    private File replayFile(String name) {
        File[] files = RecordingManager.replaysDir()
            .listFiles();
        if (files == null || files.length == 0) throw new IllegalStateException("No replays");
        if (name.equals("newest")) {
            File best = files[0];
            for (File f : files) if (f.lastModified() > best.lastModified()) best = f;
            return best;
        }
        for (File f : files) if (f.getName()
            .contains(name)) return f;
        throw new IllegalArgumentException("No replay matching " + name);
    }

    private void key(String name, boolean down) {
        KeyBinding binding;
        switch (name) {
            case "forward":
                binding = mc.gameSettings.keyBindForward;
                break;
            case "back":
                binding = mc.gameSettings.keyBindBack;
                break;
            case "left":
                binding = mc.gameSettings.keyBindLeft;
                break;
            case "right":
                binding = mc.gameSettings.keyBindRight;
                break;
            case "jump":
                binding = mc.gameSettings.keyBindJump;
                break;
            case "sneak":
                binding = mc.gameSettings.keyBindSneak;
                break;
            case "sprint":
                binding = mc.gameSettings.keyBindSprint;
                break;
            case "attack":
                binding = mc.gameSettings.keyBindAttack;
                break;
            case "use":
                binding = mc.gameSettings.keyBindUseItem;
                break;
            default:
                throw new IllegalArgumentException(name);
        }
        KeyBinding.setKeyBindState(binding.getKeyCode(), down);
        if (down) KeyBinding.onTick(binding.getKeyCode());
    }

    private void record(String[] a) {
        RecordingManager rm = RecordingManager.INSTANCE;
        switch (a[1]) {
            case "start":
                rm.start();
                break;
            case "pause":
                rm.togglePause();
                break;
            case "finish":
                rm.finish();
                break;
            case "cancel":
                rm.cancel();
                break;
            case "marker":
                rm.addMarker(Integer.parseInt(a[2]));
                break;
            default:
                throw new IllegalArgumentException(a[1]);
        }
    }

    private static final String[] N_MOUSE_CLICKED = { "mouseClicked", "func_73864_a" };
    private static final String[] N_MOUSE_UP = { "mouseMovedOrUp", "func_146286_b" };
    private static final String[] N_MOUSE_DRAG = { "mouseClickMove", "func_146273_a" };
    private static final String[] N_KEY_TYPED = { "keyTyped", "func_73869_a" };

    private void gui(String[] a, String line) throws Exception {
        GuiScreen s = mc.currentScreen;
        if (s == null) throw new IllegalStateException("No screen open");
        switch (a[1]) {
            case "name":
                log(
                    s.getClass()
                        .getName());
                break;
            case "buttons": {
                List<String> names = new ArrayList<>();
                for (Object o : screenButtons(s)) names.add(((GuiButton) o).displayString);
                log(names.toString());
                break;
            }
            case "press": {
                // press a vanilla style button by its label, through the normal click path
                String label = line.substring(line.indexOf("press") + 6);
                for (Object o : screenButtons(s)) {
                    GuiButton b = (GuiButton) o;
                    if (b.displayString.equals(label)) {
                        click(s, b.xPosition + b.width / 2, b.yPosition + 10, 0);
                        return;
                    }
                }
                log("no button: " + label);
                break;
            }
            case "click":
                click(s, Integer.parseInt(a[2]), Integer.parseInt(a[3]), a.length > 4 ? Integer.parseInt(a[4]) : 0);
                break;
            case "drag": {
                // drag x y for a button that is already held
                Method m = ReflectionHelper
                    .findMethod(GuiScreen.class, s, N_MOUSE_DRAG, int.class, int.class, int.class, long.class);
                m.invoke(s, Integer.parseInt(a[2]), Integer.parseInt(a[3]), 0, 100L);
                break;
            }
            case "down": {
                Method m = ReflectionHelper
                    .findMethod(GuiScreen.class, s, N_MOUSE_CLICKED, int.class, int.class, int.class);
                m.invoke(s, Integer.parseInt(a[2]), Integer.parseInt(a[3]), 0);
                break;
            }
            case "up": {
                Method m = ReflectionHelper.findMethod(GuiScreen.class, s, N_MOUSE_UP, int.class, int.class, int.class);
                m.invoke(s, Integer.parseInt(a[2]), Integer.parseInt(a[3]), 0);
                break;
            }
            case "key": {
                // key <LWJGL key name> [char]
                int code = Keyboard.getKeyIndex(a[2]);
                char c = a.length > 3 ? a[3].charAt(0) : 0;
                Method m = ReflectionHelper.findMethod(GuiScreen.class, s, N_KEY_TYPED, char.class, int.class);
                m.invoke(s, c, code);
                break;
            }
            case "close":
                mc.displayGuiScreen(null);
                break;
            case "invoke": {
                // no-argument method of the current screen, for shortcuts that read the real modifier keys
                Method m = s.getClass()
                    .getDeclaredMethod(a[2]);
                m.setAccessible(true);
                m.invoke(s);
                break;
            }
            default:
                throw new IllegalArgumentException(a[1]);
        }
    }

    private void click(GuiScreen s, int x, int y, int button) throws Exception {
        Method down = ReflectionHelper.findMethod(GuiScreen.class, s, N_MOUSE_CLICKED, int.class, int.class, int.class);
        down.invoke(s, x, y, button);
        Method up = ReflectionHelper.findMethod(GuiScreen.class, s, N_MOUSE_UP, int.class, int.class, int.class);
        // the screen may have changed
        if (mc.currentScreen == s) up.invoke(s, x, y, button);
    }

    private static List<?> screenButtons(GuiScreen s) {
        return ReflectionHelper.getPrivateValue(GuiScreen.class, s, "buttonList", "field_146292_n");
    }

    private void spectate(ReplayHandler h, String[] a) {
        if (a[1].equals("off")) {
            h.stopSpectating();
            return;
        }
        for (Object o : mc.theWorld.loadedEntityList) {
            Entity e = (Entity) o;
            if (e instanceof EntityPlayer && !(e instanceof CameraEntity)) {
                h.spectate(e);
                log("spectating " + e.getCommandSenderName());
                return;
            }
        }
        log("no player to spectate");
    }

    private void keyframe(ReplayHandler h, String[] a) {
        // kf camera <tick> x y z yaw pitch roll [interp] | kf fov <tick> v | kf time <tick> v | kf speed <tick> v
        // | kf shake <tick> v | kf clear
        if (a[1].equals("clear")) {
            for (TrackType t : TrackType.values()) h.editor.track(t).keyframes.clear();
            return;
        }
        TrackType type = TrackType.valueOf(a[1].toUpperCase());
        int tick = Integer.parseInt(a[2]);
        int n = type.channels.length;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = Double.parseDouble(a[3 + i]);
        Interpolation interp = a.length > 3 + n ? Interpolation.valueOf(a[3 + n]) : Interpolation.SMOOTH;
        h.editor.track(type)
            .put(tick, values, interp);
        h.markEditorDirty();
    }

    private void visual(ReplayHandler h, String[] a) throws Exception {
        Visuals v = h.editor.visuals;
        java.lang.reflect.Field f = Visuals.class.getField(a[1]);
        Class<?> t = f.getType();
        if (t == boolean.class) f.setBoolean(v, Boolean.parseBoolean(a[2]));
        else if (t == float.class) f.setFloat(v, Float.parseFloat(a[2]));
        else if (t == int.class) f.setInt(v, Integer.parseInt(a[2]));
        else f.set(v, Visuals.Weather.valueOf(a[2]));
    }

    private void export(ReplayHandler h, String[] a) throws Exception {
        // export <MP4|PNG|...> <name> <start> <end> <w> <h> <fps>
        ExportSettings s = new ExportSettings();
        s.format = FFmpegEncoder.Format.parse(a[1]);
        s.startTick = Integer.parseInt(a[3]);
        s.endTick = Integer.parseInt(a[4]);
        s.width = Integer.parseInt(a[5]);
        s.height = Integer.parseInt(a[6]);
        s.fps = Integer.parseInt(a[7]);
        s.preset = "veryfast";
        File outDir = new File(RecordingManager.rootDir(), "exports");
        outDir.mkdirs();
        s.output = new File(outDir, a[2] + (s.format == FFmpegEncoder.Format.PNG ? "" : ".mp4"));
        Exporter.start(h, s);
    }

    private String state() {
        StringBuilder b = new StringBuilder();
        b.append("screen=")
            .append(
                mc.currentScreen == null ? "none"
                    : mc.currentScreen.getClass()
                        .getSimpleName());
        b.append(" world=")
            .append(mc.theWorld != null);
        b.append(" fps=")
            .append(mc.debug.split(" ")[0]);
        if (mc.thePlayer != null) {
            b.append(
                String.format(
                    " player=%s %.2f %.2f %.2f yaw=%.1f pitch=%.1f",
                    mc.thePlayer.getClass()
                        .getSimpleName(),
                    mc.thePlayer.posX,
                    mc.thePlayer.posY,
                    mc.thePlayer.posZ,
                    mc.thePlayer.rotationYaw,
                    mc.thePlayer.rotationPitch));
        }
        if (mc.theWorld != null) {
            b.append(" entities=")
                .append(mc.theWorld.loadedEntityList.size());
            b.append(" tiles=")
                .append(mc.theWorld.loadedTileEntityList.size());
            b.append(" time=")
                .append(mc.theWorld.getWorldTime());
        }
        Recorder r = RecordingManager.INSTANCE.getCurrent();
        if (r != null) {
            b.append(" rec[ticks=")
                .append(r.getTicks())
                .append(" paused=")
                .append(r.isPaused())
                .append("]");
        }
        ReplayHandler h = ReplayHandler.get();
        if (h != null) {
            b.append(
                String.format(
                    " replay[tick=%d/%d paused=%s speed=%.2f spectating=%s]",
                    h.getCurrentTick(),
                    h.getTotalTicks(),
                    h.isPaused(),
                    h.getSpeed(),
                    h.isSpectating()));
        }
        Exporter e = Exporter.get();
        if (e != null) {
            b.append(" export[")
                .append(e.getFramesWritten())
                .append("/")
                .append(e.getTotalFrames())
                .append("]");
        }
        if (mc.theWorld != null) b.append(
            " renders=[" + mc.renderGlobal.getDebugInfoRenders()
                + "] view="
                + (mc.renderViewEntity == null ? null
                    : mc.renderViewEntity.getClass()
                        .getSimpleName()));
        if (h != null) {
            b.append(" kf=[");
            for (TrackType t : TrackType.values()) {
                StringBuilder ticks = new StringBuilder();
                for (com.flurben.flashbacknh.editor.Keyframe k : h.editor.track(t).keyframes) ticks.append(k.tick)
                    .append(',');
                if (ticks.length() > 0) b.append(t)
                    .append(':')
                    .append(ticks)
                    .append(' ');
            }
            b.append(']');
        }
        b.append(" chunksComplete=")
            .append(mc.theWorld != null && ChunkWaiter.isComplete());
        return b.toString();
    }

    private void screenshot(String name) throws IOException {
        int w = mc.displayWidth, h = mc.displayHeight;
        if (OpenGlHelper.isFramebufferEnabled()) mc.getFramebuffer()
            .bindFramebuffer(false);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int base = (h - 1 - y) * w * 4;
            for (int x = 0; x < w; x++) {
                int i = base + x * 4;
                row[x] = ((buf.get(i) & 0xFF) << 16) | ((buf.get(i + 1) & 0xFF) << 8) | (buf.get(i + 2) & 0xFF);
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        ImageIO.write(img, "png", new File(dir, name + ".png"));
        log("screenshot " + name + " " + w + "x" + h);
    }
}
