# BetterTerrain

Minecraft Java 26.3 的 Fabric 地形模组。当前提供长条山脉世界预设和山脉观察入口。

## 环境

- JDK 25（已验证 Temurin 25.0.5）
- IntelliJ IDEA，项目 SDK 与 Gradle JVM 均选择 JDK 25
- 使用项目自带 Gradle Wrapper，无需单独安装 Gradle

锁定版本：Minecraft 26.3、Fabric Loader 0.20.1、Fabric API 0.166.2+26.3、Loom 1.19.4、Gradle 9.8.2。

## 打开项目

在 IntelliJ IDEA 中打开本目录，按 Gradle 项目导入，等待依赖同步。

## 常用命令（PowerShell，在本目录执行）

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runDatagen
.\gradlew.bat genSources
```

- `build`：编译并打包，输出 `build/libs/betterterrain-0.1.0.jar`。
- `runClient`：启动开发客户端，用新建世界验证地形。
- `runServer`：启动开发服务端。首次运行需阅读并自行接受 Minecraft EULA。
- `runDatagen`：运行数据生成。当前未注册 provider，不会生成地形配置。
- `genSources`：生成供 IDE 阅读和调试的 Minecraft 源码。

## 在 Minecraft 中查看山脉

双击 `launch-terrain-preview.bat`，首次启动会创建种子 `42` 的创造模式世界，并把玩家放到山脊旁的空中。同一种子再次打开时继续使用原存档和上次停留的位置。切换种子可在项目根目录执行：

```powershell
.\launch-terrain-preview.bat 12345
.\launch-terrain-preview.bat -Seed -12345 -GameMode survival
```

本机设置在 `run/minecraft-preview.properties`。新环境可以从 [配置样例](scripts/minecraft-preview.example.properties) 复制一份，设置 `game.dir` 为 PCL 中开启版本隔离的 Fabric 实例目录，`launch.file` 为该实例用 PCL 导出的启动脚本。也可设置 `minecraft.version`、默认 `seed` 和 `gameMode`，并用 `-Config` 指定另一份配置。游戏实例应有 Minecraft 26.3、Fabric Loader 与相应的 Fabric API。PCL 导出的脚本可能含有登录参数，保留在本机。

先退出该实例里正在运行的 Minecraft，再运行脚本。脚本构建模组并安装到实例的 `mods/betterterrain-preview.jar`。更新时将旧 JAR 备份为同目录的 `.jar.bak`。存档位于实例的 `saves/betterterrain-mountains-<种子>`，启动记录位于项目的 `run/minecraft-preview/`。也可以在游戏的「世界类型」中手动选择 **BetterTerrain 山脉**（`betterterrain:terrain_preview`）。

## 网页山脉预览

用 JDK 25 在项目根目录执行 `.\gradlew.bat serveTerrainPreview`，再打开终端打印的 <http://127.0.0.1:8765/>。通过「观察窗口」在种子 `42` 和 `12345` 的原点、远处正负坐标间切换。六个 16,384 格混合山地窗口各有八条主脊，分别为四条长条、一条弧形、两条并列和一条分支山脉，块状山地用 M 单独标记。全景按类型显示脊线。点选地标或滚轮靠近，可在山段、近景和单方块精度之间切换，查看云层参考面及高程剖面。首次构建预览会从 Maven Central 解析固定版本的 Three.js，后续可用 `--offline`。仅导出静态页面和网格时执行 `.\gradlew.bat terrainPreview`，结果在 `build/terrain-preview/site/`。

选中山峰后点击「自由飞行」，以第一人称观察草地、泥土、岩石和积雪的像素方块。点击视窗控制鼠标方向，`WASD` 移动，空格升高，`Shift` 降低。按 `Esc` 或点击「返回透视」可继续使用俯瞰旋转视角。

## 项目结构


世界生成分为宏观地貌、水系、局部地形和群系四个模块。各模块目录下的 `SPEC.md` 写明目标，`HARNESS.md` 写明验收方式。完整目录树见 [AGENTS.md](AGENTS.md#项目结构)，项目目标见 [SPEC.md](SPEC.md)。

## 许可

BetterTerrain 采用 MIT 许可证，详见 [LICENSE](LICENSE)。
Gradle Wrapper 来自官方 Fabric 示例项目，保留其文件内的上游许可声明。

## 参考

- https://docs.fabricmc.net/develop/getting-started/creating-a-project
- https://docs.fabricmc.net/develop/data-generation/setup

## 初始化验证

- 三个 Java 入口已使用 JDK 25、Fabric Loader 0.20.1、Fabric API 0.166.2+26.3 和 Minecraft 26.3 实际编译通过。
- Gradle Wrapper 已成功下载并启动 Gradle 9.8.2，Loom 1.19.4 可解析。
- 已使用缓存依赖运行 `./gradlew.bat build --offline`，生成 `build/libs/betterterrain-0.1.0.jar` 并成功保存 Gradle 配置缓存。
- Gradle 的 `test` 任务显示 `NO-SOURCE`。游戏启动和后续世界生成按 [HARNESS.md](HARNESS.md) 验收。
