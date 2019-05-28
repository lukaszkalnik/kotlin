/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.jvm.util

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.lang.IllegalStateException
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarInputStream
import kotlin.reflect.KClass
import kotlin.script.experimental.jvm.impl.toFile
import kotlin.script.experimental.jvm.impl.tryGetResourcePathForClass
import kotlin.script.experimental.jvm.impl.tryGetResourcePathForClassByName
import kotlin.script.templates.standard.ScriptTemplateWithArgs

// TODO: consider moving all these utilites to the build-common or some other shared compiler API module

// Kotlin Compiler dependencies
internal const val KOTLIN_JAVA_STDLIB_JAR = "kotlin-stdlib.jar"
internal const val KOTLIN_JAVA_REFLECT_JAR = "kotlin-reflect.jar"
internal const val KOTLIN_JAVA_SCRIPT_RUNTIME_JAR = "kotlin-script-runtime.jar"
internal const val TROVE4J_JAR = "trove4j.jar"
internal const val KOTLIN_SCRIPTING_COMPILER_JAR = "kotlin-scripting-compiler.jar"
internal const val KOTLIN_SCRIPTING_COMPILER_EMBEDDABLE_JAR = "kotlin-scripting-compiler-embeddable.jar"
internal const val KOTLIN_SCRIPTING_COMPILER_IMPL_JAR = "kotlin-scripting-compiler-impl.jar"
internal const val KOTLIN_SCRIPTING_COMPILER_IMPL_EMBEDDABLE_JAR = "kotlin-scripting-compiler-impl-embeddable.jar"
internal const val KOTLIN_SCRIPTING_COMMON_JAR = "kotlin-scripting-common.jar"
internal const val KOTLIN_SCRIPTING_JVM_JAR = "kotlin-scripting-jvm.jar"

internal const val KOTLIN_COMPILER_NAME = "kotlin-compiler"
internal const val KOTLIN_COMPILER_JAR = "$KOTLIN_COMPILER_NAME.jar"

private val JAR_COLLECTIONS_CLASSES_PATHS = arrayOf("BOOT-INF/classes", "WEB-INF/classes")
private val JAR_COLLECTIONS_LIB_PATHS = arrayOf("BOOT-INF/lib", "WEB-INF/lib")
private val JAR_COLLECTIONS_KEY_PATHS = JAR_COLLECTIONS_CLASSES_PATHS + JAR_COLLECTIONS_LIB_PATHS
private const val JAR_MANIFEST_RESOURCE_NAME = "/META-INF/MANIFEST.MF"

internal const val KOTLIN_SCRIPT_CLASSPATH_PROPERTY = "kotlin.script.classpath"
internal const val KOTLIN_COMPILER_CLASSPATH_PROPERTY = "kotlin.compiler.classpath"
internal const val KOTLIN_COMPILER_JAR_PROPERTY = "kotlin.compiler.jar"
internal const val KOTLIN_STDLIB_JAR_PROPERTY = "kotlin.java.stdlib.jar"
internal const val KOTLIN_REFLECT_JAR_PROPERTY = "kotlin.java.reflect.jar"
// obsolete name, but maybe still used in the wild
// TODO: consider removing
internal const val KOTLIN_RUNTIME_JAR_PROPERTY = "kotlin.java.runtime.jar"
internal const val KOTLIN_SCRIPT_RUNTIME_JAR_PROPERTY = "kotlin.script.runtime.jar"

internal const val JAR_COLLECTIONS_UNPACK_CACHE_LOCK_TIMEOUT_MS_PROPERTY = "kotlin.script.jar.collections.unpack.cache.lock.timeout.ms"
internal const val JAR_COLLECTIONS_UNPACK_CACHE_LOCK_TIMEOUT_MS_DEFAULT = 10000L
internal const val JAR_COLLECTIONS_UNPACK_CACHE_LOCK_TIMEOUT_MS_MAX = 600000L

private val validClasspathFilesExtensions = setOf("jar", "zip", "java")
private val validJarCollectionFilesExtensions = setOf("jar", "war", "zip")

