rootProject.name = "Komponentual"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

val projectProperties = java.util.Properties()
file("gradle.properties").inputStream().use {
    projectProperties.load(it)
}

val versions: String by projectProperties

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.kotlin.link")
        mavenLocal()
    }
    
    versionCatalogs {
        create("versions").from("dev.lounres:versions:$versions")
    }
}

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.lounres.gradle.stal") version "0.4.0"
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.10.0")
}

stal {
    structure {
        taggedWith("publishing", "version catalog")
        defaultIncludeIf = { it.listFiles { file: File -> file.name != "build" || !file.isDirectory }?.isNotEmpty() == true }
        "libs" {
            subdirs("libs")
        }
        "docs"()
    }

    tag {
        // Kotlin set up
        "kotlin multiplatform" since { hasAnyOf("libs") }
        "kotlin common settings" since { hasAnyOf("kotlin multiplatform", "kotlin jvm") }
        "kotlin library settings" since { hasAnyOf("libs", "algorithms") }
        // Extra
//        "kotest" since { has("libs") }
//        "kover" since { has("libs") }
        "kotlin multiplatform publication" since { hasAnyOf("libs") }
        "publishing" since { has("libs") }
        "dokka" since { has("libs") }
    }

    action {
        gradle.allprojects {
            extra["artifactId"] = ""
            extra["alias"] = ""
            extra["isDokkaConfigured"] = false
            extra["jvmTargetVersion"] = settings.extra["jvmTargetVersion"]
            extra["jvmVendor"] = settings.extra["jvmVendor"]
        }
        "libs" {
            extra["artifactId"] = "komponentual.${project.name}"
            extra["alias"] = project.name
        }
        "version catalog" {
            extra["artifactId"] = "komponentual.versionCatalog"
        }
        "dokka" {
            extra["isDokkaConfigured"] = true
        }
    }
}