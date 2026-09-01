package com.mggx.ubuntu;

import android.content.Context;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class UbuntuInstaller {
    private static final String ROOT_NAME = "ubuntu-fs";
    private static final String STAGING_NAME = "ubuntu-fs.installing";
    private static final String BACKUP_NAME = "ubuntu-fs.backup";
    private static final String ASSET_NAME = "ubuntu-base-arm64.rootfs";
    private static final String MARKER = ".mggx-rootfs-v3";

    private UbuntuInstaller() {}

    public interface Listener {
        void onStage(String stage, int percent);
    }

    public static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), ROOT_NAME);
    }

    private static File stagingDir(Context context) {
        return new File(context.getFilesDir(), STAGING_NAME);
    }

    private static File backupDir(Context context) {
        return new File(context.getFilesDir(), BACKUP_NAME);
    }

    public static boolean isInstalled(Context context) {
        return isValidRoot(rootfsDir(context));
    }

    public static void recoverIfNeeded(Context context) {
        File root = rootfsDir(context);
        File staging = stagingDir(context);
        File backup = backupDir(context);

        try {
            if (isValidRoot(root)) {
                deleteRecursive(staging);
                deleteRecursive(backup);
                return;
            }

            if (isValidRoot(backup)) {
                deleteRecursive(root);
                moveDirectory(backup, root);
            }

            deleteRecursive(staging);
            if (!isValidRoot(root)) deleteRecursive(root);
            if (backup.exists() && !isValidRoot(backup)) deleteRecursive(backup);
        } catch (Throwable ignored) {
            // A later install attempt performs the same cleanup and will surface a useful error.
        }
    }

    public static synchronized void install(Context context, Listener listener) throws Exception {
        recoverIfNeeded(context);
        if (isInstalled(context)) return;

        File root = rootfsDir(context);
        File staging = stagingDir(context);
        File backup = backupDir(context);

        deleteRecursive(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IllegalStateException("No se pudo crear el directorio temporal del rootfs");
        }

        try {
            listener.onStage("Abriendo Ubuntu incluido en el APK…", -1);
            try (InputStream raw = context.getAssets().open(ASSET_NAME);
                 BufferedInputStream buffered = new BufferedInputStream(raw, 128 * 1024);
                 GzipCompressorInputStream gzip = new GzipCompressorInputStream(buffered);
                 TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
                extractTar(tar, staging, listener);
            }

            listener.onStage("Configurando Ubuntu…", -1);
            configureRootfs(staging);

            listener.onStage("Validando Ubuntu…", -1);
            validateRoot(staging);
            writeText(new File(staging, MARKER), "MGGX Ubuntu Mobile 0.1.2\n");

            listener.onStage("Activando Ubuntu…", -1);
            deleteRecursive(backup);
            if (root.exists()) moveDirectory(root, backup);

            try {
                moveDirectory(staging, root);
                validateRoot(root);
                if (!new File(root, MARKER).isFile()) {
                    throw new IllegalStateException("El marcador de instalación no quedó activo");
                }
                deleteRecursive(backup);
            } catch (Throwable activationError) {
                deleteRecursive(root);
                if (backup.exists()) {
                    try { moveDirectory(backup, root); } catch (Throwable ignored) {}
                }
                if (activationError instanceof Exception) throw (Exception) activationError;
                throw new RuntimeException(activationError);
            }

            listener.onStage("Ubuntu listo", 100);
        } catch (Throwable error) {
            deleteRecursive(staging);
            if (!isValidRoot(root) && isValidRoot(backup)) {
                try {
                    deleteRecursive(root);
                    moveDirectory(backup, root);
                } catch (Throwable ignored) {}
            }
            if (error instanceof Exception) throw (Exception) error;
            throw new RuntimeException(error);
        }
    }

    private static void extractTar(TarArchiveInputStream tar, File root, Listener listener) throws Exception {
        List<String[]> hardLinks = new ArrayList<>();
        long entries = 0;
        TarArchiveEntry entry;

        while ((entry = tar.getNextTarEntry()) != null) {
            String name = normalizeEntryName(entry.getName());
            if (name.isEmpty()) continue;
            File target = safeTarget(root, name);

            if (entry.isDirectory()) {
                if (existsNoFollow(target) && !isDirectoryNoFollow(target)) deleteRecursive(target);
                if (!target.mkdirs() && !target.isDirectory()) {
                    throw new IllegalStateException("No se pudo crear " + name);
                }
                chmod(target, entry.getMode());
            } else if (entry.isSymbolicLink()) {
                File parent = target.getParentFile();
                ensureDirectory(parent, "directorio padre de " + name);
                deleteRecursive(target);
                Os.symlink(entry.getLinkName(), target.getAbsolutePath());
            } else if (entry.isLink()) {
                hardLinks.add(new String[]{normalizeEntryName(entry.getLinkName()), name});
            } else if (entry.isFile()) {
                File parent = target.getParentFile();
                ensureDirectory(parent, "directorio padre de " + name);
                deleteRecursive(target);
                try (FileOutputStream fos = new FileOutputStream(target, false);
                     BufferedOutputStream out = new BufferedOutputStream(fos, 128 * 1024)) {
                    byte[] buffer = new byte[128 * 1024];
                    int n;
                    while ((n = tar.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                chmod(target, entry.getMode());
            }

            entries++;
            if ((entries % 500) == 0) {
                listener.onStage("Extrayendo Ubuntu… " + entries + " entradas", -1);
            }
        }

        materializeHardLinks(root, hardLinks, listener);
    }

    private static void materializeHardLinks(File root, List<String[]> links, Listener listener) throws Exception {
        List<String[]> pending = new ArrayList<>(links);
        int pass = 0;

        while (!pending.isEmpty() && pass <= links.size() + 1) {
            boolean progressed = false;
            List<String[]> next = new ArrayList<>();

            for (String[] link : pending) {
                File source = safeTarget(root, link[0]);
                File target = safeTarget(root, link[1]);

                if (!existsNoFollow(source)) {
                    next.add(link);
                    continue;
                }

                ensureDirectory(target.getParentFile(), "directorio padre de " + link[1]);
                deleteRecursive(target);

                Path sourcePath = source.toPath();
                if (Files.isSymbolicLink(sourcePath)) {
                    Os.symlink(Files.readSymbolicLink(sourcePath).toString(), target.getAbsolutePath());
                } else if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    if (!target.mkdirs() && !target.isDirectory()) {
                        throw new IllegalStateException("No se pudo materializar directorio hard-link " + link[1]);
                    }
                } else if (Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(sourcePath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    try { chmod(target, Os.stat(source.getAbsolutePath()).st_mode); } catch (Throwable ignored) {}
                } else {
                    next.add(link);
                    continue;
                }
                progressed = true;
            }

            pending = next;
            pass++;
            if (!progressed && !pending.isEmpty()) break;
            if ((pass % 4) == 0) listener.onStage("Resolviendo enlaces de Ubuntu…", -1);
        }

        if (!pending.isEmpty()) {
            throw new IllegalStateException("Quedaron " + pending.size() + " hard links sin resolver");
        }
    }

    private static void configureRootfs(File root) throws Exception {
        ensureDirectory(new File(root, "root"), "/root");
        File tmp = new File(root, "tmp");
        ensureDirectory(tmp, "/tmp");
        try { Os.chmod(tmp.getAbsolutePath(), 01777); } catch (Throwable ignored) {}

        File etc = new File(root, "etc");
        ensureDirectory(etc, "/etc");

        File resolv = new File(etc, "resolv.conf");
        if (!existsNoFollow(resolv) || Files.isRegularFile(resolv.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursive(resolv);
            writeText(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        }

        File hosts = new File(etc, "hosts");
        if (!hosts.exists()) writeText(hosts, "127.0.0.1 localhost\n::1 localhost\n");
    }

    private static boolean isValidRoot(File root) {
        if (root == null || !root.isDirectory()) return false;
        if (!new File(root, MARKER).isFile()) return false;
        try {
            validateRoot(root);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void validateRoot(File root) {
        requireUsable(root, "bin/bash");
        requireUsable(root, "usr/bin/env");
        requireUsable(root, "usr/bin/apt");
        requireUsable(root, "etc/passwd");
        requireUsable(root, "etc/os-release");
    }

    private static void requireUsable(File root, String path) {
        File file = new File(root, path);
        if (!file.exists() || file.length() == 0) {
            throw new IllegalStateException("Rootfs incompleto: falta " + path);
        }
    }

    public static void removeInstallation(Context context) {
        deleteRecursive(stagingDir(context));
        deleteRecursive(backupDir(context));
        deleteRecursive(rootfsDir(context));
    }

    public static void deleteRecursive(File file) {
        if (file == null) return;
        try {
            Path path = file.toPath();
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;

            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path);
                return;
            }

            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
            Files.deleteIfExists(path);
        } catch (Throwable ignored) {
            // Best-effort cleanup; callers validate the resulting state.
        }
    }

    private static void moveDirectory(File from, File to) throws Exception {
        if (!from.exists()) return;
        deleteRecursive(to);
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable atomicFailure) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File safeTarget(File root, String name) throws Exception {
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path target = rootPath.resolve(name).normalize();
        if (!target.startsWith(rootPath)) {
            throw new SecurityException("Ruta fuera del rootfs: " + name);
        }
        return target.toFile();
    }

    private static String normalizeEntryName(String value) {
        if (value == null) return "";
        String name = value.replace('\\', '/');
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        return name;
    }

    private static boolean existsNoFollow(File file) {
        return file != null && Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isDirectoryNoFollow(File file) {
        return file != null && Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static void ensureDirectory(File dir, String description) {
        if (dir == null) return;
        if (existsNoFollow(dir) && !isDirectoryNoFollow(dir)) deleteRecursive(dir);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("No se pudo crear " + description);
        }
    }

    private static void chmod(File file, int mode) {
        try {
            if (file != null && !Files.isSymbolicLink(file.toPath())) {
                Os.chmod(file.getAbsolutePath(), mode & 07777);
            }
        } catch (Throwable ignored) {}
    }

    private static void writeText(File file, String text) throws Exception {
        ensureDirectory(file.getParentFile(), "directorio padre de " + file.getName());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}
