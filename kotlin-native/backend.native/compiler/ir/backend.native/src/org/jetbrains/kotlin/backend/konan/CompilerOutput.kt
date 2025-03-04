/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */
package org.jetbrains.kotlin.backend.konan

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf8
import llvm.*
import org.jetbrains.kotlin.backend.common.phaser.ActionState
import org.jetbrains.kotlin.backend.common.phaser.BeforeOrAfter
import org.jetbrains.kotlin.backend.common.serialization.KlibIrVersion
import org.jetbrains.kotlin.backend.konan.cexport.produceCAdapterBitcode
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.objc.patchObjCRuntimeModule
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.konan.CURRENT
import org.jetbrains.kotlin.konan.CompilerVersion
import org.jetbrains.kotlin.konan.file.isBitcode
import org.jetbrains.kotlin.konan.library.KONAN_STDLIB_NAME
import org.jetbrains.kotlin.konan.library.impl.buildLibrary
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.library.BaseKotlinLibrary
import org.jetbrains.kotlin.library.KotlinAbiVersion
import org.jetbrains.kotlin.library.KotlinLibraryVersioning
import org.jetbrains.kotlin.library.metadata.KlibMetadataVersion
import org.jetbrains.kotlin.library.uniqueName
import org.jetbrains.kotlin.utils.addToStdlib.ifNotEmpty

/**
 * Supposed to be true for a single LLVM module within final binary.
 */
val KonanConfig.isFinalBinary: Boolean get() = when (this.produce) {
    CompilerOutputKind.PROGRAM, CompilerOutputKind.DYNAMIC,
    CompilerOutputKind.STATIC -> true
    CompilerOutputKind.DYNAMIC_CACHE, CompilerOutputKind.STATIC_CACHE,
    CompilerOutputKind.LIBRARY, CompilerOutputKind.BITCODE -> false
    CompilerOutputKind.FRAMEWORK -> !omitFrameworkBinary
    else -> error("not supported: ${this.produce}")
}

val CompilerOutputKind.isNativeLibrary: Boolean
    get() = this == CompilerOutputKind.DYNAMIC || this == CompilerOutputKind.STATIC

val CompilerOutputKind.involvesBitcodeGeneration: Boolean
    get() = this != CompilerOutputKind.LIBRARY

internal val CacheDeserializationStrategy?.containsKFunctionImpl: Boolean
    get() = this?.contains(KonanFqNames.internalPackageName, "KFunctionImpl.kt") != false

internal val NativeGenerationState.shouldDefineFunctionClasses: Boolean
    get() = producedLlvmModuleContainsStdlib && cacheDeserializationStrategy.containsKFunctionImpl

internal val NativeGenerationState.shouldDefineCachedBoxes: Boolean
    get() = producedLlvmModuleContainsStdlib &&
            cacheDeserializationStrategy?.contains(KonanFqNames.internalPackageName, "Boxing.kt") != false

internal val NativeGenerationState.shouldLinkRuntimeNativeLibraries: Boolean
    get() = producedLlvmModuleContainsStdlib &&
            cacheDeserializationStrategy?.contains(KonanFqNames.packageName, "Runtime.kt") != false

val KonanConfig.involvesLinkStage: Boolean
    get() = when (this.produce) {
        CompilerOutputKind.PROGRAM, CompilerOutputKind.DYNAMIC,
        CompilerOutputKind.DYNAMIC_CACHE, CompilerOutputKind.STATIC_CACHE,
        CompilerOutputKind.STATIC -> true
        CompilerOutputKind.LIBRARY, CompilerOutputKind.BITCODE -> false
        CompilerOutputKind.FRAMEWORK -> !omitFrameworkBinary
        else -> error("not supported: ${this.produce}")
    }

val CompilerOutputKind.isCache: Boolean
    get() = this == CompilerOutputKind.STATIC_CACHE || this == CompilerOutputKind.DYNAMIC_CACHE

val KonanConfig.involvesCodegen: Boolean
    get() = produce != CompilerOutputKind.LIBRARY && !omitFrameworkBinary

internal fun llvmIrDumpCallback(state: ActionState, module: IrModuleFragment, context: Context) {
    module.let{}
    if (state.beforeOrAfter == BeforeOrAfter.AFTER && state.phase.name in context.configuration.getList(KonanConfigKeys.SAVE_LLVM_IR)) {
        val moduleName: String = memScoped {
            val sizeVar = alloc<size_tVar>()
            LLVMGetModuleIdentifier(context.generationState.llvm.module, sizeVar.ptr)!!.toKStringFromUtf8()
        }
        val output = context.generationState.tempFiles.create("$moduleName.${state.phase.name}", ".ll")
        if (LLVMPrintModuleToFile(context.generationState.llvm.module, output.absolutePath, null) != 0) {
            error("Can't dump LLVM IR to ${output.absolutePath}")
        }
    }
}

