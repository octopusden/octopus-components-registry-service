# Automatic Documentation Publishing via Components Registry

A software component can be linked to its documentation component directly in the Components Registry.
Once this link is declared, documentation is automatically published to the DMS portal during the
component's release — no manual steps or changes to the component's `build.gradle` are required.

> **Note:** The previous approach (declaring the doc component as an explicit release dependency
> in `build.gradle`) remains valid and is still in use. Use the new approach for all new integrations.

## How it works

1. **Registry declaration**: add a `doc { }` section to your component entry in the Components
   Registry. This links your component to its documentation component.
2. **Automatic dependency calculation**: during each Compile&UT build, the Release Management
   infrastructure resolves the latest released version of the documentation component (matching
   the version line you specified) and registers it as a build dependency automatically.
3. **Automatic DMS upload**: during the Release build, the documentation artifacts are automatically
   uploaded to the DMS portal — no additional build steps required from the component owner.

### Result

Once the link is configured, documentation artifacts appear automatically under the component's
release in the DMS Portal alongside the regular binaries.

## Prerequisites

The documentation component must **already be registered** in the Components Registry
(`DocumentationComponents.groovy`) with a `distribution.GAV` defined before you can reference it.
If your doc component does not exist yet, ask your technical writer to register it first.

## How to declare the link

Add a `doc { }` section to your component entry in the Components Registry. For most components,
no version pinning is needed — just the component name:

```groovy
"my-component" {
    // ... existing fields ...

    doc {
        component = "doc-my-component"
    }
}
```

This tells the release infrastructure to always pick the **latest released version** of
`doc-my-component` when releasing `my-component`.

### Pinning to a specific version line

If the documentation component has independent version lines (e.g. `1.x` docs for component `1.x`,
`2.x` docs for component `2.x`), use `majorVersion` to pin:

```groovy
"(1,2)" {
    doc {
        component = "doc-my-component"
        majorVersion = "1"    // resolves to latest 1.x of doc-my-component
    }
}
"[2,)" {
    doc {
        component = "doc-my-component"
        majorVersion = "2"    // resolves to latest 2.x of doc-my-component
    }
}
```

You can also use version format variables:

```groovy
doc {
    component = "doc-my-component"
    majorVersion = '$major.$minor'  // e.g. component 3.5.* pins to doc 3.5.x
}
```

## Example

`report-service` in `Components.groovy`:

```groovy
"report-service" {
    componentDisplayName = "Report Service"
    componentOwner = "jlenon"
    groupId = "com.acme.component"
    // ...

    doc {
        component = "doc-report-service"    // <- this is the only change needed
    }

    distribution {
        explicit = true
        external = true
        GAV = 'com.acme.component:report-service:zip'
    }
}
```

The corresponding `doc-report-service` entry in `DocumentationComponents.groovy` defines the
artifact coordinates:

```groovy
"doc-report-service" {
    groupId = "com.acme.component.doc"
    artifactId = "report-service-doc"
    // ...
    distribution {
        explicit = true
        external = true
        GAV = "com.acme.component.doc:report-service-doc:zip:english," +
              "com.acme.component.doc:report-service-doc:zip:russian"
    }
}
```

Both English and Russian artifacts are automatically uploaded to DMS on every `report-service` release.

## Step-by-step: integrating your component

1. Confirm the doc component (e.g. `doc-my-component`) exists in `DocumentationComponents.groovy`
   with a `distribution.GAV`. If not — coordinate with your tech writer.
2. Open your component's file in the Components Registry (e.g. `Components.groovy`).
3. Add the `doc { component = "doc-my-component" }` section. Add `majorVersion` only if the
   documentation has independent version lines per release train.
4. Commit and submit a PR to the `components-registry` repo.
5. After the PR is merged, the link is active: from the next Compile&UT build the doc dependency
   is calculated automatically; from the next release, docs are uploaded to DMS automatically.

## Validation rules

- The `component` field inside `doc { }` is required.
- The referenced documentation component must exist in the registry.
- The documentation component must have `distribution.GAV` defined (not a VCS-only component).
- A documentation component cannot itself reference another documentation component.
- Version ranges containing `doc { }` sections must not overlap.