fun classpathFromClassloader(currentClassLoader: ClassLoader, unpackJarCollectionsTo: File? = null): List<File>? {
    return allRelatedClassLoaders(currentClassLoader).flatMap { classLoader ->
        when {
            unpackJarCollectionsTo != null && JAR_COLLECTIONS_KEY_PATHS.any { classLoader.getResource(it)?.file?.isNotEmpty() == true } -> {
                classLoader.unpackJarCollections(unpackJarCollectionsTo)
            }
            classLoader is URLClassLoader -> {
                classLoader.urLs.asSequence().mapNotNull { it.toValidClasspathFileOrNull() }
            }
            else -> {
                classLoader.classPathUrlsFromGetUrlsMethod()
                    ?: classLoader.classPathUrlsFromResources()
            }
        } ?: emptySequence()
    }.distinct().toList().takeIf { it.isNotEmpty() }
}

internal fun URL.toValidClasspathFileOrNull(): File? = toFile()?.takeIf { it.isValidClasspathFile() }

internal fun File.isValidClasspathFile(): Boolean =
    isDirectory || (isFile && validClasspathFilesExtensions.any { extension == it })

private fun ClassLoader.classPathUrlsFromGetUrlsMethod(): Sequence<File>? {
    return try {
        // e.g. for IDEA platform UrlClassLoader
        val getUrls = this::class.java.getMethod("getUrls")
        getUrls.isAccessible = true
        val result = getUrls.invoke(this) as? List<Any?>
        result?.asSequence()?.filterIsInstance<URL>()?.mapNotNull { it.toValidClasspathFileOrNull() }
    } catch (e: Throwable) {
        null
    }
}

internal fun ClassLoader.classPathUrlsFromResources(): Sequence<File>? =
    getResources(JAR_MANIFEST_RESOURCE_NAME)
        .asSequence().distinct().mapNotNull { it.toValidClasspathFileOrNull() }.takeIf { it.any() }

private fun ClassLoader.unpackJarCollections(cacheDir: File): Sequence<File>? {
    val jars = JAR_COLLECTIONS_KEY_PATHS.asSequence().flatMap { getResources(it).asSequence() }.distinct()
        .mapNotNull { it.toFile()?.takeIf { file -> validJarCollectionFilesExtensions.any { ext -> file.extension == ext } } }
    return jars.flatMap { it.unpackJarCollection(cacheDir) }.filter { it.isValidClasspathFile() }.takeIf { it.any() }
}

private fun File.getCollectionCacheDirectoryName(): String =
    "${nameWithoutExtension}_${canonicalPath.hashCode()}_${length()}_${lastModified()}"

private fun File.acquireLockFile(timeout: Long, body: File.() -> Boolean) {
    val startTime = System.currentTimeMillis()
    while (true) {
        if (body()) return
        if (System.currentTimeMillis() - startTime >= timeout) break
        Thread.sleep(100)
    }
    throw IllegalStateException("Jar collections unpacking lock timeout (${System.currentTimeMillis() - startTime}ms) on file: $canonicalPath")

}

