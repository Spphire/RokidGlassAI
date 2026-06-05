pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Rokid Maven repository (must be first for CXR SDK)
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
        // Alibaba Cloud Mirror (Accelerated)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
}

rootProject.name = "RokidPhotoAI"

// Simplified photo-AI architecture.
include(":common") // Shared Bluetooth protocol and photo packet definitions.
include(":phone-app") // Phone relay app: prompt, camera test, Bluetooth bridge, AI request.
include(":glasses-app") // Glasses app: camera trigger, photo compression/transfer, result display.
