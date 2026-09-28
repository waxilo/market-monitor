import com.android.build.api.dsl.ApkSigningConfig
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.waxilo.marketmonitor"
    compileSdk = 36

    /**
     * 正式签名密钥。
     *
     * 应用内更新要求「新包能覆盖安装旧包」，而 Android 只认**同一把密钥**签出来的包
     * （否则 INSTALL_FAILED_UPDATE_INCOMPATIBLE）。所以这把密钥必须固定：
     * CI 上从 Secrets 注入，本地可选用 `keystore.properties` 覆盖。
     *
     * 找不到密钥时**返回 null**，release 构建降级用 debug 签名 —— 本地开发 / 跑单测
     * 不该被一把生产密钥卡住；但发布流水线会显式断言密钥存在（见 release.yml）。
     */
    fun resolveReleaseKeystore(): ApkSigningConfig? {
        // CI（release.yml 解码 Secrets 后注入的环境变量）
        val envStore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let(::file)
        val envAlias = System.getenv("KEY_ALIAS")
        val envStorePass = System.getenv("KEYSTORE_PASSWORD")
        val envKeyPass = System.getenv("KEY_PASSWORD")
        if (envStore != null && envStore.exists() && !envAlias.isNullOrBlank() && !envStorePass.isNullOrBlank()) {
            return signingConfigs.create("release") {
                storeFile = envStore
                storePassword = envStorePass
                keyAlias = envAlias
                keyPassword = envKeyPass ?: envStorePass
            }
        }

        // 本地（keystore.properties 不入库，见 .gitignore）
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            val props = Properties().apply { propsFile.inputStream().use { load(it) } }
            val store = props.getProperty("storeFile")?.let { rootProject.file(it) }
            val alias = props.getProperty("keyAlias")
            val storePass = props.getProperty("storePassword")
            if (store != null && store.exists() && !alias.isNullOrBlank() && !storePass.isNullOrBlank()) {
                return signingConfigs.create("release") {
                    storeFile = store
                    storePassword = storePass
                    keyAlias = alias
                    keyPassword = props.getProperty("keyPassword") ?: storePass
                }
            }
        }
        return null
    }

    val releaseSigning = resolveReleaseKeystore()

    defaultConfig {
        applicationId = "com.waxilo.marketmonitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 37
        versionName = "0.9.25"

        buildConfigField("String", "UPDATE_OWNER", "\"waxilo\"")
        buildConfigField("String", "UPDATE_REPO", "\"market-monitor\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 签名固定才能覆盖安装（应用内更新的前提）；缺密钥时退回 debug 签名。
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
            // CI 发布暂不启用混淆以免定位困难
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
