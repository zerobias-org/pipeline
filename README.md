# ZeroBias Community Pipeline Configurations

Open-source **pipeline** content artifacts under `@zerobias-org`. A pipeline
binds a collectorbot to a product and a schedule, telling the platform how and
when to collect data into AuditgraphDB.

## Shape (non-standard)

Unlike most content repos (one npm package per leaf directory), pipelines use a
**single npm package** that holds **many** pipeline definitions:

```
package/
├── package.json        # @zerobias-org/pipeline-zerobias-zerobias
│                        #   zerobias.package = zerobias.zerobias.pipeline
│                        #   zerobias.import-artifact = pipeline
├── .npmrc
├── build.gradle.kts     # one-line marker: plugins { id("zb.content") }
├── gate-stamp.json      # written by ./gradlew :pipeline:gate
├── deprecated.yml       # { pipelines: [<uuid>, ...] } — soft-deletes
└── pipeline/
    ├── agentskills.yml  # one pipeline per yml; each has its own `id` UUID
    ├── hl7-fhir.yml
    └── mcpservers.yml
bundle/                  # @zerobias-org/pipeline-bundle (workflow-managed)
```

Each `pipeline/*.yml` carries `id`, `name`, `productId`, `collectorArtifact`,
`executionMode` (`caller`/`receiver`), `batchMode`, `format`, `connectorType`,
and schedule fields. The dataloader (`com/platform/dataloader/src/processors/pipeline/`)
is the source of truth for all field-level validation.

## Build & validate (gradle + zbb)

```bash
# Validate + dataloader integration + write gate-stamp
./gradlew :pipeline:gate

# Cross-cut: ensure no two pipelines share an id UUID
./gradlew validateUniqueIds

# List discovered gradle projects
./gradlew projectPaths
```

`gate` runs the repo-owned validator (file presence, `import-artifact`,
unique ids) and `testIntegrationDataloader`, which loads every pipeline against
an ephemeral Neon Postgres branch. Without `NEON_API_KEY` / `NEON_PROJECT_ID`
the dataloader step is skipped locally; CI re-runs it on push. Commit the
refreshed `gate-stamp.json` whenever package contents change.

## Publishing

Driven by `zerobias-org/devops/.github/workflows/zbb-publish-reusable.yml`,
triggered on push to `main` / `qa` / `dev` / `uat` (see
`.github/workflows/publish.yml`). The workflow detects the changed package,
single-writer version-bumps on `main`, publishes, refreshes the bundle, and
syncs branches. Do not `npm publish` by hand.

## Authentication

- `ZB_TOKEN` / `NPM_TOKEN` — npm registry access (`package/.npmrc`)
- `NEON_API_KEY` + `NEON_PROJECT_ID` — dataloader integration (sourced from
  vault via `zbb.yaml` in CI)
