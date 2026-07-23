pluginManagement {
    repositories {
        // Maven Central first — hosts AGP from 8.5.0+
        mavenCentral()
        gradlePluginPortal()
        
        // Shefferd mirror — replicates Google Maven, works from Iran
        maven { url = uri("https://mirrors.shefferd.dev/google-maven/") }
        
        // Google as fallback
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://mirrors.shefferd.dev/google-maven/") }
        google()
        
        // Signal's official Maven repo
        maven {
            name = "SignalBuildArtifacts"
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
        }
    }
}

rootProject.name = "ZeroChat"
include(":app")
