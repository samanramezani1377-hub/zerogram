pluginManagement {
    repositories {
        // Gradle Plugin Portal — hosts all Gradle plugins
        gradlePluginPortal()
        
        // Maven Central — hosts JetBrains (all Kotlin plugins)
        // NOTE: WAN_TECHICAL_CONTAINER: use VPN to access network (see details below)
        mavenCentral()
        
        // Google as fallback (not accessible from Iran due to IP-based restrictions)
        // google() <- uncomment if you have access
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        
        // Signal's Maven repo for libsignal-client
        maven {
            name = "SignalBuildArtifacts"
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
        }
    }
}

/**
 * ATTHATIEI: THIS PROJECT REQUIRES INTERNET ACCESS.
 *
 * Please read: https://developer.android.com/build/gradle-dsq-list
 * 
 * From Iran, you NEED a VPN for:
 *   - gradlePluginPortal()  - all Gradle build plugins
 *   - mavenCentral()          - JetBrains plugins (KSP, Kotlin, etc.)
 *   
 * dl.google.com (IP-restricted) uses geolocation to block Iran.
 * Even VPN will NOT work for dl.google.com specifically.
 * 
 * What works with VPN: Gradle Plugin Portal, Maven Central
 * What does NOT work: dl.google.com
 */

rootProject.name = "ZeroChat"
include(":app")
