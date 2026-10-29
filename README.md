# KunBackup

轻量级 Minecraft 26.2 (Fabric) 服务端备份 mod。

A lightweight server-side backup mod for Minecraft 26.2 (Fabric).

## 功能 / Features

- `/backup` — 手动把 `world` 文件夹打包成 zip 存到服务器根目录的 `backups/` 下
  （打包前自动存盘，异步压缩不卡服）
- `/backup list` — 查看现有备份及所在目录
- `/backup reload` — 修改配置后热重载（仅 OP）
- 每日定时自动备份（默认凌晨 4:00），完成后聊天框广播 `[Server] Backup complete!`
- 滚动轮转：只保留最近 N 个 zip（默认 3 个），旧的自动删除
- 完成提示直接走玩家广播接口，不受 `send_command_feedback` gamerule 影响
- **权限**：默认仅 OP（权限等级 2）和控制台可用；也可以在配置里指定普通玩家名单，名单内玩家同样能用 `/backup`

## 配置 / Config

首次启动后可在服务器根目录 `config/kunbackup.properties` 修改：

```properties
backup.hour=4
backup.keep=3
allowed.players=Steve, Alex
```

- `backup.hour`：每天几点自动备份（0-23，本地时间）
- `backup.keep`：保留最近几个 zip
- `allowed.players`：允许使用 `/backup` 的玩家名，英文逗号分隔，不区分大小写；留空则仅 OP 可用。OP 和控制台始终可用
- 改完配置后在游戏里执行 `/backup reload`（或重启服务器）生效

> 备份目录需要手动创建：服务器根目录下的 `backups/` 文件夹。
> 如果目录不存在，备份会失败并在日志中提示路径。

## 环境要求 / Requirements

| 项目 / Item | 要求 / Requirement |
|---|---|
| Minecraft | **Java 版 26.2**（2026 年 6 月的 "Chaos Cubed"）；不支持 1.21.x 及更早版本 |
| mod 加载器 | **Fabric Loader ≥ 0.19.0**；**仅支持 Fabric**，不支持 Forge / NeoForge，也不是 Paper/Spigot 插件 |
| 依赖 mod | **Fabric API**（需安装 26.2 对应版本，如 `0.160.0+26.2`） |
| Java | **25**（Minecraft 26.2 的自带要求，服务器需用 Java 25 运行） |
| 运行端 | 为**专用服务端**设计，纯服务端 mod，玩家客户端不需要安装；单人模式装上也能加载但没意义 |

> 版本对照提示：MC 26.2 起游戏本体未混淆且要求 Java 25；本 mod 就是按这套新工具链编译的，无法在旧版本（1.21.11 及以下）上运行。

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
