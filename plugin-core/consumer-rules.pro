# Plugin Core Consumer Rules
# These rules ensure all plugin API interfaces are preserved

# Keep all API interfaces and implementations
-keep class com.kingzcheung.xime.plugin.core.api.** { *; }
-keep interface com.kingzcheung.xime.plugin.core.api.** { *; }

# Keep all model classes
-keep class com.kingzcheung.xime.plugin.core.model.** { *; }

# Keep all runtime classes
-keep class com.kingzcheung.xime.plugin.core.runtime.** { *; }

# Keep Kotlin metadata
#-keep class kotlin.Metadata { *; }
#-keep @kotlin.Metadata class * { <methods>; }

# Keep suspend function signatures
-keepclassmembers class * {
    public *** *(kotlin.coroutines.Continuation);
}

# luaj: 库注册依赖反射（bind() 通过 getConstructor/getMethod 按名查找实现类），
# 混淆会裁剪掉 Bit32LibV 等实现类，运行时报 NoClassDefFoundError，必须整体保留
-keep class org.luaj.vm2.** { *; }