/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.npm.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import java.io.File

open class KotlinNpmInstallTask : DefaultTask() {
    init {
        check(project == project.rootProject)

        onlyIf {
            preparedFiles.all {
                it.exists()
            }
        }
    }

    private val nodeJs get() = NodeJsRootPlugin.apply(project.rootProject)
    private val resolutionManager get() = nodeJs.npmResolutionManager

    @Input
    val args: MutableList<String> = mutableListOf()

    @Suppress("unused")
    @get:InputFiles
    val packageJsonFiles: Collection<File>
        get() = resolutionManager.packageJsonFiles

    @get:InputFiles
    val preparedFiles: Collection<File>
        get() = nodeJs.packageManager.preparedFiles(project)

    // avoid using node_modules as output directory, as it is significantly slows down build
    @get:OutputFile
    val nodeModulesState: File
        get() = nodeJs.rootNodeModulesStateFile

    @get:OutputFile
    val yarnLock: File
        get() = nodeJs.rootPackageDir.resolve("yarn.lock")

    @TaskAction
    fun resolve() {
        resolutionManager.installIfNeeded(args = args)
    }

    companion object {
        const val NAME = "kotlinNpmInstall"
    }
}