# 当前 release 构建未启用混淆（isMinifyEnabled = false），此文件为后续开启做准备。

# kotlinx-serialization 需要保留注解与泛型签名
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault

# 反序列化 DTO（避免被移出）
-keep,includedescriptorclasses class com.waxilo.marketmonitor.data.remote.dto.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.internal.**
-dontwarn okio.**
