import java.util.Properties
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "com.example.rokidphone"
    compileSdk = 36

    val localPropsFile = rootProject.file("local.properties")
    val localProps = Properties().apply {
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
    }
    fun localProperty(name: String): String? = localProps.getProperty(name)?.takeIf { it.isNotBlank() }
    fun buildConfigString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    val releaseStoreFile = localProperty("RELEASE_STORE_FILE")
    val releaseStorePassword = localProperty("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = localProperty("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = localProperty("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    defaultConfig {
        applicationId = "com.example.rokidphone"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "0.12.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val codexRelayUrl = localProps.getProperty("CODEX_RELAY_URL", "https://api.20021004.xyz")
        val codexRelayApiKey = localProps.getProperty(
            "CODEX_RELAY_API_KEY",
            ""
        )
        val codexRelayModel = localProps.getProperty("CODEX_RELAY_MODEL", "gpt-5.5")
        val defaultVisionPrompt = localProps.getProperty(
            "DEFAULT_VISION_PROMPT",
            "\u5e2e\u6211\u56de\u7b54\u56fe\u4e2d\u7684\u9898\u76ee\uff1a\u5982\u679c\u662f\u5ba2\u89c2\u9898\uff0c\u4ec5\u7ed9\u51fa\u6b63\u786e\u9009\u9879\u4ee5\u53ca\u4e00\u53e5\u8bdd\u7684\u89e3\u91ca\uff1b\u5982\u679c\u662f\u4e3b\u89c2\u9898\uff0c\u5206\u70b9\u7cbe\u7b80\u56de\u7b54\u53bb\u9664AI\u5473\u5e76\u4ee5\u7814\u7a76\u751f\u7684\u53e3\u543b"
        )

        buildConfigField("String", "CODEX_RELAY_URL", buildConfigString(codexRelayUrl))
        buildConfigField("String", "CODEX_RELAY_API_KEY", buildConfigString(codexRelayApiKey))
        buildConfigField("String", "CODEX_RELAY_MODEL", buildConfigString(codexRelayModel))
        buildConfigField("String", "DEFAULT_VISION_PROMPT", buildConfigString(defaultVisionPrompt))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }

        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.lifecycle("Release signing is not configured. Building unsigned release artifacts.")
            }
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class.java) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

dependencies {
    implementation(project(":common"))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")

    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
