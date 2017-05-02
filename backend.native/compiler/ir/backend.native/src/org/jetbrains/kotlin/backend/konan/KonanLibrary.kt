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
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.backend.konan.serialization.Base64
import org.jetbrains.kotlin.backend.konan.serialization.deserializeModule
import java.io.File

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

class SplitLibraryReader(file: File, configuration: CompilerConfiguration) 
    : FileBasedLibraryReader(file, configuration, SplitMetadataReader(file)) {

    public constructor(path: String, configuration: CompilerConfiguration) : this(File(path), configuration) 

    private val File.dirAbsolutePaths: List<String>
        get() = this.listFiles().toList().map{it->it.absolutePath}

    override val bitcodePaths: List<String>
        get() = File(file, "kotlin").dirAbsolutePaths + File(file, "native").dirAbsolutePaths

}
/* ------------ writer part ----------------*/

interface KonanLibraryWriter {

    fun addLinkData(linkData: LinkData)
    fun addNativeBitcode(library: String)
    fun addKotlinBitcode(llvmModule: LLVMModuleRef)
    fun commit()
}

internal abstract class FileBasedLibraryWriter (
    val context: Context,
    val file: File
    ): KonanLibraryWriter {
}

// Eliminate any dependence on the context
internal class KtBcLibraryWriter(context: Context, file: File, val llvmModule: LLVMModuleRef) 
    : FileBasedLibraryWriter(context, file) {

    public constructor(context: Context, path: String, llvmModule: LLVMModuleRef) 
        : this(context, File(path), llvmModule)

    override fun addKotlinBitcode(llvmModule: LLVMModuleRef) {
        // This is a noop for .kt.bc based libraries,
        // because the bitcode itself is the container.
        assert(llvmModule == context.llvmModule)
    }

    override fun addLinkData(linkData: LinkData) {
        MetadataGenerator(context).addLinkData(linkData)
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
}

internal class SplitLibraryWriter(context: Context, file: File): FileBasedLibraryWriter(context, file) {
    public constructor(context: Context, path: String): this(context, File(path))

    val kotlinDir = File(file, "kotlin")
    val linkdataDir = File(file, "linkdata")
    val nativeDir = File(file, "native")
    val resourcesDir = File(file, "resources")
    // TODO: Experiment with separate bitcode files.
    // Per package or per class.
    val kotlinBitcode = File(kotlinDir, "kotlin.bc")

    init {
        // TODO: figure out the proper policy here.
        file.delete()
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
        SplitMetadataGenerator(context, linkdataDir).addLinkData(linkData)
    }

    override fun addNativeBitcode(library: String) {

        val libraryModule = parseBitcodeFile(library)
        val failed = LLVMLinkModules2(llvmModule!!, libraryModule)
        if (failed != 0) {
            throw Error("failed to link $library") // TODO: retrieve error message from LLVM.
        }
    }

    override fun commit() {
        // This is no-op for the Split library.
    }

}

