/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.cpp

import org.gradle.nativeplatform.fixtures.app.CppAppWithLibraries
import org.gradle.nativeplatform.fixtures.app.CppAppWithLibrariesWithApiDependencies
import org.gradle.nativeplatform.fixtures.app.CppAppWithLibraryAndOptionalFeature
import org.gradle.nativeplatform.fixtures.app.CppLib
import org.gradle.test.fixtures.archive.ZipTestFixture
import org.gradle.test.fixtures.maven.MavenFileRepository
import org.hamcrest.Matchers

class CppLibraryPublishingIntegrationTest extends AbstractCppInstalledToolChainIntegrationTest implements CppTaskNames {

    def "can publish the binaries and headers of a library to a Maven repository"() {
        def lib = new CppLib()
        assert !lib.publicHeaders.files.empty
        assert !lib.privateHeaders.files.empty

        given:
        buildFile << """
            apply plugin: 'cpp-library'
            apply plugin: 'maven-publish'
            
            group = 'some.group'
            version = '1.2'
            library {
                baseName = 'test'
            }
            publishing {
                repositories { maven { url 'repo' } }
            }
"""
        lib.writeToProject(testDirectory)

        when:
        run('publish')

        then:
        result.assertTasksExecuted(
            compileAndLinkTasks(debug),
            compileAndLinkTasks(release),
            ":generatePomFileForDebugPublication",
            ":generateMetadataFileForDebugPublication",
            ":publishDebugPublicationToMavenRepository",
            ":cppHeaders",
            ":generatePomFileForMainPublication",
            ":generateMetadataFileForMainPublication",
            ":publishMainPublicationToMavenRepository",
            ":generatePomFileForReleasePublication",
            ":generateMetadataFileForReleasePublication",
            ":publishReleasePublicationToMavenRepository",
            ":publish"
        )

        def headersZip = file("build/headers/cpp-api-headers.zip")
        new ZipTestFixture(headersZip).hasDescendants(lib.publicHeaders.files*.name)

        def repo = new MavenFileRepository(file("repo"))

        def main = repo.module('some.group', 'test', '1.2')
        main.assertPublished()
        main.assertArtifactsPublished("test-1.2-cpp-api-headers.zip", "test-1.2.pom", "test-1.2.module")
        main.artifactFile(classifier: 'cpp-api-headers', type: 'zip').assertIsCopyOf(headersZip)

        main.parsedPom.scopes.isEmpty()

        def mainMetadata = main.parsedModuleMetadata
        mainMetadata.variants.size() == 5
        def api = mainMetadata.variant("api")
        api.dependencies.empty
        api.files.size() == 1
        api.files[0].name == 'cpp-api-headers.zip'
        api.files[0].url == 'test-1.2-cpp-api-headers.zip'
        mainMetadata.variant("debug-link").availableAt.coords == "some.group:test_debug:1.2"
        mainMetadata.variant("debug-runtime").availableAt.coords == "some.group:test_debug:1.2"
        mainMetadata.variant("release-link").availableAt.coords == "some.group:test_release:1.2"
        mainMetadata.variant("release-runtime").availableAt.coords == "some.group:test_release:1.2"

        def debug = repo.module('some.group', 'test_debug', '1.2')
        debug.assertPublished()
        debug.assertArtifactsPublished(withSharedLibrarySuffix("test_debug-1.2"), withLinkLibrarySuffix("test_debug-1.2"), "test_debug-1.2.pom", "test_debug-1.2.module")
        debug.artifactFile(type: sharedLibraryExtension).assertIsCopyOf(sharedLibrary("build/lib/main/debug/test").file)
        debug.artifactFile(type: linkLibrarySuffix).assertIsCopyOf(sharedLibrary("build/lib/main/debug/test").linkFile)

        debug.parsedPom.scopes.isEmpty()

        def debugMetadata = debug.parsedModuleMetadata
        debugMetadata.variants.size() == 2
        def debugLink = debugMetadata.variant('debug-link')
        debugLink.dependencies.empty
        debugLink.files.size() == 1
        debugLink.files[0].name == linkLibraryName('test')
        debugLink.files[0].url == withLinkLibrarySuffix("test_debug-1.2")
        def debugRuntime = debugMetadata.variant('debug-runtime')
        debugRuntime.dependencies.empty
        debugRuntime.files.size() == 1
        debugRuntime.files[0].name == sharedLibraryName('test')
        debugRuntime.files[0].url == withSharedLibrarySuffix("test_debug-1.2")

        def release = repo.module('some.group', 'test_release', '1.2')
        release.assertPublished()
        release.assertArtifactsPublished(withSharedLibrarySuffix("test_release-1.2"), withLinkLibrarySuffix("test_release-1.2"), "test_release-1.2.pom", "test_release-1.2.module")
        release.artifactFile(type: sharedLibraryExtension).assertIsCopyOf(sharedLibrary("build/lib/main/release/test").file)
        release.artifactFile(type: linkLibrarySuffix).assertIsCopyOf(sharedLibrary("build/lib/main/release/test").linkFile)

        release.parsedPom.scopes.isEmpty()

        def releaseMetadata = release.parsedModuleMetadata
        releaseMetadata.variants.size() == 2
        def releaseLink = releaseMetadata.variant('release-link')
        releaseLink.dependencies.empty
        releaseLink.files.size() == 1
        releaseLink.files[0].name == linkLibraryName('test')
        releaseLink.files[0].url == withLinkLibrarySuffix("test_release-1.2")
        def releaseRuntime = releaseMetadata.variant('release-runtime')
        releaseRuntime.dependencies.empty
        releaseRuntime.files.size() == 1
        releaseRuntime.files[0].name == sharedLibraryName('test')
        releaseRuntime.files[0].url == withSharedLibrarySuffix("test_release-1.2")
    }

