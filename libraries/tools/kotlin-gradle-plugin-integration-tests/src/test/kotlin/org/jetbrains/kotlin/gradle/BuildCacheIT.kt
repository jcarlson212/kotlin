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

package org.jetbrains.kotlin.gradle

import org.jetbrains.kotlin.gradle.util.modify
import org.junit.Test
import java.io.File

class BuildCacheIT : BaseGradleIT() {
    override fun defaultBuildOptions(): BuildOptions =
        super.defaultBuildOptions().copy(withBuildCache = true)

    companion object {
        private val GRADLE_VERSION = GradleVersionRequired.None
    }

    @Test
    fun testKotlinCachingEnabledFlag() = with(Project("simpleProject", GRADLE_VERSION)) {
        prepareLocalBuildCache()

        build("assemble") {
            assertSuccessful()
            assertTaskPackedToCache(":compileKotlin")
        }

        build("clean", "assemble", "-Dkotlin.caching.enabled=false") {
            assertSuccessful()
            assertNotContains(":compileKotlin FROM-CACHE")
        }
    }

    @Test
    fun testCacheHitAfterClean() = with(Project("simpleProject", GRADLE_VERSION)) {
        prepareLocalBuildCache()

        build("assemble") {
            assertSuccessful()
            assertTaskPackedToCache(":compileKotlin")
        }
        build("clean", "assemble") {
            assertSuccessful()
            assertContains(":compileKotlin FROM-CACHE")
            assertContains(":compileJava FROM-CACHE")
        }
    }

    @Test
    fun testCacheHitAfterCacheHit() = with(Project("simpleProject", GRADLE_VERSION)) {
        prepareLocalBuildCache()

        build("assemble") {
            assertSuccessful()
            // Should store the output into the cache:
            assertTaskPackedToCache(":compileKotlin")
        }

        val sourceFile = File(projectDir, "src/main/kotlin/helloWorld.kt")
        val originalSource: String = sourceFile.readText()
        val modifiedSource: String = originalSource.replace(" and ", " + ")
        sourceFile.writeText(modifiedSource)

        build("assemble") {
            assertSuccessful()
            assertTaskPackedToCache(":compileKotlin")
        }

        sourceFile.writeText(originalSource)

        build("assemble") {
            assertSuccessful()
            // Should load the output from cache:
            assertContains(":compileKotlin FROM-CACHE")
        }

        sourceFile.writeText(modifiedSource)

        build("assemble") {
            assertSuccessful()
            // And should load the output from cache again, without compilation:
            assertContains(":compileKotlin FROM-CACHE")
        }
    }

    @Test
    fun testKotlinCompileIncrementalBuildWithoutRelocation() = with(Project("buildCacheSimple", GRADLE_VERSION)) {
        prepareLocalBuildCache()

        checkKotlinCompileCachingIncrementalBuild(projectDir, this)
    }

    @Test
    fun testKotlinCompileCachingIncrementalBuildWithRelocation() {
        with(Project("buildCacheSimple", GRADLE_VERSION)) {
            prepareLocalBuildCache()

            // Copy the project to a different directory
            val copyProjectParentDir = projectDir.resolveSibling("copy_${projectDir.name}").also { it.mkdirs() }
            copyRecursively(projectDir, copyProjectParentDir)

            checkKotlinCompileCachingIncrementalBuild(copyProjectParentDir.resolve(projectName), this)
        }
    }

    @Test
    fun testKaptCachingIncrementalBuildWithoutRelocation() {
        with(Project("kaptAvoidance", GRADLE_VERSION, directoryPrefix = "kapt2")) {
            prepareLocalBuildCache()

            checkKaptCachingIncrementalBuild(projectDir, this)
        }
    }

    @Test
    fun testKaptCachingIncrementalBuildWithRelocation() {
        with(Project("kaptAvoidance", GRADLE_VERSION, directoryPrefix = "kapt2")) {
            prepareLocalBuildCache()

            // Copy the project to a different directory
            val copyProjectParentDir = projectDir.resolveSibling("copy_${projectDir.name}").also { it.mkdirs() }
            copyRecursively(projectDir, copyProjectParentDir)

            checkKaptCachingIncrementalBuild(copyProjectParentDir.resolve(projectName), this)
        }
    }

