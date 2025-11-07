# Documentation Automation Improvement Concept (DRAFT)

## Overview

This document describes the concept for improving documentation automation in the Components Registry and related systems.

The improvement is split into two parts:
1. **General documentation automation** - linking components with their documentation, automated dependency management
2. **Solution documentation support** - automated aggregation of documentation for solutions

---

## Part 1: General Documentation Automation

### 1.1 Add `doc` Section to Components

**Goal:** Explicitly link software components with their documentation components.

**Implementation:**



**Example configuration:**
```groovy
"mycomponent" {
    componentOwner = "user1"
    groupId = "org.company.mycomponent"
    
    "(1,2)" {
        doc {
            component = "doc_mycomponent"
            majorVersion = '$major.$minor'
        }
    }
    "(2,3)" {
        doc {
            component = "doc_mycomponent"
            majorVersion = '2'
        }
    }
}

"doc_mycomponent" {
    componentOwner = "tech_writer1"
    componentDisplayName = "MyComponent Documentation"
    groupId = "org.company.docs"
    artifactId = "mycomponent-docs"
    
    distribution {
        explicit = true
        external = true
        GAV = "org.company.docs:mycomponent-docs:zip"
    }
}
```

TODO: To decide (simplifications):
* majorVersion is optional
* don't support verion ranges for doc section
* majorVersion or lineVersion (preferred)?
* check that there ara not multi linked doc components
* для Doc компонетов обязательный distribution->GAV (+ validation GAV что там нет file entities)
* supportedLanguages не нужны?
* doc component - это компонент, в котрый в другом компоненте указан в секции doc


**Support in REST API:**
   `GET /api/rest/v2/components/{name}/versions/{version}`
   `GET /api/rest/v2/components/{name}/versions/{version}/doc`


**API Response:**
```json
{
   ...
  "component": "mycomponent",
  "version": "2.5.123",
  "doc": {
    "component": "doc_mycomponent",
    "majorVersion": "2.5"
  }
}
```

### 1.2 Calculate Documentation Dependencies in Build

**Goal:** Automatically resolve and inject documentation dependencies into the build process.

**Component:** `CalculateReleaseManagementParameters` (SetDistributionMojo in releng)

**Implementation:**
- Add calculation of TeamCity parameter `DEPENDENCIES` or separate `DOC_DEPENDENCIES`
- Resolution rule: `doc_mycomponent:<latest released version from version line in CR>` (Take data from CRS and RM service)

**Example:**
```
Component: mycomponent v2.5.123
doc section: { componentKey = "doc_mycomponent", majorVersion = "2.5" }

Resolved dependency:
DOC_DEPENDENCIES = doc_mycomponent:2.5.45  (latest released 2.5.x version)
```

**Purpose:** This parameter will be passed to the standard dependency registration mechanism on the build (register-build).

### 1.3 Add `supportedDocLanguages` Configuration

**Goal:** Support multiple documentation languages in the registry.

**Component:** ComponentRegistryService

**Implementation:**
- Add configuration parameter `supportedDocLanguages` (similar to `supportedGroupIds`)
- Expose in REST API

**Configuration example:**
```yaml
components-registry:
  supportedDocLanguages: "russian,english,spanish"
```

**API endpoint:**
```
GET /api/rest/v2/configuration
Response:
{
  "supportedGroupIds": [...],
  "supportedDocLanguages": ["russian", "english", "spanish"]
}
```

### 1.4 DMS Upload Step for Documentation

**Goal:** Automatically upload documentation artifacts to DMS during component release.

**Component:** DMS Client

**Implementation:**
- Add new step (meta-runner + implementation)
- Upload to DMS for the main component from documentation component ZIP artifacts:
  - `{artifactId}-russian.zip`
  - `{artifactId}-english.zip`
  - `{artifactId}-spanish.zip`
- Check artifact existence via Artifactory REST API first
- Upload only found artifacts

**Algorithm:**
```
1. Get component documentation info from Components Registry API
   GET /api/rest/v2/components/{name}/versions/{version}
   
2. Resolve documentation component version
   doc_mycomponent:2.5.45
   
3. Query Artifactory for language-specific artifacts:
   GET /api/search/artifact?name=mycomponent-docs-2.5.45-russian.zip&repos=...
   GET /api/search/artifact?name=mycomponent-docs-2.5.45-english.zip&repos=...
   GET /api/search/artifact?name=mycomponent-docs-2.5.45-spanish.zip&repos=...
   
4. For each found artifact:
   - Download from Artifactory
   - Upload to DMS with type=documentation, language=<lang>
```

**DMS Artifact Metadata:**
```json
{
  "component": "mycomponent",
  "version": "2.5.123",
  "type": "documentation",
  "language": "russian",
  "sourceArtifact": "mycomponent-docs-2.5.45-russian.zip"
}
```

### 1.5 Add Documentation Step to Release Template

**Goal:** Integrate documentation upload into standard release process.

**Implementation:**
- Add step from 1.4 to Release TeamCity template
- Execute after main artifact upload