    def "can publish a library and its dependencies to a Maven repository"() {
        def app = new CppAppWithLibrariesWithApiDependencies()

        given:
        def repoDir = file("repo")
        settingsFile << "include 'deck', 'card', 'shuffle'"
        buildFile << """
            subprojects {
                apply plugin: 'cpp-library'
                apply plugin: 'maven-publish'
                
                group = 'some.group'
                version = '1.2'
                publishing {
                    repositories { maven { url '${repoDir.toURI()}' } }
                }
            }
            project(':deck') { 
                dependencies {
                    api project(':card')
                    implementation project(':shuffle')
                }
            }
"""
        app.deck.writeToProject(file('deck'))
        app.card.writeToProject(file('card'))
        app.shuffle.writeToProject(file('shuffle'))

        when:
        run('publish')

        then:
        def repo = new MavenFileRepository(repoDir)

        def deckModule = repo.module('some.group', 'deck', '1.2')
        deckModule.assertPublished()
        deckModule.parsedPom.scopes.size() == 1
        deckModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2")

        def deckMetadata = deckModule.parsedModuleMetadata
        def deckApi = deckMetadata.variant("api")
        deckApi.dependencies.size() == 1
        deckApi.dependencies[0].group == "some.group"
        deckApi.dependencies[0].module == "card"
        deckApi.dependencies[0].version == "1.2"

        def deckDebugModule = repo.module('some.group', 'deck_debug', '1.2')
        deckDebugModule.assertPublished()

        deckDebugModule.parsedPom.scopes.size() == 1
        deckDebugModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2", "some.group:shuffle:1.2")

        def deckDebugMetadata = deckDebugModule.parsedModuleMetadata
        def deckDebugLink = deckDebugMetadata.variant("debug-link")
        deckDebugLink.dependencies.size() == 2
        deckDebugLink.dependencies[0].coords == "some.group:shuffle:1.2"
        deckDebugLink.dependencies[1].coords == "some.group:card:1.2"
        def deckDebugRuntime = deckDebugMetadata.variant("debug-runtime")
        deckDebugRuntime.dependencies.size() == 2
        deckDebugRuntime.dependencies[0].coords == "some.group:shuffle:1.2"
        deckDebugRuntime.dependencies[1].coords == "some.group:card:1.2"

        def deckReleaseModule = repo.module('some.group', 'deck_release', '1.2')
        deckReleaseModule.assertPublished()
        deckReleaseModule.parsedPom.scopes.size() == 1
        deckReleaseModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2", "some.group:shuffle:1.2")

        def deckReleaseMetadata = deckReleaseModule.parsedModuleMetadata
        def deckReleaseLink = deckReleaseMetadata.variant("release-link")
        deckReleaseLink.dependencies.size() == 2
        deckReleaseLink.dependencies[0].coords == "some.group:shuffle:1.2"
        deckReleaseLink.dependencies[1].coords == "some.group:card:1.2"
        def deckReleaseRuntime = deckReleaseMetadata.variant("release-runtime")
        deckReleaseRuntime.dependencies.size() == 2
        deckReleaseRuntime.dependencies[0].coords == "some.group:shuffle:1.2"
        deckReleaseRuntime.dependencies[1].coords == "some.group:card:1.2"

        def cardModule = repo.module('some.group', 'card', '1.2')
        cardModule.assertPublished()
        cardModule.parsedPom.scopes.isEmpty()

        def cardDebugModule = repo.module('some.group', 'card_debug', '1.2')
        cardDebugModule.assertPublished()
        cardDebugModule.parsedPom.scopes.isEmpty()

        def cardReleaseModule = repo.module('some.group', 'card_release', '1.2')
        cardReleaseModule.assertPublished()
        cardReleaseModule.parsedPom.scopes.isEmpty()

        def shuffleModule = repo.module('some.group', 'shuffle', '1.2')
        shuffleModule.assertPublished()

        def shuffleDebugModule = repo.module('some.group', 'shuffle_debug', '1.2')
        shuffleDebugModule.assertPublished()

        def shuffleReleaseModule = repo.module('some.group', 'shuffle_release', '1.2')
        shuffleReleaseModule.assertPublished()

        when:
        def consumer = file("consumer").createDir()
        consumer.file('settings.gradle') << ''
        consumer.file("build.gradle") << """
            apply plugin: 'cpp-application'
            repositories { maven { url '${repoDir.toURI()}' } }
            dependencies { implementation 'some.group:deck:1.2' }
"""
        app.main.writeToProject(consumer)

        executer.inDirectory(consumer)
        run("assemble")

        then:
        noExceptionThrown()
        sharedLibrary(consumer.file("build/install/main/debug/lib/deck")).file.assertExists()
        sharedLibrary(consumer.file("build/install/main/debug/lib/card")).file.assertExists()
        sharedLibrary(consumer.file("build/install/main/debug/lib/shuffle")).file.assertExists()
        installation(consumer.file("build/install/main/debug")).exec().out == app.expectedOutput
    }

