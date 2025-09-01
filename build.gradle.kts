@file:Suppress("SuspiciousCollectionReassignment")

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import kotlinx.atomicfu.plugin.gradle.AtomicFUPluginExtension
import org.gradle.accessors.dm.LibrariesForVersions
import org.gradle.accessors.dm.RootProjectAccessor
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Warning
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.yarn
import kotlin.collections.plus


plugins {
    alias(versions.plugins.kotlin.multiplatform) apply false
    alias(versions.plugins.kotlinx.atomicfu) apply false
    alias(versions.plugins.kotlin.compose) apply false
    alias(versions.plugins.compose.multiplatform) apply false
    alias(versions.plugins.dokka)
    `version-catalog`
    alias(versions.plugins.gradle.maven.publish.plugin)
}

val komponentualVersion = project.properties["version"] as String
val komponentualGroup = project.properties["group"] as String

allprojects {
    version = komponentualVersion
    
    repositories {
        google()
        mavenCentral()
        maven("https://repo.kotlin.link")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
        mavenLocal()
    }
}

val Project.versions: LibrariesForVersions get() = rootProject.extensions.getByName<LibrariesForVersions>("versions")
//val Project.libs: LibrariesForLibs get() = rootProject.extensions.getByName<LibrariesForLibs>("libs")
val Project.projects: RootProjectAccessor get() = rootProject.extensions.getByName<RootProjectAccessor>("projects")
fun PluginAware.apply(pluginDependency: PluginDependency) = apply(plugin = pluginDependency.pluginId)
fun PluginAware.apply(pluginDependency: Provider<PluginDependency>) = apply(plugin = pluginDependency.get().pluginId)
fun PluginAware.apply(pluginDependency: ProviderConvertible<PluginDependency>) = apply(plugin = pluginDependency.asProvider().get().pluginId)
fun PluginManager.withPlugin(pluginDep: PluginDependency, block: AppliedPlugin.() -> Unit) = withPlugin(pluginDep.pluginId, block)
fun PluginManager.withPlugin(pluginDepProvider: Provider<PluginDependency>, block: AppliedPlugin.() -> Unit) = withPlugin(pluginDepProvider.get().pluginId, block)
fun PluginManager.withPlugins(vararg pluginDeps: PluginDependency, block: AppliedPlugin.() -> Unit) = pluginDeps.forEach { withPlugin(it, block) }
fun PluginManager.withPlugins(vararg pluginDeps: Provider<PluginDependency>, block: AppliedPlugin.() -> Unit) = pluginDeps.forEach { withPlugin(it, block) }
inline fun <T> Iterable<T>.withEach(action: T.() -> Unit) = forEach { it.action() }

val Project.artifact: String get() = extra["artifactId"] as String
val Project.alias: String get() = extra["alias"] as String

catalog.versionCatalog {
    version("komponentual", komponentualVersion)
}

gradle.projectsEvaluated {
    val bundleLibsProjects = stal.lookUp.projectsThat { has("libs") }
//    val bundleLibsAliases = bundleLibsProjects.map { it.alias }
    catalog.versionCatalog {
        for (p in bundleLibsProjects)
            library(p.alias, komponentualGroup, p.artifact).versionRef("komponentual")
        
//        bundle("all", bundleLibsAliases)
    }
}

