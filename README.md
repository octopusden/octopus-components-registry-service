## Dockerfile

By default, Dockerfile uses `eclipse-temurin:21-jdk` image.
To build with custom image use `--build-arg` option.

## Parameters

| Name                                            | Description                       |
|-------------------------------------------------|-----------------------------------|
| components-registry.supportedGroupIds           | Supported group ids.              |
| components-registry.supportedSystems            | Supported systems.                |
| components-registry.version-name.service-branch | Name of a service branch version. |
| components-registry.version-name.service        | Name of a service version.        |
| components-registry.version-name.minor          | Name of a minor version.          |

## Documentation

### Generate locally

Run `asciidoctor` gradle task

### Publish to wiki

Run `adocPublishToWiki` gradle task

### Gradle properties

| Name                                     | Description                                               | `asciidoctor` | `adocPublishToWiki` |
|------------------------------------------|-----------------------------------------------------------|---------------|---------------------|
| adoc.header                              | Header. Default value: Components Registry Configuration. |               |                     |
| adoc.glossary-component-link             | Link to Component page in glossary.                       | +             | +                   |
| adoc.components-registry-link            | Link to Component Registry repository.                    | +             | +                   |
| adoc.release-management-automation-link  | Link to Release Management Automation page.               | +             | +                   |
| adoc.escrow-automation-tool-link         | Link to Escrow Automation Tool page.                      | +             | +                   |
| adoc.service-desk-link                   | Link to Service Desk portal.                              | +             | +                   |
| adoc.components-registry-validation-link | Link to Component Registry Validation builds.             | +             | +                   |
| docker.registry                          | Docker registry url.                                      |               | +                   |
| wiki.url                                 | Confluence url.                                           |               | +                   |
| wiki.username                            | Confluence user.                                          |               | +                   |
| wiki.password                            | Confluence user password.                                 |               | +                   |
| wiki.space-key                           | Confluence space key.                                     |               | +                   |
| wiki.page-id                             | Confluence page id.                                       |               | +                   |