    def "can publish a library with external dependencies to a Maven repository"() {
        def app = new CppAppWithLibrariesWithApiDependencies()

        given:
        def repoDir = file("repo")
        def producer = file("producer")
        producer.file("settings.gradle") << "include 'card', 'shuffle'"
        producer.file("build.gradle") << """
            subprojects {
                apply plugin: 'cpp-library'
                apply plugin: 'maven-publish'
                
                group = 'some.group'
                version = '1.2'
                publishing {
                    repositories { maven { url '${repoDir.toURI()}' } }
                }
            }
"""
        app.card.writeToProject(producer.file('card'))
        app.shuffle.writeToProject(producer.file('shuffle'))
        executer.inDirectory(producer)
        run('publish')

        settingsFile << "rootProject.name = 'deck'"
        buildFile << """
            apply plugin: 'cpp-library'
            apply plugin: 'maven-publish'

            repositories { maven { url '${repoDir.toURI()}' } }
            
            group = 'some.group'
            version = '1.2'
            publishing {
                repositories { maven { url '${repoDir.toURI()}' } }
            }
            
            dependencies {
                api 'some.group:card:1.2'
                implementation 'some.group:shuffle:1.2'
            }
"""
        app.deck.writeToProject(testDirectory)

        when:
        run("publish")

        then:
        def repo = new MavenFileRepository(repoDir)

        def deckModule = repo.module('some.group', 'deck', '1.2')
        deckModule.assertPublished()
        deckModule.parsedPom.scopes.size() == 1
        deckModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2")

        def deckMetadata = deckModule.parsedModuleMetadata
        def deckApi = deckMetadata.variant("api")
        deckApi.dependencies.size() == 1
        deckApi.dependencies[0].group == "some.group"
        deckApi.dependencies[0].module == "card"
        deckApi.dependencies[0].version == "1.2"

        def deckDebugModule = repo.module('some.group', 'deck_debug', '1.2')
        deckDebugModule.assertPublished()
        deckDebugModule.parsedPom.scopes.size() == 1
        deckDebugModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2", "some.group:shuffle:1.2")

        def deckDebugMetadata = deckDebugModule.parsedModuleMetadata
        def deckDebugLink = deckDebugMetadata.variant("debug-link")
        deckDebugLink.dependencies.size() == 2
        deckDebugLink.dependencies[0].coords == "some.group:shuffle:1.2"
        deckDebugLink.dependencies[1].coords == "some.group:card:1.2"
        def deckDebugRuntime = deckDebugMetadata.variant("debug-runtime")
        deckDebugRuntime.dependencies.size() == 2
        deckDebugRuntime.dependencies[0].coords == "some.group:shuffle:1.2"
        deckDebugRuntime.dependencies[1].coords == "some.group:card:1.2"

        def deckReleaseModule = repo.module('some.group', 'deck_release', '1.2')
        deckReleaseModule.assertPublished()
        deckReleaseModule.parsedPom.scopes.size() == 1
        deckReleaseModule.parsedPom.scopes.runtime.assertDependsOn("some.group:card:1.2", "some.group:shuffle:1.2")

        def deckReleaseMetadata = deckReleaseModule.parsedModuleMetadata
        def deckReleaseLink = deckReleaseMetadata.variant("release-link")
        deckReleaseLink.dependencies.size() == 2
        deckReleaseLink.dependencies[0].coords == "some.group:shuffle:1.2"
        deckReleaseLink.dependencies[1].coords == "some.group:card:1.2"
        def deckReleaseRuntime = deckReleaseMetadata.variant("release-runtime")
        deckReleaseRuntime.dependencies.size() == 2
        deckReleaseRuntime.dependencies[0].coords == "some.group:shuffle:1.2"
        deckReleaseRuntime.dependencies[1].coords == "some.group:card:1.2"

        when:
        def consumer = file("consumer").createDir()
        consumer.file('settings.gradle') << ''
        consumer.file("build.gradle") << """
            apply plugin: 'cpp-application'
            repositories { maven { url '${repoDir.toURI()}' } }
            dependencies { implementation 'some.group:deck:1.2' }
"""
        app.main.writeToProject(consumer)

        executer.inDirectory(consumer)
        run("assemble")

        then:
        noExceptionThrown()
        sharedLibrary(consumer.file("build/install/main/debug/lib/deck")).file.assertExists()
        sharedLibrary(consumer.file("build/install/main/debug/lib/card")).file.assertExists()
        sharedLibrary(consumer.file("build/install/main/debug/lib/shuffle")).file.assertExists()
        installation(consumer.file("build/install/main/debug")).exec().out == app.expectedOutput
    }