    private fun checkKotlinCompileCachingIncrementalBuild(projectToBeModified: File, project: BaseGradleIT.Project) {
        with(project) {
            // First build, should be stored into the build cache:
            build("assemble") {
                assertSuccessful()
                assertTaskPackedToCache(":compileKotlin")
            }

            // A cache hit: a clean build without any changes to the project
            build("clean", "assemble", projectDir = projectToBeModified) {
                assertSuccessful()
                assertContains(":compileKotlin FROM-CACHE")
            }

            // Change the return type of foo() from Int to String in foo.kt, and check that fooUsage.kt is recompiled as well:
            File(projectToBeModified, "src/main/kotlin/foo.kt").modify { it.replace("Int = 1", "String = \"abc\"") }
            build("assemble", projectDir = projectToBeModified) {
                assertSuccessful()
                assertCompiledKotlinSources(relativize(allKotlinFiles))
            }

            // Revert the change to the return type of foo(), and check if we get a cache hit
            File(projectToBeModified, "src/main/kotlin/foo.kt").modify { it.replace("String = \"abc\"", "Int = 1") }
            build("clean", "assemble", projectDir = projectToBeModified) {
                assertSuccessful()
                assertContains(":compileKotlin FROM-CACHE")
            }
        }
    }

    private fun checkKaptCachingIncrementalBuild(projectToBeModified: File, project: BaseGradleIT.Project) {
        with(project) {
            val options = defaultBuildOptions().copy(
                kaptOptions = KaptOptions(
                    verbose = true,
                    useWorkers = false,
                    incrementalKapt = true,
                    includeCompileClasspath = false
                )
            )

            // First build, should be stored into the build cache:
            build(options = options, params = *arrayOf("clean", ":app:build")) {
                assertSuccessful()
                assertTaskPackedToCache(":app:kaptGenerateStubsKotlin")
                assertTaskPackedToCache(":app:kaptKotlin")
            }

            // A cache hit: a clean build without any changes to the project
            build(options = options, params = *arrayOf("clean", ":app:build"), projectDir = projectToBeModified) {
                assertSuccessful()
                assertContains(":app:kaptGenerateStubsKotlin FROM-CACHE")
                assertContains(":app:kaptKotlin FROM-CACHE")
            }

            // Make changes to annotated class and check kapt tasks are re-executed
            File(projectToBeModified, "app/src/main/kotlin/AppClass.kt").modify {
                it.replace("val testVal: String = \"text\"", "val testVal: Int = 1")
            }
            build(options = options, params = *arrayOf("build"), projectDir = projectToBeModified) {
                assertSuccessful()
                assertContains("':app:kaptGenerateStubsKotlin' is not up-to-date")
                assertContains("':app:kaptKotlin' is not up-to-date")
            }

            // Revert changes and check kapt tasks are from cache
            File(projectToBeModified, "app/src/main/kotlin/AppClass.kt").modify {
                it.replace("val testVal: Int = 1", "val testVal: String = \"text\"")
            }
            build(options = options, params = *arrayOf("clean", "build"), projectDir = projectToBeModified) {
                assertSuccessful()
                assertContains(":app:kaptGenerateStubsKotlin FROM-CACHE")
                assertContains(":app:kaptKotlin FROM-CACHE")
            }
        }
    }
}

fun BaseGradleIT.Project.prepareLocalBuildCache(directory: File = File(projectDir.parentFile, "buildCache").apply { mkdir() }): File {
    if (!projectDir.exists()) {
        setupWorkingDir()
    }
    File(projectDir, "settings.gradle").appendText("\nbuildCache.local.directory = '${directory.absolutePath.replace("\\", "/")}'")
    return directory
}

internal fun BaseGradleIT.CompiledProject.assertTaskPackedToCache(taskPath: String) {
    with(project.testCase) {
        assertContainsRegex(Regex("(?:Packing|Stored cache entry for) task '${Regex.escape(taskPath)}'"))
    }
}