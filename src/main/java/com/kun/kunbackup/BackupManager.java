package com.kun.kunbackup;

import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** 每天几点自动备份（本地时间，24小时制），可在 config/kunbackup.properties 里改 backup.hour */
    private static int backupHour = 4;
    /** 保留最近几个 zip，相当于保留最近 N 天（backup.keep） */
    private static int keepCount = 3;
    /** 允许使用 /backup 的玩家名单（小写比较），来自 config 的 allowed.players，OP 始终允许 */
    private static final Set<String> allowedPlayers = new HashSet<>();

    private static final AtomicBoolean backingUp = new AtomicBoolean(false);
    private static ScheduledExecutorService scheduler;

    // ---------------------------------------------------------------- config

    public static void initConfig(MinecraftServer server) {
        Path configDir = getServerRoot(server).resolve("config");
        Path configFile = configDir.resolve("kunbackup.properties");
        try {
            Files.createDirectories(configDir);
            if (Files.exists(configFile)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(configFile)) {
                    props.load(in);
                }
                backupHour = Integer.parseInt(props.getProperty("backup.hour", String.valueOf(backupHour)));
                keepCount = Integer.parseInt(props.getProperty("backup.keep", String.valueOf(keepCount)));
                allowedPlayers.clear();
                for (String name : props.getProperty("allowed.players", "").split("[,;\\s]+")) {
                    if (!name.isBlank()) allowedPlayers.add(name.trim().toLowerCase(Locale.ROOT));
                }
            } else {
                Properties props = new Properties();
                props.setProperty("backup.hour", String.valueOf(backupHour));
                props.setProperty("backup.keep", String.valueOf(keepCount));
                props.setProperty("allowed.players", "");
                try (OutputStream out = Files.newOutputStream(configFile)) {
                    props.store(out, "KunBackup config:\n"
                            + " backup.hour     = daily auto backup hour, 0-23\n"
                            + " backup.keep     = how many daily zip files to keep\n"
                            + " allowed.players = comma separated player names allowed to run /backup (OP always allowed)");
                }
            }
        } catch (Exception e) {
            KunBackup.LOGGER.warn("[KunBackup] 读取配置失败，使用默认值 (hour={}, keep={}): {}", backupHour, keepCount, e.toString());
        }
        if (backupHour < 0 || backupHour > 23) backupHour = 4;
        if (keepCount < 1) keepCount = 1;
        KunBackup.LOGGER.info("[KunBackup] 配置: 每日 {} 点自动备份，保留最近 {} 个 zip。配置文件: {}", backupHour, keepCount, configFile);
        KunBackup.LOGGER.info("[KunBackup] 可使用 /backup 的玩家名单: {}", allowedPlayers.isEmpty() ? "(空，仅 OP)" : allowedPlayers);
    }

    /** /backup reload：重新读取配置文件（时间、保留数量、玩家名单） */
    public static void reloadConfig(MinecraftServer server) {
        initConfig(server);
    }

    /**
     * 判断某个命令来源是否有权使用备份功能：
     * OP / 控制台（拥有 COMMANDS_GAMEMASTER 权限）始终允许，其余玩家必须在 allowed.players 名单里。
     */
    public static boolean isAllowed(CommandSourceStack source) {
        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer player) {
            return allowedPlayers.contains(player.getName().getString().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    // ------------------------------------------------------------- scheduler

    public static synchronized void startScheduler(MinecraftServer server) {
        initConfig(server);
        // 不自动创建备份目录（按需自己在服务器根目录建好 backups），这里只检查并打印绝对路径
        Path backupDir = getBackupDir(server);
        if (Files.isDirectory(backupDir)) {
            KunBackup.LOGGER.info("[KunBackup] 备份保存目录: {}", backupDir);
        } else {
            KunBackup.LOGGER.error("[KunBackup] 备份目录不存在，请先创建: {}", backupDir);
        }
        stopScheduler();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KunBackup-Scheduler");
            t.setDaemon(true);
            return t;
        });
        long initialDelay = Duration.between(LocalDateTime.now(), nextRunTime()).toMinutes();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                backup(server, null);
            } catch (Exception e) {
                KunBackup.LOGGER.error("[KunBackup] 定时备份失败", e);
            }
        }, initialDelay, 24L * 60L, TimeUnit.MINUTES);
        KunBackup.LOGGER.info("[KunBackup] 定时任务已启动：每天 {} 点自动备份，下次执行约 {} 分钟后。", backupHour, initialDelay);
    }

    public static synchronized void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private static LocalDateTime nextRunTime() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(LocalTime.of(backupHour, 0));
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return next;
    }

    // ---------------------------------------------------------------- backup

    /** 手动 /backup 触发的入口。返回 false 表示已有备份在跑。 */
    public static boolean startManualBackup(MinecraftServer server) {
        return backup(server, null);
    }

    /** 带 command source 的备份入口，完成后会把结果回给执行者。返回 false 表示已有备份在跑。 */
    public static boolean backup(MinecraftServer server, CommandSourceStack source) {
        if (!backingUp.compareAndSet(false, true)) {
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                doBackup(server, source);
            } catch (Exception e) {
                KunBackup.LOGGER.error("[KunBackup] 备份失败", e);
                sayServer(server, "Backup failed! Check server log.");
                if (source != null) {
                    server.execute(() -> source.sendFailure(Component.literal("[KunBackup] 备份失败: " + e.getMessage())));
                }
            } finally {
                backingUp.set(false);
            }
        }, "KunBackup-Worker");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static void doBackup(MinecraftServer server, CommandSourceStack source) throws Exception {
        Path worldDir = getWorldDir(server);
        Path backupDir = getBackupDir(server);
        if (!Files.isDirectory(backupDir)) {
            throw new IOException("备份目录不存在，请先创建: " + backupDir);
        }

        // 1. 先在主线程保存所有区块，保证 zip 里的数据是完整的
        CountDownLatch saveLatch = new CountDownLatch(1);
        server.execute(() -> {
            try {
                server.saveEverything(true, true, true);
                KunBackup.LOGGER.info("[KunBackup] 世界已保存，开始打包。");
            } catch (Exception e) {
                KunBackup.LOGGER.error("[KunBackup] 保存世界时出错", e);
            } finally {
                saveLatch.countDown();
            }
        });
        saveLatch.await(2, TimeUnit.MINUTES);

        // 2. 打包 world 文件夹为 zip
        String zipName = "backup-" + LocalDateTime.now().format(TS) + ".zip";
        Path target = backupDir.resolve(zipName);
        Path tmp = backupDir.resolve(zipName + ".tmp");
        zipDirectory(worldDir, tmp);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // 3. 轮转：只保留最近 keepCount 个 zip（即覆盖/删除 3 天前的）
        int deleted = rotate(backupDir);

        long sizeMb = Files.size(target) / 1024 / 1024;
        KunBackup.LOGGER.info("[KunBackup] 备份完成: {} ({} MB)", target.toAbsolutePath(), sizeMb);
        // 聊天框广播，玩家看到的是 [Server] Backup complete! (xx MB)
        sayServer(server, "Backup complete! (" + sizeMb + " MB)");
        if (source != null) {
            server.execute(() -> source.sendSuccess(() -> Component.literal(
                    "[KunBackup] 备份完成: " + target.toAbsolutePath() + " (" + sizeMb + " MB)"), false));
        }
    }

    /** 直接向所有玩家广播消息（say 指令内部用的同一接口），不受 send_command_feedback gamerule 影响 */
    private static void sayServer(MinecraftServer server, String text) {
        try {
            server.execute(() -> server.getPlayerList()
                    .broadcastSystemMessage(Component.literal(text), false));
        } catch (Exception e) {
            KunBackup.LOGGER.warn("[KunBackup] 广播失败: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ util

    private static void zipDirectory(Path dir, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String entryName = dir.relativize(p).toString().replace('\\', '/');
                    // 万一 world 里还有旧的 backups 目录，跳过它，避免把 zip 自己打包进去越滚越大
                    if (entryName.equals("backups") || entryName.startsWith("backups/")) {
                        return;
                    }
                    try {
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        // 单个文件读不了（比如正在写入的日志）就跳过，不中断整个备份
                        KunBackup.LOGGER.warn("[KunBackup] 跳过文件 {}: {}", entryName, e.toString());
                    }
                });
            }
        }
    }

    private static int rotate(Path backupDir) {
        List<Path> zips = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("backup-") && n.endsWith(".zip");
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .forEach(zips::add);
        } catch (IOException e) {
            KunBackup.LOGGER.warn("[KunBackup] 轮转时无法列出备份目录: {}", e.toString());
            return 0;
        }
        int deleted = 0;
        for (int i = keepCount; i < zips.size(); i++) {
            try {
                Files.deleteIfExists(zips.get(i));
                deleted++;
                KunBackup.LOGGER.info("[KunBackup] 已删除过期备份 {}", zips.get(i).getFileName());
            } catch (IOException e) {
                KunBackup.LOGGER.warn("[KunBackup] 删除旧备份失败 {}: {}", zips.get(i), e.toString());
            }
        }
        return deleted;
    }

    public static List<String> listBackups(MinecraftServer server) {
        Path backupDir = getBackupDir(server);
        List<String> names = new ArrayList<>();
        names.add("备份目录: " + backupDir);
        if (!Files.isDirectory(backupDir)) return names;
        try (Stream<Path> stream = Files.list(backupDir)) {
            stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("backup-") && n.endsWith(".zip");
                    })
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .forEach(p -> {
                        try {
                            names.add(p.getFileName() + " (" + (Files.size(p) / 1024 / 1024) + " MB)");
                        } catch (IOException e) {
                            names.add(p.getFileName().toString());
                        }
                    });
        } catch (IOException ignored) {
        }
        return names;
    }

    private static Path getWorldDir(MinecraftServer server) {
        // 注意：getWorldPath(ROOT) 可能返回带 "." 或多余分隔符的路径，
        // 必须先 toAbsolutePath().normalize()，否则下面取父目录会取错（会把 world 当成服务器根目录）
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    private static Path getServerRoot(MinecraftServer server) {
        // world 文件夹的上一级就是服务器根目录（面板里的 /home/container）
        Path parent = getWorldDir(server).getParent();
        return parent != null ? parent : Path.of(".");
    }

    public static Path getBackupDir(MinecraftServer server) {
        return getServerRoot(server).resolve("backups");
    }
}