private fun File.unpackJarCollection(cacheDir: File): Sequence<File> {
    val targetName = getCollectionCacheDirectoryName()
    val targetDir = File(cacheDir, targetName)
    val markerFile = File(cacheDir, "$targetName.cached")
    val lockFile = File(cacheDir, "$targetName.lock")
    val lockTimeout =
        System.getProperty(JAR_COLLECTIONS_UNPACK_CACHE_LOCK_TIMEOUT_MS_PROPERTY)?.toLongOrNull()
            ?.takeIf { it in 0..JAR_COLLECTIONS_UNPACK_CACHE_LOCK_TIMEOUT_MS_MAX }
            ?: JAR_COLLECTIONS_UNPACK_CACHE_LOCK_TIMEOUT_MS_DEFAULT
    var isError = false
    lockFile.acquireLockFile(lockTimeout) { !exists() }
    return if (targetDir.isDirectory && markerFile.exists()) {
        JAR_COLLECTIONS_CLASSES_PATHS.asSequence().mapNotNull { classesDir ->
            File(targetDir, classesDir).takeIf { it.isDirectory }
        } + JAR_COLLECTIONS_LIB_PATHS.asSequence().flatMap {
            File(targetDir, it).listFiles().orEmpty().asSequence()
        }
    } else try {
        lockFile.apply {
            acquireLockFile(lockTimeout) { createNewFile() }
            deleteOnExit()
        }
        ArrayList<File>().apply {
            JarInputStream(FileInputStream(this@unpackJarCollection)).use { jarInputStream ->
                for (classesDir in JAR_COLLECTIONS_CLASSES_PATHS) {
                    add(File(targetDir, classesDir))
                }
                do {
                    val entry = jarInputStream.nextJarEntry
                    if (entry != null) {
                        try {
                            if (!entry.isDirectory) {
                                val file = File(targetDir, entry.name)
                                if (JAR_COLLECTIONS_LIB_PATHS.any { entry.name.startsWith(it) }) {
                                    add(file)
                                }
                                file.parentFile.mkdirs()
                                file.outputStream().use { outputStream ->
                                    jarInputStream.copyTo(outputStream)
                                    outputStream.flush()
                                }
                            }
                        } finally {
                            jarInputStream.closeEntry()
                        }
                    }
                } while (entry != null)
            }
        }.asSequence()
    } catch (e: Throwable) {
        isError = true
        targetDir.deleteRecursively()
        throw e
    } finally {
        lockFile.delete()
        if (!isError) markerFile.createNewFile()
    }
}

fun classpathFromClasspathProperty(): List<File>? =
        System.getProperty("java.class.path")
                ?.split(String.format("\\%s", File.pathSeparatorChar).toRegex())
                ?.dropLastWhile(String::isEmpty)
                ?.map(::File)

fun classpathFromClass(classLoader: ClassLoader, klass: KClass<out Any>): List<File>? =
    classpathFromFQN(classLoader, klass.qualifiedName!!)

fun classpathFromFQN(classLoader: ClassLoader, fqn: String): List<File>? {
    val clp = "${fqn.replace('.', '/')}.class"
    val url = classLoader.getResource(clp)
    return url?.toURI()?.path?.removeSuffix(clp)?.let {
        listOf(File(it))
    }
}

fun File.matchMaybeVersionedFile(baseName: String) =
        name == baseName ||
        name == baseName.removeSuffix(".jar") || // for classes dirs
        Regex(Regex.escape(baseName.removeSuffix(".jar")) + "(-\\d.*)?\\.jar").matches(name)

fun File.hasParentNamed(baseName: String): Boolean =
    nameWithoutExtension == baseName || parentFile?.hasParentNamed(baseName) ?: false

private const val KOTLIN_COMPILER_EMBEDDABLE_JAR = "$KOTLIN_COMPILER_NAME-embeddable.jar"

private fun allRelatedClassLoaders(clsLoader: ClassLoader, visited: MutableSet<ClassLoader> = HashSet()): Sequence<ClassLoader> {
    if (!visited.add(clsLoader)) return emptySequence()

    val singleParent = clsLoader.parent
    if (singleParent != null)
        return sequenceOf(clsLoader) + sequenceOf(singleParent).flatMap { allRelatedClassLoaders(it, visited) }

    return try {
        val field = clsLoader.javaClass.getDeclaredField("myParents") // com.intellij.ide.plugins.cl.PluginClassLoader
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val arrayOfClassLoaders = field.get(clsLoader) as Array<ClassLoader>
        sequenceOf(clsLoader) + arrayOfClassLoaders.asSequence().flatMap { allRelatedClassLoaders(it, visited) }
    } catch (e: Throwable) {
        sequenceOf(clsLoader)
    }
}


internal fun List<File>.takeIfContainsAll(vararg keyNames: String): List<File>? =
        takeIf { classpath ->
            keyNames.all { key -> classpath.any { it.matchMaybeVersionedFile(key) } }
        }

