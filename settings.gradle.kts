pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "TerminalPlus"
include(":app")
include(":guest-protocol")

include(":terminal-emulator", ":terminal-view")
project(":terminal-emulator").projectDir = file("third_party/termux/terminal-emulator")
project(":terminal-view").projectDir = file("third_party/termux/terminal-view")
