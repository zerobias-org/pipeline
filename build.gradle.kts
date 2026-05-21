import com.zerobias.buildtools.content.SchemaPrimitives

plugins {
    id("zb.workspace")
}

group = "com.zerobias.content"

// ════════════════════════════════════════════════════════════
// Pipeline content validator — owned by this repo.
//
// Philosophy (per Chris/Kevin): the dataloader is the source of truth
// for schema rules. Re-validating those here just creates drift risk —
// when the dataloader tightens a rule, the gate gets stale. The full
// schema (id/productId UUID parse, executionMode/batchMode/format/
// connectorType/frequency/timezone enums, collectorArtifact dependency
// + batchMode-support lookup, receiver-mode field forbids, etc.) is
// exercised by testIntegrationDataloader against an ephemeral Neon
// branch during gate. See com/platform/dataloader/src/processors/pipeline/
// (PipelineFileHandler.ts, PipelineArtifactLoader.ts).
//
// SHAPE: a pipeline package is a single npm package at
// package/<vendor>/<product>/ that holds MANY pipeline ymls under its
// own pipeline/ subdirectory (each yml has its own `id` UUID), rather
// than one-artifact-per-directory like vendor/suite. This validator
// only enforces what the dataloader CANNOT or DOES NOT check:
//
//   1. Filesystem ↔ npm ↔ zerobias-block triangulation. The directory
//      package/<vendor>/<product>/ deterministically yields the npm
//      name and zerobias.package; the dataloader reads zerobias.package
//      but never the npm `name` field nor the on-disk path, so a wrong
//      name publishes under the wrong package and only surfaces in prod.
//   2. Required files exist (package.json, .npmrc, a non-empty pipeline/
//      directory).
//   3. Repo-wide unique pipeline `id` UUIDs (separate :validateUniqueIds
//      task below). Dataloader processes one yml at a time, so a
//      duplicate id only surfaces when the second pipeline overwrites
//      the first's DB row.
// ════════════════════════════════════════════════════════════
extra["contentValidator"] = { proj: org.gradle.api.Project ->
    val projectDir = proj.projectDir   // = package/<vendor>/<product>/
    val tag = "[pipeline-validator] ${proj.path}"

    require(projectDir.resolve("package.json").isFile) { "$tag package.json missing in ${projectDir.path}" }
    require(projectDir.resolve(".npmrc").isFile)       { "$tag .npmrc missing in ${projectDir.path}" }

    val pipelineDir = projectDir.resolve("pipeline")
    require(pipelineDir.isDirectory) { "$tag pipeline/ directory missing in ${projectDir.path}" }
    val ymls = pipelineDir.walkTopDown()
        .filter { it.isFile && (it.extension == "yml" || it.extension == "yaml") }
        .toList()
    require(ymls.isNotEmpty()) { "$tag no pipeline definitions found under ${pipelineDir.path}" }

    // ── Filesystem ↔ npm ↔ zerobias-block triangulation ──
    // package/<vendor>/<product>/ → npm @zerobias-org/pipeline-<vendor>-<product>,
    // zerobias.package <vendor>.<product>.pipeline. Both must agree.
    val productCode = projectDir.name
    val vendorCode = projectDir.parentFile.name
    val pkgDoc = SchemaPrimitives.parseJson(projectDir.resolve("package.json"))
    SchemaPrimitives.requirePackageIdentity(
        pkgDoc,
        expectedNpmName = "@zerobias-org/pipeline-$vendorCode-$productCode",
        expectedZerobiasPackage = "$vendorCode.$productCode.pipeline",
        field = "$tag package.json",
    )

    @Suppress("UNCHECKED_CAST")
    val zb = (pkgDoc["zerobias"] ?: pkgDoc["auditmation"]) as? Map<String, Any?>
        ?: throw GradleException("$tag package.json missing 'zerobias' block")
    require(zb["import-artifact"] == "pipeline") {
        "$tag zerobias.import-artifact must be 'pipeline' (got '${zb["import-artifact"]}')"
    }

    proj.logger.lifecycle("$tag: vendor=$vendorCode product=$productCode, ${ymls.size} pipeline definition(s)")
}

// ════════════════════════════════════════════════════════════
// :validateUniqueIds — repo-wide cross-cut.
//
// Walks every package/**/pipeline/**/*.yml, extracts the top-level `id`
// UUID, and fails if two pipelines share one. Cannot be done by the
// dataloader (it processes one yml at a time); a collision only surfaces
// in prod when the second pipeline tries to load to the same DB row.
// Wired as a dependency of each per-package validateContent so any gate
// run picks it up. (deprecated.yml lives at the package root, not under
// pipeline/, so it's naturally excluded.)
// ════════════════════════════════════════════════════════════
val validateUniqueIds by tasks.registering {
    group = "verification"
    description = "Fail if two pipelines share the same id UUID"

    val packageDir = layout.projectDirectory.dir("package").asFile
    inputs.files(
        fileTree(packageDir) {
            include("**/pipeline/**/*.yml")
            exclude("**/node_modules/**")
        }
    )

    doLast {
        val byId = mutableMapOf<String, MutableList<String>>()
        fileTree(packageDir) {
            include("**/pipeline/**/*.yml")
            exclude("**/node_modules/**")
        }.forEach { f ->
            val doc = try {
                SchemaPrimitives.parseYaml(f)
            } catch (e: Exception) {
                logger.warn("[validateUniqueIds] skipping unparseable ${f.relativeTo(rootDir)}: ${e.message}")
                return@forEach
            }
            val id = (doc["id"] as? String)?.lowercase() ?: return@forEach
            byId.getOrPut(id) { mutableListOf() }.add(f.relativeTo(rootDir).path)
        }

        val collisions = byId.filterValues { it.size > 1 }
        if (collisions.isNotEmpty()) {
            val report = collisions.entries.joinToString("\n") { (id, paths) ->
                "  $id\n    " + paths.joinToString("\n    ")
            }
            throw GradleException("[validateUniqueIds] duplicate pipeline ids across the repo:\n$report")
        }
        logger.lifecycle("[validateUniqueIds] ${byId.size} unique pipeline id(s)")
    }
}

subprojects {
    tasks.matching { it.name == "validateContent" }.configureEach {
        dependsOn(rootProject.tasks.named("validateUniqueIds"))
    }
}

val projectPaths by tasks.registering {
    group = "info"
    description = "Output project-to-directory mappings for tooling (used by zbb CLI)"
    doLast {
        subprojects.filter { it.buildFile.exists() }.forEach { p ->
            println("${p.path}=${p.projectDir.relativeTo(rootDir)}")
        }
    }
}

val changedModules by tasks.registering {
    group = "info"
    description = "List pipeline packages changed since last version tag"
    doLast {
        val lastTag = try {
            providers.exec {
                commandLine("git", "describe", "--tags", "--abbrev=0")
            }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            logger.warn("No version tags found -- listing all packages as changed")
            null
        }

        val diffArgs = if (lastTag != null) {
            listOf("git", "diff", "--name-only", lastTag, "HEAD")
        } else {
            listOf("git", "ls-files")
        }

        val result = providers.exec {
            commandLine(diffArgs)
        }.standardOutput.asText.get()

        val changed = result.lines()
            .filter { it.startsWith("package/") }
            .map { it.split("/").drop(1).take(2).joinToString("/") }
            .distinct()
            .filter { it.isNotEmpty() && it.contains("/") }

        changed.forEach { println(it) }
    }
}