internal fun produceCStubs(generationState: NativeGenerationState) {
    generationState.cStubsManager.compile(
            generationState.config.clang,
            generationState.messageCollector,
            generationState.inVerbosePhase
    ).forEach {
        parseAndLinkBitcodeFile(generationState, generationState.llvm.module, it.absolutePath)
    }
}

private val BaseKotlinLibrary.isStdlib: Boolean
    get() = uniqueName == KONAN_STDLIB_NAME


private data class LlvmModules(
        val runtimeModules: List<LLVMModuleRef>,
        val additionalModules: List<LLVMModuleRef>
)

/**
 * Deserialize, generate, patch all bitcode dependencies and classify them into two sets:
 * - Runtime modules. These may be used as an input for a separate LTO (e.g. for debug builds).
 * - Everything else.
 */
private fun collectLlvmModules(generationState: NativeGenerationState, generatedBitcodeFiles: List<String>): LlvmModules {
    val config = generationState.config

    val (bitcodePartOfStdlib, bitcodeLibraries) = generationState.llvm.bitcodeToLink
            .partition { it.isStdlib && generationState.producedLlvmModuleContainsStdlib }
            .toList()
            .map { libraries ->
                libraries.flatMap { it.bitcodePaths }.filter { it.isBitcode }
            }

    val nativeLibraries = config.nativeLibraries + config.launcherNativeLibraries
            .takeIf { config.produce == CompilerOutputKind.PROGRAM }.orEmpty()
    val additionalBitcodeFilesToLink = generationState.llvm.additionalProducedBitcodeFiles
    val exceptionsSupportNativeLibrary = listOf(config.exceptionsSupportNativeLibrary)
            .takeIf { config.produce == CompilerOutputKind.DYNAMIC_CACHE }.orEmpty()
    val additionalBitcodeFiles = nativeLibraries +
            generatedBitcodeFiles +
            additionalBitcodeFilesToLink +
            bitcodeLibraries +
            exceptionsSupportNativeLibrary

    val runtimeNativeLibraries = config.runtimeNativeLibraries


    fun parseBitcodeFiles(files: List<String>): List<LLVMModuleRef> = files.map { bitcodeFile ->
        val parsedModule = parseBitcodeFile(generationState.llvmContext, bitcodeFile)
        if (!generationState.shouldUseDebugInfoFromNativeLibs()) {
            LLVMStripModuleDebugInfo(parsedModule)
        }
        parsedModule
    }

    val runtimeModules = parseBitcodeFiles(
            (runtimeNativeLibraries + bitcodePartOfStdlib)
                    .takeIf { generationState.shouldLinkRuntimeNativeLibraries }.orEmpty()
    )
    val additionalModules = parseBitcodeFiles(additionalBitcodeFiles)
    return LlvmModules(
            runtimeModules.ifNotEmpty { this + generationState.generateRuntimeConstantsModule() } ?: emptyList(),
            additionalModules + listOfNotNull(patchObjCRuntimeModule(generationState))
    )
}

private fun linkAllDependencies(generationState: NativeGenerationState, generatedBitcodeFiles: List<String>) {
    val (runtimeModules, additionalModules) = collectLlvmModules(generationState, generatedBitcodeFiles)
    // TODO: Possibly slow, maybe to a separate phase?
    val optimizedRuntimeModules = RuntimeLinkageStrategy.pick(generationState, runtimeModules).run()

    (optimizedRuntimeModules + additionalModules).forEach {
        val failed = llvmLinkModules2(generationState, generationState.llvm.module, it)
        if (failed != 0) {
            error("Failed to link ${it.getName()}")
        }
    }
}

internal fun insertAliasToEntryPoint(generationState: NativeGenerationState) {
    val config = generationState.config
    val nomain = config.configuration.get(KonanConfigKeys.NOMAIN) ?: false
    if (config.produce != CompilerOutputKind.PROGRAM || nomain)
        return
    val module = generationState.llvm.module
    val entryPointName = config.entryPointName
    val entryPoint = LLVMGetNamedFunction(module, entryPointName)
            ?: error("Module doesn't contain `$entryPointName`")
    LLVMAddAlias(module, LLVMTypeOf(entryPoint)!!, entryPoint, "main")
}