internal fun List<File>.filterIfContainsAll(vararg keyNames: String): List<File>? {
    val res = hashMapOf<String, File>()
    for (cpentry in this) {
        for (prefix in keyNames) {
            if (!res.containsKey(prefix) &&
                (cpentry.matchMaybeVersionedFile(prefix) || (cpentry.isDirectory && cpentry.hasParentNamed(prefix)))
            ) {
                res[prefix] = cpentry
                break
            }
        }
    }
    return if (keyNames.all { res.containsKey(it) }) res.values.toList()
    else null
}

internal fun List<File>.takeIfContainsAny(vararg keyNames: String): List<File>? =
        takeIf { classpath ->
            keyNames.any { key -> classpath.any { it.matchMaybeVersionedFile(key) } }
        }

fun scriptCompilationClasspathFromContextOrNull(
    vararg keyNames: String,
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    wholeClasspath: Boolean = false,
    unpackJarCollectionsTo: File? = null
): List<File>? {
    fun List<File>.takeAndFilter() = when {
        isEmpty() -> null
        wholeClasspath -> takeIfContainsAll(*keyNames)
        else -> filterIfContainsAll(*keyNames)
    }

    val fromProperty = System.getProperty(KOTLIN_SCRIPT_CLASSPATH_PROPERTY)?.split(File.pathSeparator)?.map(::File)
    if (fromProperty != null) return fromProperty

    return classpathFromClassloader(classLoader, unpackJarCollectionsTo)?.takeAndFilter()
        ?: classpathFromClasspathProperty()?.takeAndFilter()
}



fun scriptCompilationClasspathFromContextOrStdlib(
    vararg keyNames: String,
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    wholeClasspath: Boolean = false
): List<File> =
    scriptCompilationClasspathFromContextOrNull(
        *keyNames,
        classLoader = classLoader,
        wholeClasspath = wholeClasspath
    )
            ?: KotlinJars.kotlinScriptStandardJars

fun scriptCompilationClasspathFromContext(
    vararg keyNames: String,
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    wholeClasspath: Boolean = false,
    unpackJarCollectionsTo: File? = null
): List<File> =
    scriptCompilationClasspathFromContextOrNull(
        *keyNames,
        classLoader = classLoader,
        wholeClasspath = wholeClasspath
    )
            ?: throw Exception("Unable to get script compilation classpath from context, please specify explicit classpath via \"$KOTLIN_SCRIPT_CLASSPATH_PROPERTY\" property")

object KotlinJars {

    private val explicitCompilerClasspath: List<File>? by lazy {
        System.getProperty(KOTLIN_COMPILER_CLASSPATH_PROPERTY)?.split(File.pathSeparator)?.map(::File)
        ?: System.getProperty(KOTLIN_COMPILER_JAR_PROPERTY)?.let(::File)?.takeIf(File::exists)?.let { listOf(it) }
    }

    val compilerClasspath: List<File> by lazy {
        findCompilerClasspath(withScripting = false)
    }

    val compilerWithScriptingClasspath: List<File> by lazy {
        findCompilerClasspath(withScripting = true)
    }

