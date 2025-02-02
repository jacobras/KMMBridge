/*
 * Copyright (c) 2023 Touchlab.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package co.touchlab.faktory

import co.touchlab.faktory.versionmanager.ManualVersionManager
import co.touchlab.faktory.versionmanager.VersionException
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkConfig
import java.io.File
import kotlin.collections.filter
import kotlin.collections.flatMap
import kotlin.collections.forEach

@Suppress("unused")
class KMMBridgePlugin : Plugin<Project> {

    override fun apply(project: Project): Unit = with(project) {
        val extension = extensions.create<KmmBridgeExtension>(EXTENSION_NAME)

        extension.dependencyManagers.convention(emptyList())
        extension.versionManager.convention(ManualVersionManager)
        extension.buildType.convention(NativeBuildType.RELEASE)

        afterEvaluate {
            configureXcFramework()
            configureLocalDev()
            if(enablePublishing) {
                configureArtifactManagerAndDeploy()
            }
        }
    }

    private fun Project.configureZipTask(extension: KmmBridgeExtension): Pair<TaskProvider<Zip>, File> {
        val zipFile = zipFilePath()
        val zipTask = tasks.register<Zip>("zipXCFramework") {
            group = TASK_GROUP_NAME
            from("$buildDir/XCFrameworks/${extension.buildType.get().getName()}")
            destinationDirectory.set(zipFile.parentFile)
            archiveFileName.set(zipFile.name)
        }

        return Pair(zipTask, zipFile)
    }

    // Collect all declared frameworks in project and combine into xcframework
    private fun Project.configureXcFramework() {
        val extension = kmmBridgeExtension
        var xcFrameworkConfig: XCFrameworkConfig? = null

        val spmBuildTargets: Set<String> = project.spmBuildTargets?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        kotlin.targets
            .withType<KotlinNativeTarget>()
            .filter { it.konanTarget.family.isAppleFamily }
            .flatMap { it.binaries.filterIsInstance<Framework>() }
            .forEach { framework ->
                val theName = framework.baseName
                val currentName = extension.frameworkName.orNull
                if (currentName == null) {
                    extension.frameworkName.set(theName)
                } else {
                    if (currentName != theName) {
                        throw IllegalStateException("Only one framework name currently allowed. Found $currentName and $theName")
                    }
                }
                val shouldAddTarget = spmBuildTargets.isEmpty() || spmBuildTargets.contains(framework.target.konanTarget.name)
                if(shouldAddTarget) {
                    if (xcFrameworkConfig == null) {
                        xcFrameworkConfig = XCFramework(theName)
                    }
                    xcFrameworkConfig!!.add(framework)
                }
            }
    }

    private fun Project.configureLocalDev() {
        val extension = kmmBridgeExtension
        extension.localDevManager.orNull?.configureLocalDev(this)
    }

    private fun Project.configureArtifactManagerAndDeploy() {
        val extension = extensions.getByType<KmmBridgeExtension>()

        // Early-out with a warning if user hasn't added required config yet, to ensure project still syncs
        val artifactManager = extension.artifactManager.orNull ?: run {
            project.logger.warn("You must apply an artifact manager! Call `artifactManager.set(...)` or a configuration function like `mavenPublishArtifacts()` in your `kmmbridge` block.")
            return
        }
        val versionManager = extension.versionManager.orNull ?: run {
            project.logger.warn("You must apply an version manager! Call `versionManager.set(...)` or a configuration function like `githubReleaseVersions()` in your `kmmbridge` block.")
            return
        }

        val version = try {
            versionManager.getVersion(project)
        } catch (e: VersionException) {
            if (e.localDevOk) {
                project.logger.info("(KMMBridge) ${e.message}")
            } else {
                project.logger.warn("(KMMBridge) ${e.message}")
            }
            return
        }

        val (zipTask, zipFile) = configureZipTask(extension)

        // Zip task depends on the XCFramework assemble task
        zipTask.configure {
            dependsOn(findXCFrameworkAssembleTask())
        }

        // Upload task depends on the zip task
        val uploadTask = tasks.register("uploadXCFramework") {
            group = TASK_GROUP_NAME

            dependsOn(zipTask)
            inputs.file(zipFile)
            outputs.files(urlFile, versionFile)
            outputs.upToDateWhen { false } // We want to always upload when this task is called

            @Suppress("ObjectLiteralToLambda")
            doLast(object : Action<Task> {
                override fun execute(t: Task) {
                    versionFile.writeText(version)
                    logger.info("Uploading XCFramework version $version")
                    val deployUrl = artifactManager.deployArtifact(project, zipFile, version)
                    urlFile.writeText(deployUrl)
                }
            })
        }

        // Publish task depends on the upload task
        val publishRemoteTask = tasks.register("kmmBridgePublish") {
            group = TASK_GROUP_NAME
            dependsOn(uploadTask)

            @Suppress("ObjectLiteralToLambda")
            doLast(object : Action<Task> {
                override fun execute(t: Task) {
                    // currently just a dependency anchor
                }
            })
        }

        // MavenPublishArtifactManager is somewhat complex because we have to hook into maven publishing
        // If you are exploring the task dependencies, be aware of that code
        artifactManager.configure(this, version, uploadTask, publishRemoteTask)

        val dependencyManagers = extension.dependencyManagers.get()
        for (dependencyManager in dependencyManagers) {
            dependencyManager.configure(this, uploadTask, publishRemoteTask)
        }
    }
}
