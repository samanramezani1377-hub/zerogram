pluginManagement {
    repositories {
        // KSP & other plugins first (Gradle Plugin Portal)
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        
        // Android/Google plugins
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        
        // General dependencies
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Aliyun mirrors
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/jcenter") }
        
        // Fallbacks
        google()
        mavenCentral()
        
        // Signal's official Maven repo for libsignal-client >= 0.45
        maven {
            name = "SignalBuildArtifacts"
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
        }
    }
}

rootProject.name = "ZeroChat"
include(":app")
