// settings.gradle.kts — define QUAIS módulos existem e DE ONDE baixar dependências.
pluginManagement {
    repositories {
        google()            // Android Gradle Plugin, AndroidX, CameraX
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Proíbe que módulos declarem repositórios próprios: tudo fica centralizado aqui.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RootEncoder (pedroSG94) é publicado no JitPack. Restringimos o JitPack
        // ao grupo dele para que nenhuma outra dependência seja resolvida por lá
        // (reduz risco de supply-chain).
        maven("https://jitpack.io") {
            content { includeGroup("com.github.pedroSG94.RootEncoder") }
        }
    }
}

rootProject.name = "OldCam"
include(":app")