internal fun linkBitcodeDependencies(generationState: NativeGenerationState) {
    val config = generationState.config
    val tempFiles = generationState.tempFiles
    val produce = config.produce

    val generatedBitcodeFiles =
            if (produce == CompilerOutputKind.DYNAMIC || produce == CompilerOutputKind.STATIC) {
                produceCAdapterBitcode(
                        config.clang,
                        tempFiles.cAdapterCppName,
                        tempFiles.cAdapterBitcodeName)
                listOf(tempFiles.cAdapterBitcodeName)
            } else emptyList()
    if (produce == CompilerOutputKind.FRAMEWORK && config.produceStaticFramework) {
        embedAppleLinkerOptionsToBitcode(generationState.llvm, config)
    }
    linkAllDependencies(generationState, generatedBitcodeFiles)

}

internal fun produceOutput(generationState: NativeGenerationState) {
    val context = generationState.context
    val config = context.config
    val tempFiles = generationState.tempFiles
    val produce = config.produce
    if (produce == CompilerOutputKind.FRAMEWORK) {
        generationState.objCExport.produceFrameworkInterface()
        if (config.omitFrameworkBinary) {
            // Compiler does not compile anything in this mode, so return early.
            return
        }
    }
    when (produce) {
        CompilerOutputKind.STATIC,
        CompilerOutputKind.DYNAMIC,
        CompilerOutputKind.FRAMEWORK,
        CompilerOutputKind.DYNAMIC_CACHE,
        CompilerOutputKind.STATIC_CACHE,
        CompilerOutputKind.PROGRAM -> {
            val output = tempFiles.nativeBinaryFileName
            generationState.bitcodeFileName = output
            // Insert `_main` after pipeline so we won't worry about optimizations
            // corrupting entry point.
            insertAliasToEntryPoint(generationState)
            LLVMWriteBitcodeToFile(generationState.llvm.module, output)
        }
        CompilerOutputKind.LIBRARY -> {
            val nopack = config.configuration.getBoolean(KonanConfigKeys.NOPACK)
            val output = generationState.outputFiles.klibOutputFileName(!nopack)
            val libraryName = config.moduleId
            val shortLibraryName = config.shortModuleName
            val neededLibraries = context.librariesWithDependencies
            val abiVersion = KotlinAbiVersion.CURRENT
            val compilerVersion = CompilerVersion.CURRENT.toString()
            val libraryVersion = config.configuration.get(KonanConfigKeys.LIBRARY_VERSION)
            val metadataVersion = KlibMetadataVersion.INSTANCE.toString()
            val irVersion = KlibIrVersion.INSTANCE.toString()
            val versions = KotlinLibraryVersioning(
                abiVersion = abiVersion,
                libraryVersion = libraryVersion,
                compilerVersion = compilerVersion,
                metadataVersion = metadataVersion,
                irVersion = irVersion
            )
            val target = config.target
            val manifestProperties = config.manifestProperties

            if (!nopack) {
                val suffix = config.produce.suffix(target)
                if (!output.endsWith(suffix)) {
                    error("please specify correct output: packed: ${!nopack}, $output$suffix")
                }
            }

            val library = buildLibrary(
                    config.nativeLibraries,
                    config.includeBinaries,
                    neededLibraries,
                    context.serializedMetadata!!,
                    context.serializedIr,
                    versions,
                    target,
                    output,
                    libraryName,
                    nopack,
                    shortLibraryName,
                    manifestProperties,
                    context.dataFlowGraph)

            generationState.bitcodeFileName = library.mainBitcodeFileName
        }
        CompilerOutputKind.BITCODE -> {
            val output = generationState.outputFile
            generationState.bitcodeFileName = output
            LLVMWriteBitcodeToFile(generationState.llvm.module, output)
        }
        else -> error("not supported: $produce")
    }
}

private fun parseAndLinkBitcodeFile(generationState: NativeGenerationState, llvmModule: LLVMModuleRef, path: String) {
    val parsedModule = parseBitcodeFile(generationState.llvmContext, path)
    if (!generationState.shouldUseDebugInfoFromNativeLibs()) {
        LLVMStripModuleDebugInfo(parsedModule)
    }
    val failed = llvmLinkModules2(generationState, llvmModule, parsedModule)
    if (failed != 0) {
        throw Error("failed to link $path")
    }
}

private fun embedAppleLinkerOptionsToBitcode(llvm: Llvm, config: KonanConfig) {
    fun findEmbeddableOptions(options: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val iterator = options.iterator()
        loop@while (iterator.hasNext()) {
            val option = iterator.next()
            result += when {
                option.startsWith("-l") -> listOf(option)
                option == "-framework" && iterator.hasNext() -> listOf(option, iterator.next())
                else -> break@loop // Ignore the rest.
            }
        }
        return result
    }

    val optionsToEmbed = findEmbeddableOptions(config.platform.configurables.linkerKonanFlags) +
            llvm.allNativeDependencies.flatMap { findEmbeddableOptions(it.linkerOpts) }

    embedLlvmLinkOptions(llvm.llvmContext, llvm.module, optionsToEmbed)
}
