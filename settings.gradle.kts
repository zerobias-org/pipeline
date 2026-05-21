// settings.gradle.kts for @zerobias-org/pipeline.
//
// Pipeline packages follow the depth-2 vendor/product layout
// (package/<vendor>/<product>/), matching the npm name
// @zerobias-org/pipeline-<vendor>-<product>. Each package is a single
// npm artifact that holds many pipeline definition ymls under its own
// pipeline/ subdirectory.
//
// (The package must NOT sit directly at package/: the publish workflow's
// detect job walks UP from each changed file to the nearest
// build.gradle.kts but explicitly skips the dir literally named
// `package`, so a package rooted there is never detected.)

pluginManagement {
    // Use local build-tools if available (dev), otherwise pull from
    // GitHub Packages Maven (CI).
    val localBuildTools = file("../util/packages/build-tools")
    if (localBuildTools.exists()) {
        includeBuild(localBuildTools)
    }
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/zerobias-org/util")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "zerobias-org"
                password = System.getenv("READ_TOKEN")
                    ?: System.getenv("NPM_TOKEN")
                    ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("zb.workspace") version "1.+"
        id("zb.base") version "1.+"
        id("zb.content") version "1.+"
    }
}

rootProject.name = "pipelines"

// Auto-discover pipeline packages under package/. Packages live two
// directories deep — package/<vendor>/<product>/ — and the marker walk
// picks up build.gradle.kts at any depth. Project paths mirror the
// filesystem: package/zerobias/zerobias → :zerobias:zerobias.
val packageDir = file("package")
if (packageDir.exists()) {
    packageDir.walkTopDown()
        .filter { it.name == "build.gradle.kts" }
        .forEach { buildFile ->
            val moduleDir = buildFile.parentFile
            val relativePath = moduleDir.relativeTo(packageDir).path
            val projectPath = relativePath.replace(File.separatorChar, ':')

            include(projectPath)
            project(":$projectPath").projectDir = moduleDir
        }
}
