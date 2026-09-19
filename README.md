clone 后首次构建大概耗时  8 min



已定位，磁盘主要被以下内容占用：

| 路径 | 大小 | 说明 |
|---|---:|---|
| `.buildozer` | 9.8G | SDK、NDK、Buildozer 构建缓存 |
| `build` | 2.8G | Gradle 构建中间产物 |
| `.cxx` | 2.1G | CMake/Native 编译中间产物 |
| `.gradle` | 2.9G | Gradle 缓存和发行版 |
| `java_pid40256.hprof` | 493M | Java 崩溃堆转储 |
| 4 个 APK | 约 0.5G | 正常构建产物 |

另外发现当前 Gradle/Kotlin 编译进程仍在运行，且没有“已删除但仍占用”的隐藏文件。

构建完成并确认不再需要中间产物后，可以优先清理：

```bash
rm -rf /workspaces/Xime/app/.cxx
rm -rf /workspaces/Xime/app/build/intermediates
rm -rf /workspaces/Xime/app/build/kotlin
rm -rf /workspaces/Xime/app/build/kspCaches
rm -rf /workspaces/Xime/app/build/tmp
rm -rf /workspaces/Xime/app/build/generated
rm -f /home/vscode/.buildozer/android/platform/build-arm64-v8a/dists/hualing/java_pid40256.hprof
```

预计释放约 5G。

如果不再使用 Buildozer 的旧 NDK，还可以清理：

```bash
rm -rf /home/vscode/.buildozer/android/platform/android-ndk-r25b
rm -f /home/vscode/.buildozer/android/platform/android-ndk-r25b-linux.zip
```

预计额外释放约 2.2G。当前项目实际使用的是 Android SDK 下的 NDK 29。不要在构建进程结束前删除这些目录。