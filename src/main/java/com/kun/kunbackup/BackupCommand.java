package com.kun.kunbackup;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class BackupCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("backup")
                // 对所有玩家可见，具体权限在下面用配置名单判断，方便给出明确提示
                .requires(source -> source.getEntity() instanceof ServerPlayer
                        || source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> {
                    if (!requirePermission(ctx.getSource())) {
                        return 0;
                    }
                    MinecraftServer server = ctx.getSource().getServer();
                    ctx.getSource().sendSuccess(() -> Component.literal("[KunBackup] 开始备份 world 文件夹..."), true);
                    boolean ok = BackupManager.backup(server, ctx.getSource());
                    if (!ok) {
                        ctx.getSource().sendFailure(Component.literal("[KunBackup] 已有一个备份正在进行，请稍后再试。"));
                    }
                    return 1;
                })
                .then(Commands.literal("list").executes(ctx -> {
                    if (!requirePermission(ctx.getSource())) {
                        return 0;
                    }
                    List<String> names = BackupManager.listBackups(ctx.getSource().getServer());
                    if (names.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.literal("[KunBackup] 暂无备份文件。"), false);
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.literal("[KunBackup] 现有备份（新→旧）:\n" + String.join("\n", names)), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    if (!ctx.getSource().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                        ctx.getSource().sendFailure(Component.literal("[KunBackup] 只有 OP 可以重载配置。"));
                        return 0;
                    }
                    BackupManager.reloadConfig(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("[KunBackup] 配置已重新加载。"), false);
                    return 1;
                }))
        );
    }

    /** 权限检查：OP / 控制台放行，普通玩家需在 config 的 allowed.players 名单里 */
    private static boolean requirePermission(CommandSourceStack source) {
        if (BackupManager.isAllowed(source)) {
            return true;
        }
        source.sendFailure(Component.literal(
                "[KunBackup] 你没有备份权限。请让管理员把你的游戏名加入 config/kunbackup.properties 的 allowed.players。"));
        return false;
    }
}
