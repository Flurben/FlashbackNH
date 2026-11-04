package com.flurben.flashbacknh.format;

/**
 * Constants describing the FlashbackNH replay format.
 *
 * <p>
 * A replay is a zip archive with these entries:
 * <ul>
 * <li>{@code metadata.json} - {@link ReplayMeta}</li>
 * <li>{@code stream.fbnh} - the tick stream (see below)</li>
 * <li>{@code registry.nbt} - the FML block/item id map that was active while recording</li>
 * <li>{@code editor.json} - optional editor state (keyframes, in/out points, visuals)</li>
 * <li>{@code thumbnail.png} - optional</li>
 * </ul>
 *
 * <p>
 * The stream starts with {@link #MAGIC} and {@link #VERSION} (two big-endian ints) followed by records. Each record is
 * {@code [type: byte][length: varint][payload: length bytes]}. Records are grouped into ticks: every record before the
 * first {@link #REC_TICK} belongs to tick 0, records after the n-th {@code REC_TICK} belong to tick n. Unknown record
 * types are skipped, and a truncated final record (crash while recording) is ignored, which makes recovery trivial.
 */
public final class ReplayFormat {

    public static final int MAGIC = 0x46424E48; // "FBNH"
    public static final int VERSION = 2;

    public static final String ENTRY_META = "metadata.json";
    public static final String ENTRY_STREAM = "stream.fbnh";
    public static final String ENTRY_REGISTRY = "registry.nbt";
    public static final String ENTRY_EDITOR = "editor.json";
    public static final String ENTRY_THUMBNAIL = "thumbnail.png";

    /** End of the current tick. Empty payload. */
    public static final byte REC_TICK = 0;
    /** A clientbound PLAY packet: varint packet id followed by the packet's wire payload. */
    public static final byte REC_PACKET = 1;
    /**
     * High precision movement for the recording player: int entityId, double x, y, z, float yaw, pitch, headYaw,
     * boolean onGround.
     */
    public static final byte REC_LOCAL_MOVE = 2;
    /**
     * Client-side tile entity state for tiles without a description packet: int x, y, z, NBT compound. Only found in
     * version 1 streams, later ones use {@link #REC_TILE_BATCH}.
     */
    public static final byte REC_TILE_NBT = 3;
    /** Clear all entities and chunks (used before a fresh snapshot, e.g. after unpausing a recording). */
    public static final byte REC_CLEAR_WORLD = 4;
    /** Identifies the recording player's entity: int entityId. */
    public static final byte REC_LOCAL_PLAYER = 5;
    /** First-person HUD state for the recording player: see {@code HotbarState}. */
    public static final byte REC_HOTBAR = 6;
    /** Many tile entities restored from NBT at once: see {@link TileBatch}. Since version 2. */
    public static final byte REC_TILE_BATCH = 7;
    /**
     * The same record as one earlier in the stream, which saves storing a chunk again when it comes back unchanged:
     * long position of that record in the stream. Since version 2.
     */
    public static final byte REC_REPEAT = 8;

    private ReplayFormat() {}
}
