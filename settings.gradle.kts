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
        // Linphone SDK (GPLv3) is published only to its own repository.
        maven("https://download.linphone.org/maven_repository") {
            content { includeGroup("org.linphone") }
        }
    }
}

rootProject.name = "SipDialer"
include(":app")
