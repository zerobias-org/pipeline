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
// NON-STANDARD SHAPE: a pipeline npm package is a SINGLE package holding
// MANY pipeline ymls (package/pipeline/*.yml), not one-artifact-per-dir
// like vendor/suite. There is no directory ↔ npm-name triangulation to
// do here — the dataloader reads both `name` and `zerobias.package` from
// the same package.json and there's no authoritative on-disk path to
// compare against. So this validator only enforces what the dataloader
// CANNOT or DOES NOT check:
//
//   1. Required files exist on disk (package.json, .npmrc, a non-empty
//      pipeline/ directory). The dataloader never sees a missing .npmrc
//      until publish fails.
//   2. package.json declares `import-artifact: pipeline` and a non-blank
//      `zerobias.package` / `dataloader-version` (cheap sanity; a wrong
//      import-artifact silently routes the package to the wrong handler).
//   3. Repo-wide unique pipeline `id` UUIDs (separate :validateUniqueIds
//      task below). The dataloader processes one yml at a time, so a
//      duplicate id only surfaces when the second pipeline overwrites
//      the first's DB row.
// ════════════════════════════════════════════════════════════
extra["contentValidator"] = { proj: org.gradle.api.Project ->
    val projectDir = proj.projectDir   // = package/
    val tag = "[pipeline-validator] ${proj.path}"

    require(projectDir.resolve("package.json").isFile) { "$tag package.json missing in ${projectDir.path}" }
    require(projectDir.resolve(".npmrc").isFile)       { "$tag .npmrc missing in ${projectDir.path}" }

    val pipelineDir = projectDir.resolve("pipeline")
    require(pipelineDir.isDirectory) { "$tag pipeline/ directory missing in ${projectDir.path}" }
    val ymls = pipelineDir.walkTopDown()
        .filter { it.isFile && (it.extension == "yml" || it.extension == "yaml") }
        .toList()
    require(ymls.isNotEmpty()) { "$tag no pipeline definitions found under ${pipelineDir.path}" }

    val pkgDoc = SchemaPrimitives.parseJson(projectDir.resolve("package.json"))
    val name = pkgDoc["name"] as? String
    require(name != null && name.startsWith("@zerobias-org/pipeline-")) {
        "$tag package.json name '$name' must start with '@zerobias-org/pipeline-'"
    }

    @Suppress("UNCHECKED_CAST")
    val zb = (pkgDoc["zerobias"] ?: pkgDoc["auditmation"]) as? Map<String, Any?>
        ?: throw GradleException("$tag package.json missing 'zerobias' block")
    require(zb["import-artifact"] == "pipeline") {
        "$tag zerobias.import-artifact must be 'pipeline' (got '${zb["import-artifact"]}')"
    }
    require((zb["package"] as? String)?.isNotBlank() == true) {
        "$tag zerobias.package must be a non-blank string"
    }
    require((zb["dataloader-version"] as? String)?.isNotBlank() == true) {
        "$tag zerobias.dataloader-version must be a non-blank string"
    }

    proj.logger.lifecycle("$tag: ${ymls.size} pipeline definition(s), package=${zb["package"]}")
}

// ════════════════════════════════════════════════════════════
// :validateUniqueIds — repo-wide cross-cut.
//
// Walks every package/**/pipeline/**/*.yml, extracts the top-level `id`
// UUID, and fails if two pipelines share one. Cannot be done by the
// dataloader (it processes one yml at a time); a collision only surfaces
// in prod when the second pipeline tries to load to the same DB row.
// Wired as a dependency of the per-package validateContent so any gate
// run picks it up. (deprecated.yml lives at package/ root, not under
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

        // Single content package rooted at package/. Emit it if anything
        // under package/ changed.
        if (result.lines().any { it.startsWith("package/") }) {
            println("package")
        }
    }
}
