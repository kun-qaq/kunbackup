package com.kun.kunbackup;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;

import java.util.List;

public class BackupCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("backup")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(ctx -> {
                    MinecraftServer server = ctx.getSource().getServer();
                    ctx.getSource().sendSuccess(() -> Component.literal("[KunBackup] 开始备份 world 文件夹..."), true);
                    boolean ok = BackupManager.backup(server, ctx.getSource());
                    if (!ok) {
                        ctx.getSource().sendFailure(Component.literal("[KunBackup] 已有一个备份正在进行，请稍后再试。"));
                    }
                    return 1;
                })
                .then(Commands.literal("list").executes(ctx -> {
                    List<String> names = BackupManager.listBackups(ctx.getSource().getServer());
                    if (names.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> Component.literal("[KunBackup] 暂无备份文件。"), false);
                    } else {
                        ctx.getSource().sendSuccess(() -> Component.literal("[KunBackup] 现有备份（新→旧）:\n" + String.join("\n", names)), false);
                    }
                    return 1;
                }))
        );
    }
}