    def "uses base name of library to calculate coordinates"() {
        def app = new CppAppWithLibrariesWithApiDependencies()

        given:
        def repoDir = file("repo")
        settingsFile << "include 'deck', 'card', 'shuffle'"
        buildFile << """
            subprojects {
                apply plugin: 'cpp-library'
                apply plugin: 'maven-publish'
                
                group = 'some.group'
                version = '1.2'

                publishing {
                    repositories { maven { url '${repoDir.toURI()}' } }
                }
            }
            project(':deck') { 
                library.baseName = 'card_deck'
                dependencies {
                    api project(':card')
                    implementation project(':shuffle')
                }
            }
            project(':shuffle') {
                library.baseName = 'card_shuffle'
            }
"""
        app.deck.writeToProject(file('deck'))
        app.card.writeToProject(file('card'))
        app.shuffle.writeToProject(file('shuffle'))

        when:
        run('publish')

        then:
        def repo = new MavenFileRepository(repoDir)

        def deckModule = repo.module('some.group', 'card_deck', '1.2')
        deckModule.assertPublished()

        def deckMetadata = deckModule.parsedModuleMetadata
        def deckApi = deckMetadata.variant("api")
        deckApi.dependencies.size() == 1
        deckApi.dependencies[0].coords == "some.group:card:1.2"

        def deckDebugModule = repo.module('some.group', 'card_deck_debug', '1.2')
        deckDebugModule.assertPublished()

        def deckDebugMetadata = deckDebugModule.parsedModuleMetadata
        def deckDebugLink = deckDebugMetadata.variant("debug-link")
        deckDebugLink.dependencies.size() == 2
        deckDebugLink.dependencies[0].coords == "some.group:card_shuffle:1.2"
        deckDebugLink.dependencies[1].coords == "some.group:card:1.2"
        def deckDebugRuntime = deckDebugMetadata.variant("debug-runtime")
        deckDebugRuntime.dependencies.size() == 2
        deckDebugRuntime.dependencies[0].coords == "some.group:card_shuffle:1.2"
        deckDebugRuntime.dependencies[1].coords == "some.group:card:1.2"

        def deckReleaseModule = repo.module('some.group', 'card_deck_release', '1.2')
        deckReleaseModule.assertPublished()

        def deckReleaseMetadata = deckReleaseModule.parsedModuleMetadata
        def deckReleaseLink = deckReleaseMetadata.variant("release-link")
        deckReleaseLink.dependencies.size() == 2
        deckReleaseLink.dependencies[0].coords == "some.group:card_shuffle:1.2"
        deckReleaseLink.dependencies[1].coords == "some.group:card:1.2"
        def deckReleaseRuntime = deckReleaseMetadata.variant("release-runtime")
        deckReleaseRuntime.dependencies.size() == 2
        deckReleaseRuntime.dependencies[0].coords == "some.group:card_shuffle:1.2"
        deckReleaseRuntime.dependencies[1].coords == "some.group:card:1.2"

        def cardModule = repo.module('some.group', 'card', '1.2')
        cardModule.assertPublished()

        def cardDebugModule = repo.module('some.group', 'card_debug', '1.2')
        cardDebugModule.assertPublished()

        def cardReleaseModule = repo.module('some.group', 'card_release', '1.2')
        cardReleaseModule.assertPublished()

        def shuffleModule = repo.module('some.group', 'card_shuffle', '1.2')
        shuffleModule.assertPublished()

        def shuffleDebugModule = repo.module('some.group', 'card_shuffle_debug', '1.2')
        shuffleDebugModule.assertPublished()

        def shuffleReleaseModule = repo.module('some.group', 'card_shuffle_release', '1.2')
        shuffleReleaseModule.assertPublished()

        when:
        def consumer = file("consumer").createDir()
        consumer.file('settings.gradle') << ''
        consumer.file("build.gradle") << """
            apply plugin: 'cpp-application'
            repositories { maven { url '${repoDir.toURI()}' } }
            dependencies { implementation 'some.group:card_deck:1.2' }
"""
        app.main.writeToProject(consumer)

        executer.inDirectory(consumer)
        run("assemble")

        then:
        noExceptionThrown()
        sharedLibrary(consumer.file("build/install/main/debug/lib/card_deck")).file.assertExists()
        sharedLibrary(consumer.file("build/install/main/debug/lib/card")).file.assertExists()
        sharedLibrary(consumer.file("build/install/main/debug/lib/card_shuffle")).file.assertExists()
        installation(consumer.file("build/install/main/debug")).exec().out == app.expectedOutput
    }

