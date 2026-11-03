package com.flurben.flashbacknh.format;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Reading and writing of replay zip archives. */
public final class ReplayArchive {

    private ReplayArchive() {}

    /**
     * Writes a complete archive. {@code extraEntries} values may be byte arrays or Files.
     */
    public static void write(File target, ReplayMeta meta, File stream, Map<String, Object> extraEntries)
        throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            putBytes(
                zos,
                ReplayFormat.ENTRY_META,
                meta.toJson()
                    .getBytes(StandardCharsets.UTF_8));
            if (stream != null) putFile(zos, ReplayFormat.ENTRY_STREAM, stream);
            if (extraEntries != null) {
                for (Map.Entry<String, Object> e : extraEntries.entrySet()) {
                    if (e.getValue() instanceof byte[]) putBytes(zos, e.getKey(), (byte[]) e.getValue());
                    else if (e.getValue() instanceof File) putFile(zos, e.getKey(), (File) e.getValue());
                }
            }
        }
        replace(tmp, target);
    }

    /**
     * Rewrites an existing archive, replacing (or adding) the given entries and keeping everything else. A null value
     * removes the entry.
     */
    public static void update(File archive, Map<String, byte[]> replacements) throws IOException {
        File tmp = new File(archive.getParentFile(), archive.getName() + ".tmp");
        Map<String, byte[]> pending = new LinkedHashMap<>(replacements);
        try (ZipFile zip = new ZipFile(archive);
            ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (pending.containsKey(name)) {
                    byte[] data = pending.remove(name);
                    if (data != null) putBytes(zos, name, data);
                    continue;
                }
                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = zip.getInputStream(entry)) {
                    copy(in, zos);
                }
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : pending.entrySet()) {
                if (e.getValue() != null) putBytes(zos, e.getKey(), e.getValue());
            }
        }
        replace(tmp, archive);
    }

    public static ReplayMeta readMeta(File archive) throws IOException {
        byte[] data = readEntry(archive, ReplayFormat.ENTRY_META);
        if (data == null) throw new IOException("Missing " + ReplayFormat.ENTRY_META);
        return ReplayMeta.fromJson(new String(data, StandardCharsets.UTF_8));
    }

    public static byte[] readEntry(File archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                copy(in, bos);
                return bos.toByteArray();
            }
        }
    }

    public static boolean extractEntry(File archive, String name, File dest) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return false;
            dest.getParentFile()
                .mkdirs();
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), 1 << 16);
                OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 1 << 16)) {
                copy(in, out);
            }
            return true;
        }
    }

    private static void putBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static void putFile(ZipOutputStream zos, String name, File file) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
            copy(in, zos);
        }
        zos.closeEntry();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void replace(File tmp, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to replace " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Unable to rename " + tmp + " to " + target);
        }
    }
}
