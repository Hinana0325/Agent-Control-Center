pluginManagement {
    repositories {
        // Official repos first (reliable in CI)
        gradlePluginPortal()
        mavenCentral()
        google()
        // Aliyun mirrors as fallback (for China network)
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        // Aliyun mirrors as fallback (for China network)
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "AgentControlCenterDesktop"
