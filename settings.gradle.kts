pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        
        // Mirror for Google Maven (AGP, Compose, etc.)
        // This is a known working mirror for Iran
        maven {
            url = uri("https://storage.googleapis.com/gradle-releases/")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        
        // Signal's official Maven repo
        maven {
            name = "SignalBuildArtifacts"
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
        }
    }
}

rootProject.name = "ZeroChat"
include(":app")
