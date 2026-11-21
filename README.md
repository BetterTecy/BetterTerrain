# BetterTerrain

Minecraft Java 26.3 的 Fabric 地形模组。目前为项目骨架，尚未修改世界生成。

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