stal {
    action {
        "kotlin jvm" {
            apply(versions.plugins.kotlin.jvm)
            configure<KotlinJvmProjectExtension> {
                compilerOptions {
                    freeCompilerArgs = freeCompilerArgs.get() + listOf(
                        "-Xklib-duplicated-unique-name-strategy=allow-all-with-warning",
                        "-Xexpect-actual-classes",
                        "-Xconsistent-data-class-copy-visibility",
                    )
                }
                
                @Suppress("UNUSED_VARIABLE")
                sourceSets {
                    val test by getting {
                        dependencies {
                            implementation(kotlin("test"))
                        }
                    }
                }
            }
        }
        "kotlin multiplatform" {
            apply(versions.plugins.kotlin.multiplatform)
            configure<KotlinMultiplatformExtension> {
                applyDefaultHierarchyTemplate()
                
                compilerOptions {
                    freeCompilerArgs = freeCompilerArgs.get() + listOf(
                        "-Xklib-duplicated-unique-name-strategy=allow-all-with-warning",
                        "-Xexpect-actual-classes",
                        "-Xconsistent-data-class-copy-visibility",
                    )
                }
                
                jvm {
                    testRuns.all {
                        executionTask {
                            useJUnitPlatform()
                        }
                    }
                }
                
                js {
                    browser()
                    nodejs()
                }
                
                @OptIn(ExperimentalWasmDsl::class)
                wasmJs {
                    browser()
                    nodejs()
                    d8()
                }

//                linuxX64()
//                mingwX64()
//                macosX64()

//                androidTarget()
//                iosX64()
//                iosArm64()
//                iosSimulatorArm64()
//                macosArm64()
                
                @Suppress("UNUSED_VARIABLE")
                sourceSets {
                    commonTest {
                        dependencies {
                            implementation(kotlin("test"))
                        }
                    }
                }
            }
            afterEvaluate {
                yarn.lockFileDirectory = rootDir.resolve("gradle")
            }
        }
        "kotlin common settings" {
            pluginManager.withPlugins(versions.plugins.kotlin.jvm, versions.plugins.kotlin.multiplatform) {
                configure<KotlinProjectExtension> {
                    jvmToolchain {
                        languageVersion = JavaLanguageVersion.of(project.extra["jvmTargetVersion"] as String)
                        vendor = JvmVendorSpec.matching(project.extra["jvmVendor"] as String)
                    }
                    
                    sourceSets {
                        all {
                            languageSettings {
                                progressiveMode = true
                                enableLanguageFeature("ContextParameters")
                                enableLanguageFeature("ValueClasses")
                                enableLanguageFeature("ContractSyntaxV2")
                                enableLanguageFeature("ExplicitBackingFields")
                                optIn("kotlin.contracts.ExperimentalContracts")
                                optIn("kotlin.ExperimentalStdlibApi")
                                optIn("kotlin.ExperimentalSubclassOptIn")
                                optIn("kotlin.ExperimentalUnsignedTypes")
                                optIn("kotlin.uuid.ExperimentalUuidApi")
                                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
                                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                                optIn("dev.lounres.kone.annotations.UnstableKoneAPI")
                                optIn("dev.lounres.kone.annotations.ExperimentalKoneAPI")
                            }
                        }
                    }
                }
            }
            pluginManager.withPlugin("org.gradle.java") {
                tasks.withType<Test> {
                    useJUnitPlatform()
                }
            }
        }
        "kotlin library settings" {
            configure<KotlinProjectExtension> {
                explicitApi = Warning
            }
        }
        "atomicfu" {
            apply(versions.plugins.kotlinx.atomicfu)
            configure<AtomicFUPluginExtension> {
                transformJvm = true
                jvmVariant = "VH"
            }
        }
        "compose" {
            apply(versions.plugins.kotlin.compose)
            apply(versions.plugins.compose.multiplatform)
        }
        "dokka" {
            val thisProject = this
            val docsProject = project(":docs")
            
            apply(versions.plugins.dokka)
            dependencies {
                dokkaPlugin(versions.dokka.mathjax)
            }
            
            docsProject.afterEvaluate {
                dependencies {
                    dokka(thisProject)
                }
            }
            
            configure<DokkaExtension> {
                moduleName = project.artifact
                // DOKKA-3885
                dokkaGeneratorIsolation = ClassLoaderIsolation()
                
                dokkaSourceSets.all {
//                    reportUndocumented = true
                    
                    sourceLink {
                        val relativePathToSourceRoot = project.projectDir.toRelativeString(rootDir).replace('\\', '/')
                        remoteUrl("https://github.com/lounres/Kone/tree/experiment/$relativePathToSourceRoot")
                    }
                }
                
                pluginsConfiguration.html {
//                    customAssets.from(docsProject.projectDir.resolve("images/logo-icon.svg"), docsProject.projectDir.resolve("images/favicon.svg"))
                    footerMessage = "Copyright © 2025 Gleb Minaev<br>All rights reserved. Licensed under the Apache License, Version 2.0. See the license in file LICENSE"
                    templatesDir = docsProject.projectDir.resolve("templates")
                }
            }
        }
        "kotlin jvm publication" {
            pluginManager.withPlugin("com.vanniktech.maven.publish") {
                configure<MavenPublishBaseExtension> {
                    configure(
                        KotlinJvm(
                            javadocJar = JavadocJar.Empty(),
                            sourcesJar = true,
                        )
                    )
                }
            }
        }
        "kotlin multiplatform publication" {
            pluginManager.withPlugin("com.vanniktech.maven.publish") {
                configure<MavenPublishBaseExtension> {
                    configure(
                        KotlinMultiplatform(
                            javadocJar =
                                if (extra["isDokkaConfigured"] == true) JavadocJar.Dokka("dokkaGeneratePublicationHtml")
                                else JavadocJar.Empty(),
                            sourcesJar = true,
                        )
                    )
                }
            }
        }
        "publishing" {
            apply(plugin = "com.vanniktech.maven.publish")
            configure<MavenPublishBaseExtension> {
                publishToMavenCentral()
                
                signAllPublications()
                
                coordinates(groupId = project.group as String, artifactId = project.artifact, version = project.version as String)
                
                pom {
                    name = "Komponentual library"
                    description = "Set of libraries for creating component-bassed applications"
                    url = "https://github.com/lounres/Komponentual"
                    
                    licenses {
                        license {
                            name = "Apache License, Version 2.0"
                            url = "https://opensource.org/license/apache-2-0/"
                        }
                    }
                    developers {
                        developer {
                            id = "lounres"
                            name = "Gleb Minaev"
                            email = "minaevgleb@yandex.ru"
                        }
                    }
                    scm {
                        url = "https://github.com/lounres/Komponentual"
                    }
                }
            }
        }
    }
}