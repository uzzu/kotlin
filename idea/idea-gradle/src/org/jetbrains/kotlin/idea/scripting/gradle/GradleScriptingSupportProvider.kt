/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scripting.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.script.configuration.ScriptingSupport
import org.jetbrains.kotlin.idea.core.script.configuration.utils.ScriptClassRootsIndexer
import org.jetbrains.kotlin.idea.scripting.gradle.importing.GradleKtsContext
import org.jetbrains.kotlin.idea.scripting.gradle.importing.KotlinDslGradleBuildSync
import org.jetbrains.kotlin.idea.scripting.gradle.importing.KotlinDslScriptModel
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.KotlinScriptDefinitionFromAnnotatedTemplate
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import org.jetbrains.plugins.gradle.config.GradleSettingsListenerAdapter
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.settings.GradleSettingsListener
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates [GradleScriptingSupport] per each linked Gradle build.
 * Gradle project sync should call [update].
 */
class GradleScriptingSupportProvider(val project: Project) : ScriptingSupport.Provider() {
    private val rootsIndexer = ScriptClassRootsIndexer(project)

    private val byBuildRoot = ConcurrentHashMap<VirtualFile, GradleScriptingSupport>()
    override val all get() = byBuildRoot.values

    override fun getSupport(file: VirtualFile): ScriptingSupport? =
        all.find {
            file.path.startsWith(it.buildRoot.path)
        }

    init {
        getGradleProjectSettings(project).forEach { gradleProjectSettings ->
            if (kotlinDslScriptsModelImportSupported(gradleProjectSettings.resolveGradleVersion().version)) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val support = createSupport(gradleProjectSettings.externalProjectPath) {
                        KotlinDslScriptModels.read(it)
                    }

                    if (support != null) {
                        byBuildRoot.putIfAbsent(support.buildRoot, support)
                    }
                }
            }
        }

        // subscribe to gradle build unlink
        val listener = object : GradleSettingsListenerAdapter() {
            override fun onProjectsUnlinked(linkedProjectPaths: MutableSet<String>) {
                linkedProjectPaths.forEach {
                    val buildRoot = VfsUtil.findFile(Paths.get(it), false)
                    byBuildRoot.remove(buildRoot)
                }
            }
        }

        project.messageBus.connect(project).subscribe(GradleSettingsListener.TOPIC, listener)
    }

    private fun createSupport(
        externalProjectPath: String,
        dataProvider: (buildRoot: VirtualFile) -> ConfigurationData?
    ): GradleScriptingSupport? {
        val gradleExeSettings =
            ExternalSystemApiUtil.getExecutionSettings<GradleExecutionSettings>(
                project,
                externalProjectPath,
                GradleConstants.SYSTEM_ID
            )

        val javaHome = gradleExeSettings.javaHome ?: return null
        val buildRoot = VfsUtil.findFile(Paths.get(externalProjectPath), true) ?: return null
        val data = dataProvider(buildRoot) ?: return null

        return GradleScriptingSupport(
            rootsIndexer,
            project,
            buildRoot,
            GradleKtsContext(File(javaHome)),
            Configuration(data)
        )
    }

    fun update(build: KotlinDslGradleBuildSync) {
        if (build.models.isEmpty()) return // todo: why?

        val templateClasspath = findTemplateClasspath(build) ?: return
        val data = ConfigurationData(templateClasspath, build.models)
        val newSupport = createSupport(build.workingDir) { data } ?: return

        byBuildRoot[newSupport.buildRoot] = newSupport
    }

    private fun findTemplateClasspath(build: KotlinDslGradleBuildSync): List<String>? {
        val anyScript = VfsUtil.findFile(Paths.get(build.models.first().file), true)!!
        // todo: find definition according to build.workingDir
        val definition = anyScript.findScriptDefinition(project) ?: return null
        return definition.asLegacyOrNull<KotlinScriptDefinitionFromAnnotatedTemplate>()
            ?.templateClasspath?.map { it.path }
    }

    companion object {
        fun getInstance(project: Project): GradleScriptingSupportProvider =
            EPN.getPoint(project).extensionList.firstIsInstance()
    }
}