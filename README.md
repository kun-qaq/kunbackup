# KunBackup

轻量级 Minecraft 26.2 (Fabric) 服务端备份 mod。

A lightweight server-side backup mod for Minecraft 26.2 (Fabric).

## 功能 / Features

- `/backup` — 手动把 `world` 文件夹打包成 zip 存到服务器根目录的 `backups/` 下
  （打包前自动存盘，异步压缩不卡服）
- `/backup list` — 查看现有备份及所在目录
- 每日定时自动备份（默认凌晨 4:00），完成后聊天框广播 `[Server] Backup complete!`
- 滚动轮转：只保留最近 N 个 zip（默认 3 个），旧的自动删除
- 完成提示直接走玩家广播接口，不受 `send_command_feedback` gamerule 影响

## 配置 / Config

首次启动后可在服务器根目录 `config/kunbackup.properties` 修改：

```properties
backup.hour=4
backup.keep=3
```

- `backup.hour`：每天几点自动备份（0-23，本地时间）
- `backup.keep`：保留最近几个 zip

> 备份目录需要手动创建：服务器根目录下的 `backups/` 文件夹。
> 如果目录不存在，备份会失败并在日志中提示路径。

## 安装 / Install

1. 服务器需要 Fabric Loader + Fabric API（Minecraft 26.2）
2. 把 `build/libs/kunbackup-1.0.0.jar` 放进服务器 `mods/` 文件夹
3. 重启服务器，创建 `backups/` 文件夹

## 构建 / Build

需要 JDK 25：

```bash
gradle build
```

## 恢复世界 / Restore

1. 停服
2. 删掉（或改名）现在的 `world` 文件夹
3. 把备份 zip 解压到服务器根目录（zip 里就是完整的 `world/`）
4. 开服

## License

MIT
