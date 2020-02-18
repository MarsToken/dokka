package org.jetbrains.dokka

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jetbrains.dokka.analysis.AnalysisEnvironment
import org.jetbrains.dokka.analysis.DokkaResolutionFacade
import org.jetbrains.dokka.model.Module
import org.jetbrains.dokka.pages.PlatformData
import org.jetbrains.dokka.pages.RootPageNode
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.DokkaPlugin
import org.jetbrains.dokka.utilities.DokkaLogger
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.utils.PathUtil
import java.io.File

/**
 * DokkaGenerator is the main entry point for generating documentation
 * [generate] method has been split into submethods for test reasons
 */
class DokkaGenerator(
    private val configuration: DokkaConfiguration,
    private val logger: DokkaLogger
) {
    fun generate() {
        logger.progress("Setting up analysis environments")
        val platforms: Map<PlatformData, EnvironmentAndFacade> = setUpAnalysis(configuration)

        logger.progress("Initializing plugins")
        val context = initializePlugins(configuration, logger, platforms)

        logger.progress("Creating documentation models")
        val modulesFromPlatforms = createDocumentationModels(platforms, context)

        logger.progress("Merging documentation models")
        val documentationModel = mergeDocumentationModels(modulesFromPlatforms, context)

        logger.progress("Transforming documentation model")
        val transformedDocumentation = transformDocumentationModel(documentationModel, context)

        logger.progress("Creating pages")
        val pages = createPages(transformedDocumentation, context)

        logger.progress("Transforming pages")
        val transformedPages = transformPages(pages, context)

        logger.progress("Rendering")
        render(transformedPages, context)

        logger.report()
    }

    fun setUpAnalysis(configuration: DokkaConfiguration): Map<PlatformData, EnvironmentAndFacade> =
        configuration.passesConfigurations.map {
            PlatformData(it.moduleName, it.analysisPlatform, it.targets) to createEnvironmentAndFacade(it)
        }.toMap()

    fun initializePlugins(
        configuration: DokkaConfiguration,
        logger: DokkaLogger,
        platforms: Map<PlatformData, EnvironmentAndFacade>,
        pluginOverrides: List<DokkaPlugin> = emptyList()
    ) = DokkaContext.create(configuration, logger, platforms, pluginOverrides)

    fun createDocumentationModels(
        platforms: Map<PlatformData, EnvironmentAndFacade>,
        context: DokkaContext
    ) = platforms.map { (pdata, _) -> translateDescriptors(pdata, context) } +
            platforms.map { (pdata, _)  -> translatePsi(pdata, context) }

    fun mergeDocumentationModels(
        modulesFromPlatforms: List<Module>,
        context: DokkaContext
    ) = context.single(CoreExtensions.documentableMerger).invoke(modulesFromPlatforms, context)

    fun transformDocumentationModel(
        documentationModel: Module,
        context: DokkaContext
    ) = context[CoreExtensions.documentationTransformer].fold(documentationModel) { acc, t -> t(acc, context) }

    fun createPages(
        transformedDocumentation: Module,
        context: DokkaContext
    ) = context.single(CoreExtensions.documentablesToPageTranslator).invoke(transformedDocumentation)

    fun transformPages(
        pages: RootPageNode,
        context: DokkaContext
    ) = context[CoreExtensions.pageTransformer].fold(pages) { acc, t -> t(acc) }

    fun render(
        transformedPages: RootPageNode,
        context: DokkaContext
    ) {
        val renderer = context.single(CoreExtensions.renderer)
        renderer.render(transformedPages)
    }

    private fun createEnvironmentAndFacade(pass: DokkaConfiguration.PassConfiguration): EnvironmentAndFacade =
        AnalysisEnvironment(DokkaMessageCollector(logger), pass.analysisPlatform).run {
            if (analysisPlatform == Platform.jvm) {
                addClasspath(PathUtil.getJdkClassesRootsFromCurrentJre())
            }
            pass.classpath.forEach { addClasspath(File(it)) }

            addSources(pass.sourceRoots.map { it.path })

            loadLanguageVersionSettings(pass.languageVersion, pass.apiVersion)

            val environment = createCoreEnvironment()
            val (facade, _) = createResolutionFacade(environment)
            EnvironmentAndFacade(environment, facade)
        }

    private fun translateDescriptors(platformData: PlatformData, context: DokkaContext): Module {
        val (environment, facade) = context.platforms.getValue(platformData)

        val packageFragments = environment.getSourceFiles().asSequence()
            .map { it.packageFqName }
            .distinct()
            .mapNotNull { facade.resolveSession.getPackageFragment(it) }
            .toList()

        return context.single(CoreExtensions.descriptorToDocumentationTranslator)
            .invoke(platformData.name, packageFragments, platformData)
    }

    private fun translatePsi(platformData: PlatformData, context: DokkaContext): Module {
        val (environment, _) = context.platforms.getValue(platformData)

        val sourceRoots = environment.configuration.get(CLIConfigurationKeys.CONTENT_ROOTS)
            ?.filterIsInstance<JavaSourceRoot>()
            ?.map { it.file }
                ?: listOf()
        val localFileSystem = VirtualFileManager.getInstance().getFileSystem("file")

        val psiFiles = sourceRoots.map { sourceRoot ->
            sourceRoot.absoluteFile.walkTopDown().mapNotNull {
                localFileSystem.findFileByPath(it.path)?.let { vFile ->
                    PsiManager.getInstance(environment.project).findFile(vFile) as? PsiJavaFile
                }
            }.toList()
        }.flatten()

        return context.single(CoreExtensions.psiToDocumentationTranslator)
            .invoke(platformData.name, psiFiles, platformData, context)

    }

    private class DokkaMessageCollector(private val logger: DokkaLogger) : MessageCollector {
        override fun clear() {
            seenErrors = false
        }

        private var seenErrors = false

        override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
            if (severity == CompilerMessageSeverity.ERROR) {
                seenErrors = true
            }
            logger.info(MessageRenderer.PLAIN_FULL_PATHS.render(severity, message, location))
        }

        override fun hasErrors() = seenErrors
    }
}

// It is not data class due to ill-defined equals
class EnvironmentAndFacade(val environment: KotlinCoreEnvironment, val facade: DokkaResolutionFacade) {
    operator fun component1() = environment
    operator fun component2() = facade
}