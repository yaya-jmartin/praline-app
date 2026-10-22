// The official Android build (Android Gradle Plugin) for Praliné, run on GitHub Actions where Google's
// servers are reachable. It builds the same sources as the hand-made build at the repository root.
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "praline-agp"
include(":app")