**Template addition:**
```
Build Steps:
  ...
  - Upload Release Artifacts
  - Upload Documentation (from step 1.4)  ← NEW
  - Register Release in JIRA
  ...
```

---

## Part 2: Solution Documentation Support

### 2.1 Solution Documentation Aggregation

**Goal:** Automatically aggregate documentation from all solution sub-components during solution release.

**Component:** Solution Release Process

**Implementation:**
- Noted: Implemented in idp-automation

- Add step to solution release that:
  1. Retrieves all sub-components for the solution
  2. Downloads all `type=documentation` artifacts from DMS for each sub-component
  3. Aggregates artifacts by language
  4. Creates combined archives per language
  5. Uploads aggregated archives to DMS for the solution release

**Algorithm:**
```
1. Get solution sub-components from Components Registry
   GET /api/rest/v2/components/{solution_name}/versions/{version}
   → returns list of sub-components with versions
   
2. For each sub-component:
   Query DMS for documentation artifacts:
   GET /dms/api/artifacts?component={sub}&version={ver}&type=documentation
   
3. Download all found documentation artifacts
   
4. Group by language:
   russian_docs = [comp1-russian.zip, comp2-russian.zip, ...]
   english_docs = [comp1-english.zip, comp2-english.zip, ...]
   spanish_docs = [comp1-english.zip, comp3-spanish.zip, ...]
   
5. For each language:
   - Extract all ZIPs
   - Merge content (preserving structure: comp1/..., comp2/...)
   - Create aggregated archive: {solution}-{version}-{language}.zip
   
6. Upload aggregated archives to DMS:
   {solution}-1.0.0-russian.zip
   {solution}-1.0.0-english.zip
   {solution}-1.0.0-spanish.zip
```

**Aggregated Archive Structure:**
```
solution-1.0.0-russian.zip
├── component1/
│   ├── UserGuide.pdf
│   └── AdminGuide.pdf
├── component2/
│   └── API_Reference.pdf
├── component3/
│   ├── Installation.pdf
│   └── Configuration.pdf
└── index.html  (optional: generated table of contents)
```

**DMS Metadata for Aggregated Documentation:**
```json
{
  "component": "my_solution",
  "version": "1.0.0",
  "type": "documentation",
  "language": "russian",
  "aggregated": true,
  "subComponents": [
    "component1:2.5.123",
    "component2:3.1.45",
    "component3:1.0.7"
  ]
}
```

---

## Implementation Sequence

### Phase 1: Foundation (Part 1.1-1.3)
1. Implement `doc` section in Components Registry
2. Add REST API support
3. Add `supportedDocLanguages` configuration

**Expected Duration:** 2-3 weeks

### Phase 2: Build Integration (Part 1.2, 1.4-1.5)
1. Implement documentation dependency calculation in releng
2. Implement DMS upload step
3. Integrate into Release template

**Expected Duration:** 2-3 weeks

### Phase 3: Solution Support (Part 2)
1. Implement documentation aggregation for solutions
2. Add to solution release template
3. Testing with real solutions

**Expected Duration:** 2-3 weeks

---

## Dependencies

### Systems
- **Components Registry** - add `doc` section support
- **releng (SetDistributionMojo)** - calculate doc dependencies
- **DMS Client** - upload/download documentation artifacts
- **TeamCity** - release templates modifications
- **Artifactory** - query for artifact existence

### Configuration
- `supportedDocLanguages` in Components Registry
- Documentation component registration
- Language-specific artifact naming convention

---

## Benefits

### Part 1 Benefits
1. **Traceability** - explicit link between software and documentation versions
2. **Automation** - no manual documentation artifact management
3. **Consistency** - documentation version always matches software version
4. **Multi-language support** - automatic handling of different language versions

### Part 2 Benefits
1. **Complete solution documentation** - all sub-component docs in one place
2. **Customer convenience** - single download for all solution documentation
3. **Consistency** - documentation versions match deployed component versions
4. **Reduced manual work** - no manual documentation collection

---

## Open Questions

1. **Artifact naming convention:** Should we enforce strict naming for language-specific docs?
   - Current proposal: `{artifactId}-{version}-{language}.zip`

2. **Missing documentation handling:** What if some sub-components don't have documentation?
   - Proposal: Continue, log warning, include README stub

3. **Version mismatch:** What if documentation version doesn't match component version exactly?
   - Proposal: Find closest lower version from same major.minor line

4. **Large solutions:** How to handle solutions with 50+ sub-components?
   - Proposal: Parallel download, size limit warnings, exclude optional components

5. **Index generation:** Should we auto-generate index.html for aggregated docs?
   - Proposal: Yes, with component list, versions, and links to PDFs

6. **Failure handling:** Should documentation upload failure block the release?
   - Proposal: No, log error, notify team, but proceed with release

---

## Related Documents

- [doc-components-requirements.md](doc-components-requirements.md) - Detailed requirements for `doc` section
- [doc-components-architecture.md](doc-components-architecture.md) - Architecture design
- [doc-components-implementation-examples.md](doc-components-implementation-examples.md) - Code examples
