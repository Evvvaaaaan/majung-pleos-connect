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
        // 공식 가이드(https://document.pleos.ai/docs/pleos-only/getting-started Step 3) 기준 Pleos Maven 저장소
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
        }
    }
}

rootProject.name = "MajungPleosConnect"
include(":app")
