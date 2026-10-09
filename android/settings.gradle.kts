pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Tether"
include(":core")
// Local sandbox (aarch64, no aapt2) builds only the JVM core; CI builds everything.
if (System.getenv("TETHER_CORE_ONLY") == null) include(":app")