    def "private headers are not visible to consumer"() {
        def lib = new CppLib()
        def repoDir = file("repo")

        given:
        settingsFile << "rootProject.name = 'testlib'"
        buildFile << """
            apply plugin: 'cpp-library'
            apply plugin: 'maven-publish'
            
            group = 'some.group'
            version = '1.2'
            publishing {
                repositories { maven { url '${repoDir.toURI()}' } }
            }
"""
        lib.writeToProject(testDirectory)

        run('publish')

        def consumer = file("consumer")
        consumer.file("settings.gradle") << ''
        consumer.file("src/main/cpp/main.cpp") << """
#include "greeter_consts.h"
"""
        consumer.file("build.gradle") << """
apply plugin: 'cpp-library'
repositories { maven { url '${repoDir.toURI()}' } }
dependencies { implementation 'some.group:testlib:1.2' }
"""

        when:
        executer.inDirectory(consumer)
        fails("compileDebugCpp")

        then:
        failure.assertThatCause(Matchers.containsString("C++ compiler failed while compiling main.cpp."))

        when:
        buildFile << """
library.privateHeaders.from = []
library.publicHeaders.from 'src/main/public', 'src/main/headers'
"""
        run('publish')

        then:
        executer.inDirectory(consumer)
        succeeds("compileDebugCpp")
    }

