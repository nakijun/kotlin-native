/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan

import llvm.* // TODO: extract LLVM things to other module?
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.backend.konan.llvm.KtBcMetadataReader
import org.jetbrains.kotlin.backend.konan.llvm.MetadataReader
import org.jetbrains.kotlin.backend.konan.llvm.MetadataGenerator
import org.jetbrains.kotlin.backend.konan.llvm.SplitMetadataGenerator
import org.jetbrains.kotlin.backend.konan.llvm.SplitMetadataReader
import org.jetbrains.kotlin.backend.konan.llvm.NamedModuleData
import org.jetbrains.kotlin.backend.konan.llvm.parseBitcodeFile
import org.jetbrains.kotlin.backend.konan.llvm.createLlvmDeclarations
import org.jetbrains.kotlin.backend.konan.TargetManager
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.backend.konan.serialization.Base64
import org.jetbrains.kotlin.backend.konan.serialization.deserializeModule
import java.io.File
import java.nio.file.Files

class LinkData(
    val abiVersion: Int,
    val module: String,
    val moduleName: String,
    val fragments: List<String>,
    val fragmentNames: List<String> )

interface KonanLibraryReader {
    val libraryName: String
    val moduleName: String
    val moduleDescriptor: ModuleDescriptorImpl
    val bitcodePaths: List<String>
    //val linkData: LinkData?
}

abstract class FileBasedLibraryReader(
    val file: File, 
    val configuration: CompilerConfiguration, 
    val reader: MetadataReader): KonanLibraryReader {

    init {
        if (!file.exists()) 
            error("Path '" + file.path + "' does not exist")
    }

    override val libraryName: String
        get() = file.path

    protected val namedModuleData by lazy {
        val currentAbiVersion = configuration.get(KonanConfigKeys.ABI_VERSION)!!
        reader.loadSerializedModule(currentAbiVersion)
    }

    override val moduleName = namedModuleData.name

    protected val tableOfContentsAsString = namedModuleData.base64

    protected fun packageMetadata(fqName: String): Base64 =
        reader.loadSerializedPackageFragment(fqName)

    override val moduleDescriptor: ModuleDescriptorImpl by lazy {
        deserializeModule(configuration, 
            {it -> packageMetadata(it)}, 
            tableOfContentsAsString, moduleName)
    }
}

class KtBcLibraryReader(file: File, configuration: CompilerConfiguration) 
    : FileBasedLibraryReader(file, configuration, KtBcMetadataReader(file)) {

    public constructor(path: String, configuration: CompilerConfiguration) : this(File(path), configuration) 

    override val bitcodePaths: List<String>
        get() = listOf(libraryName)

}

// TODO: Get rid of the configuration here.
class SplitLibraryReader(file: File, configuration: CompilerConfiguration) 
    : FileBasedLibraryReader(file, configuration, SplitMetadataReader(file)) {

    public constructor(path: String, configuration: CompilerConfiguration) : this(File(path), configuration) 

    private val File.dirAbsolutePaths: List<String>
        get() = this.listFiles()!!.toList()!!.map{it->it.absolutePath}

    private val targetDir: File
        get() {
            // TODO: Make it a function
            // TODO: make something about it here.
            val target = configuration.get(KonanConfigKeys.TARGET) ?: TargetManager.host.name.toLowerCase()
            val dir = File(file, target)
            println(dir)
            return dir
        }

    override val bitcodePaths: List<String>
        get() = File(targetDir, "kotlin").dirAbsolutePaths + 
                File(targetDir, "native").dirAbsolutePaths

}
/* ------------ writer part ----------------*/

interface KonanLibraryWriter {

    fun addLinkData(linkData: LinkData)
    fun addNativeBitcode(library: String)
    fun addKotlinBitcode(llvmModule: LLVMModuleRef)
    fun commit()
}

abstract class FileBasedLibraryWriter (
    val file: File
    ): KonanLibraryWriter {
}

class KtBcLibraryWriter(file: File, val llvmModule: LLVMModuleRef) 
    : FileBasedLibraryWriter(file) {

    public constructor(path: String, llvmModule: LLVMModuleRef) 
        : this(File(path), llvmModule)

    override fun addKotlinBitcode(llvmModule: LLVMModuleRef) {
        // This is a noop for .kt.bc based libraries,
        // because the bitcode itself is the container.
    }

    override fun addLinkData(linkData: LinkData) {
        MetadataGenerator(llvmModule).addLinkData(linkData)
    }

    override fun addNativeBitcode(library: String) {

        val libraryModule = parseBitcodeFile(library)
        val failed = LLVMLinkModules2(llvmModule, libraryModule)
        if (failed != 0) {
            throw Error("failed to link $library") // TODO: retrieve error message from LLVM.
        }
    }

    override fun commit() {
        LLVMWriteBitcodeToFile(llvmModule, file.path)
    }

    companion object {
        fun mainBitcodeFile(library: File): File = library
    }
}

class SplitLibraryWriter(file: File, val target: String): FileBasedLibraryWriter(file) {
    public constructor(path: String, target: String): this(File(path), target)

    val kotlinDir = kotlinDir(file, target)
    val targetDir = targetDir(file, target)
    val linkdataDir = File(file, "linkdata")
    val nativeDir = File(targetDir, "native")
    val resourcesDir = File(file, "resources")
    // TODO: Experiment with separate bitcode files.
    // Per package or per class.
    val kotlinBitcode = kotlinBitcode(file, target)

    init {
        // TODO: figure out the proper policy here.
        file.deleteRecursively()
        file.mkdirs()
        kotlinDir.mkdirs()
        linkdataDir.mkdirs()
        nativeDir.mkdirs()
        resourcesDir.mkdirs()
    }

    var llvmModule: LLVMModuleRef? = null

    override fun addKotlinBitcode(llvmModule: LLVMModuleRef) {
        this.llvmModule = llvmModule
        LLVMWriteBitcodeToFile(llvmModule, kotlinBitcode.path)
    }

    override fun addLinkData(linkData: LinkData) {
        SplitMetadataGenerator(linkdataDir).addLinkData(linkData)
    }

    override fun addNativeBitcode(library: String) {
        val basename = File(library).getName()
        Files.copy(File(library).toPath(), File(nativeDir, basename).toPath()) 
    }

    override fun commit() {
        // This is no-op for the Split library.
        // Or should we zip the directory?
    }

    companion object {
        fun targetDir(library: File, target: String) = File(library, target)

        fun kotlinDir(library: File, target: String) = File(targetDir(library, target), "kotlin")

        fun kotlinBitcode(library: File, target: String)
            = File(kotlinDir(library, target), "program.kt.bc")

        fun mainBitcodeFile(libraryName: String, target: String) 
            = kotlinBitcode(File(libraryName), target).path
    }
}

