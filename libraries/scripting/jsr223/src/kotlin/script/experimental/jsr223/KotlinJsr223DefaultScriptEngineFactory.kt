/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.script.experimental.jsr223

import org.jetbrains.kotlin.cli.common.repl.KotlinJsr223JvmScriptEngineFactoryBase
import java.io.File
import javax.script.ScriptEngine
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.createJvmEvaluationConfigurationFromTemplate
import kotlin.script.experimental.jvmhost.jsr223.KotlinJsr223ScriptEngineImpl

const val JAR_COLLECTIONS_DISABLE_UNPACK_CACHE_PROPERTY = "kotlin.script.disable.jar.collections.unpack.cache"
const val JAR_COLLECTIONS_UNPACK_CACHE_DIR_PROPERTY = "kotlin.script.jar.collections.unpack.cache.path"

class KotlinJsr223DefaultScriptEngineFactory : KotlinJsr223JvmScriptEngineFactoryBase() {

    private val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<KotlinJsr223DefaultScript>()
    private val evaluationConfiguration = createJvmEvaluationConfigurationFromTemplate<KotlinJsr223DefaultScript>()

    override fun getScriptEngine(): ScriptEngine {
        val unpackedJarCollectionsCache =
            System.getProperty(JAR_COLLECTIONS_UNPACK_CACHE_DIR_PROPERTY)?.let(::File)
                ?: unpackedJarCollectionsCache

        return KotlinJsr223ScriptEngineImpl(
            this,
            ScriptCompilationConfiguration(compilationConfiguration) {
                jvm {
                    dependenciesFromCurrentContext(wholeClasspath = true, unpackJarCollectionsTo = unpackedJarCollectionsCache)
                }
            },
            evaluationConfiguration
        )
    }

    companion object {
        val unpackedJarCollectionsCache: File? by lazy {
            if (System.getProperty(JAR_COLLECTIONS_DISABLE_UNPACK_CACHE_PROPERTY)?.toLowerCase() == "true") null
            else createTempDir("unpackedJarCache").canonicalFile.also {
                Runtime.getRuntime().addShutdownHook(Thread {
                    it.deleteRecursively()
                })
            }
        }
    }
}