    def "implementation dependencies are not visible to consumer"() {
        def app = new CppAppWithLibraries()
        def repoDir = file("repo")

        given:
        settingsFile << "include 'greeter', 'logger'"
        buildFile << """
            subprojects {
                apply plugin: 'cpp-library'
                apply plugin: 'maven-publish'
                
                group = 'some.group'
                version = '1.2'
                publishing {
                    repositories { maven { url '${repoDir.toURI()}' } }
                }
            }
            project(':greeter') {
                dependencies { implementation project(':logger') }
            }
"""
        app.greeterLib.writeToProject(file('greeter'))
        app.loggerLib.writeToProject(file('logger'))

        run('publish')

        def consumer = file("consumer")
        consumer.file("settings.gradle") << ''
        consumer.file("src/main/cpp/main.cpp") << """
#include "logger.h"
"""
        consumer.file("build.gradle") << """
apply plugin: 'cpp-library'
repositories { maven { url '${repoDir.toURI()}' } }
dependencies { implementation 'some.group:greeter:1.2' }
"""

        when:
        executer.inDirectory(consumer)
        fails("compileDebugCpp")

        then:
        failure.assertThatCause(Matchers.containsString("C++ compiler failed while compiling main.cpp."))

        when:
        buildFile.text = buildFile.text.replace("dependencies { implementation project(':logger')", "dependencies { api project(':logger')")
        run('publish')

        then:
        executer.inDirectory(consumer)
        succeeds("compileDebugCpp")
    }

    def "correct variant of published library is selected when resolving"() {
        def app = new CppAppWithLibraryAndOptionalFeature()

        def repoDir = file("repo")
        def producer = file("greeting")
        producer.file("build.gradle") << """
            apply plugin: 'cpp-library'
            apply plugin: 'maven-publish'
            
            group = 'some.group'
            version = '1.2'
            publishing {
                repositories { maven { url '${repoDir.toURI()}' } }
            }
            compileReleaseCpp.macros(WITH_FEATURE: "true")
            
        """
        app.greeterLib.writeToProject(file(producer))

        executer.inDirectory(producer)
        run('publish')

        def consumer = file("consumer").createDir()
        consumer.file("build.gradle") << """
            apply plugin: 'cpp-application'
            repositories { maven { url '${repoDir.toURI()}' } }
            dependencies { implementation 'some.group:greeting:1.2' }
            compileReleaseCpp.macros(WITH_FEATURE: "true")
"""
        app.main.writeToProject(consumer)

        when:
        executer.inDirectory(consumer)
        run("installDebug")

        then:
        installation(consumer.file("build/install/main/debug")).exec().out == app.withFeatureDisabled().expectedOutput

        when:
        executer.inDirectory(consumer)
        run("installRelease")

        then:
        installation(consumer.file("build/install/main/release")).exec().out == app.withFeatureEnabled().expectedOutput
    }

}
