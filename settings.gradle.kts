// settings.gradle.kts for @zerobias-org/pipeline.
//
// NOTE — non-standard shape. Unlike vendor/suite/crosswalk (one npm
// package per leaf directory under package/), the pipeline repo has a
// SINGLE content npm package that lives AT `package/` itself and holds
// many pipeline definition ymls under `package/pipeline/*.yml`. The
// canonical auto-discovery walk (filter build.gradle.kts under package/)
// computes an empty relative path for a marker at package/build.gradle.kts
// and breaks, so we include the project explicitly instead.
//
// The reusable publish workflow is unaffected — it walks UP from each
// changed `package/pipeline/*.yml` to the nearest build.gradle.kts
// (package/build.gradle.kts).

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

// Single content package, rooted at package/. The npm package
// (@zerobias-org/pipeline-zerobias-zerobias) carries many pipeline ymls;
// the bundle (@zerobias-org/pipeline-bundle) is workflow-managed and is
// NOT a gradle subproject (matches vendor/suite).
include(":pipeline")
project(":pipeline").projectDir = file("package")
