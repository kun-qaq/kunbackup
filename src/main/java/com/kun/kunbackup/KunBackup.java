package com.kun.kunbackup;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;

public class KunBackup implements ModInitializer {

    public static final String MODID = "kunbackup";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(BackupManager::startScheduler);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BackupManager.stopScheduler());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BackupCommand.register(dispatcher));

        LOGGER.info("[KunBackup] 已加载。/backup 手动备份，每天按 config/kunbackup.properties 设定时间自动备份。");
    }
}
