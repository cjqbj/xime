# App ProGuard rules
-dontobfuscate

-optimizations !class/merging/*

# Keep utility classes used by Application and services
-keep class com.kingzcheung.xime.util.** { *; }

# luaj 插件运行时: 库注册依赖反射（bind 通过 getConstructor/getMethod 按名查找实现类），
# R8 无法静态发现 Bit32LibV 等实现类，整体保留不裁剪
-keep class org.luaj.vm2.** { *; }

# luaj 可选依赖（Android 上不存在）：JSR-223 script / bcel 后端 / JDK 内部类
-dontwarn javax.script.**
-dontwarn org.apache.bcel.**
-dontwarn com.sun.nio.file.**
-dontwarn kotlin.Cloneable$DefaultImpls

# Keep Kotlin stdlib classes used by plugins via parent classloader
# Plugins use compileOnly(plugin-core), so Kotlin stdlib resolves from host app.
# R8 strips unused stdlib methods — these rules ensure plugins can call them.
-keep class kotlin.Metadata { *; }

-keep class com.kingzcheung.xime.plugin.** { *; }
-keepclassmembers class com.kingzcheung.xime.plugin.** { *; }

-keep class com.kingzcheung.xime.rime.** { *; }
-keep class com.kingzcheung.xime.**Jni** { *; }

-keepattributes SourceFile,LineNumberTable

-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

-processkotlinnullchecks remove