    private fun findCompilerClasspath(withScripting: Boolean): List<File> {
        val kotlinCompilerJars = listOf(
            KOTLIN_COMPILER_JAR,
            KOTLIN_COMPILER_EMBEDDABLE_JAR
        )
        val kotlinLibsJars = listOf(
            KOTLIN_JAVA_STDLIB_JAR,
            KOTLIN_JAVA_REFLECT_JAR,
            KOTLIN_JAVA_SCRIPT_RUNTIME_JAR,
            TROVE4J_JAR
        )
        val kotlinScriptingJars = if (withScripting) listOf(
            KOTLIN_SCRIPTING_COMPILER_JAR,
            KOTLIN_SCRIPTING_COMPILER_EMBEDDABLE_JAR,
            KOTLIN_SCRIPTING_COMPILER_IMPL_JAR,
            KOTLIN_SCRIPTING_COMPILER_IMPL_EMBEDDABLE_JAR,
            KOTLIN_SCRIPTING_COMMON_JAR,
            KOTLIN_SCRIPTING_JVM_JAR
        ) else emptyList()

        val kotlinBaseJars = kotlinCompilerJars + kotlinLibsJars + kotlinScriptingJars

        val classpath = explicitCompilerClasspath
        // search classpath from context classloader and `java.class.path` property
            ?: (classpathFromFQN( Thread.currentThread().contextClassLoader, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler" )
                ?: classpathFromClassloader(Thread.currentThread().contextClassLoader)?.takeIf { it.isNotEmpty() }
                ?: classpathFromClasspathProperty()
                    )?.filter { f -> kotlinBaseJars.any { f.matchMaybeVersionedFile(it) } }?.takeIf { it.isNotEmpty() }
        // if autodetected, additionally check for presence of the compiler jars
        if (classpath == null || (explicitCompilerClasspath == null && classpath.none { f ->
                kotlinCompilerJars.any {
                    f.matchMaybeVersionedFile(
                        it
                    )
                }
            })) {
            throw FileNotFoundException("Cannot find kotlin compiler jar, set kotlin.compiler.classpath property to proper location")
        }
        return classpath
    }

    fun getLib(propertyName: String, jarName: String, markerClass: KClass<*>, classLoader: ClassLoader? = null): File? =
        getExplicitLib(propertyName, jarName)
            ?: run {
                val requestedClassloader = classLoader ?: Thread.currentThread().contextClassLoader
                val byName =
                    if (requestedClassloader == markerClass.java.classLoader) null
                    else tryGetResourcePathForClassByName(markerClass.java.name, requestedClassloader)
                byName ?: tryGetResourcePathForClass(markerClass.java)
            }?.takeIf(File::exists)

    fun getLib(propertyName: String, jarName: String, markerClassName: String, classLoader: ClassLoader? = null): File? =
        getExplicitLib(propertyName, jarName)
            ?: tryGetResourcePathForClassByName(
                markerClassName, classLoader ?: Thread.currentThread().contextClassLoader
            )?.takeIf(File::exists)

    private fun getExplicitLib(propertyName: String, jarName: String) =
        System.getProperty(propertyName)?.let(::File)?.takeIf(File::exists)
            ?: explicitCompilerClasspath?.firstOrNull { it.matchMaybeVersionedFile(jarName) }?.takeIf(File::exists)

    val stdlibOrNull: File? by lazy {
        System.getProperty(KOTLIN_STDLIB_JAR_PROPERTY)?.let(::File)?.takeIf(File::exists)
                ?: getLib(
                    KOTLIN_RUNTIME_JAR_PROPERTY,
                    KOTLIN_JAVA_STDLIB_JAR,
                    JvmStatic::class
                )
    }

    val stdlib: File by lazy {
        stdlibOrNull
                ?: throw Exception("Unable to find kotlin stdlib, please specify it explicitly via \"$KOTLIN_STDLIB_JAR_PROPERTY\" property")
    }

    val reflectOrNull: File? by lazy {
        getLib(
            KOTLIN_REFLECT_JAR_PROPERTY,
            KOTLIN_JAVA_REFLECT_JAR,
            "kotlin.reflect.full.KClasses" // using a class that is a part of the kotlin-reflect.jar
        )
    }

    val scriptRuntimeOrNull: File? by lazy {
        getLib(
            KOTLIN_SCRIPT_RUNTIME_JAR_PROPERTY,
            KOTLIN_JAVA_SCRIPT_RUNTIME_JAR,
            ScriptTemplateWithArgs::class
        )
    }

    val scriptRuntime: File by lazy {
        scriptRuntimeOrNull
                ?: throw Exception("Unable to find kotlin script runtime, please specify it explicitly via \"$KOTLIN_SCRIPT_RUNTIME_JAR_PROPERTY\" property")
    }

    val kotlinScriptStandardJars get() = listOf(
        stdlibOrNull,
        scriptRuntimeOrNull
    ).filterNotNull()
}
