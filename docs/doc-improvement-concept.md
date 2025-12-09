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
    
    // With majorVersion - pins to specific version line
    "(1,2)" {
        doc {
            component = "doc_mycomponent"
            majorVersion = '$major.$minor'  // Resolves to latest in this line
        }
    }
    
    // With explicit version line
    "(2,3)" {
        doc {
            component = "doc_mycomponent"
            majorVersion = '2'  // Resolves to latest 2.x
        }
    }
    
    // Without majorVersion - uses latest available
    "(3,)" {
        doc {
            component = "doc_mycomponent"
            // Will resolve to absolutely latest version: e.g., 3.2.18
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
        external = false //can be also true
        GAV = "org.company.docs:mycomponent-docs:zip"
    }
}
```

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
    "majorVersion": "2.5"  // Present if specified in config
  }
}
```

Or without majorVersion:
```json
{
   ...
  "component": "mycomponent",
  "version": "3.1.45",
  "doc": {
    "component": "doc_mycomponent"
    // majorVersion omitted - will resolve to latest
  }
}
```

**Doc section validation rules:**

1. `component` field is **required** when `doc` section is present
2. `majorVersion` field is **optional**
3. If `majorVersion` is specified, it must be valid (literals or variables: `$major`, `$minor`, etc.)
4. Referenced doc component must exist in registry
5. Documentation component must have `distribution.GAV` defined (artifact-based, no `file:` entities)
6. Version ranges with `doc` sections must not overlap for the same component
7. Documentation component can't have reference on other documentation component (i.e. the following MainComponentA -> DocComponentA -> DocComponentB is restricted)

### 1.2 Calculate Documentation Dependencies in Build

**Goal:** Automatically resolve and inject documentation dependencies into the build process in Compile&UT build configuration (in standard templates).

**Build step:** `CalculateReleaseManagementParameters` (SetDistributionMojo in releng)

**Implementation:**
Add calculation of TeamCity parameters:
-  `DEPENDENCIES` or separate `DOC_DEPENDENCIES`
   Resolution rule: `doc_mycomponent:<latest released version from version line in CR>` (Take data from CRS and RM service)
- `DOC_ARTIFACTS_COORDINATES` in format supported by maven-dms-plugin (Example: DOC_ARTIFACTS_COORDINATES=org.myorg.mycomponent.doc:my-documentation:zip:english,org.myorg.mycomponent.doc:my-documentation:zip:spanish)
- `DOC_ARTIFACTS_VERSION` with value = version of documentation sub-component. Example: DOC_ARTIFACTS_VERSION=1.3.9

**Example:**
```
Component: mycomponent v2.5.123
doc section: { component = "doc_mycomponent", majorVersion = "2.5" }

Resolved dependency:
DOC_DEPENDENCIES = doc_mycomponent:2.5.45  (latest released 2.5.x version)

---

Component: mycomponent v3.1.45
doc section: { component = "doc_mycomponent" }  // no majorVersion

Resolved dependency:
DOC_DEPENDENCIES = doc_mycomponent:3.2.18  (latest released version overall)
```

**Purpose:** This parameter will be passed to the standard dependency registration mechanism on the build (register-build).


### 1.4 Add Documentation Upload Step to Release Template

**Goal:** Automatically upload documentation artifacts to DMS during main component release.

**Implementation:**
- Add build step "Upload documentation to DMS" with meta-runner "Upload artifacts to DMS" to "[CD] Release" template to upload documentation artifacts of documentation sub-component.

**Parameters:**
- `ARTIFACT_TYPE=documentation`
- `ARTIFACTS_COORDINATES=%DOC_ARTIFACTS_COORDINATES%`
- `ARTIFACTS_COORDINATES_VERSION=%DOC_ARTIFACTS_COORDINATES_VERSION%`

**Note:** The `DOC_ARTIFACTS_COORDINATES` and `DOC_ARTIFACTS_COORDINATES_VERSION` variables are populated from the documentation component's distribution configuration retrieved via Components Registry Service API (see section 1.3).

**Placement:** Execute after main artifact upload

**Template addition:**
```yaml
Build Steps:
  ...
  - Register Release in JIRA
  ...
  - Upload Release Artifacts
  - Upload Documentation to DMS  ← NEW
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
1. Get solution sub-components from Release Management Service API
   GET ... (TODO)
   → returns list of sub-components with versions
   
2. For each sub-component/version:
   Query DMS for documentation artifacts:
   GET ... (TODO)
   
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

---

## Implementation Sequence

### Phase 1: Foundation (Part 1.1-1.3)
1. Implement `doc` section in Components Registry
2. Add REST API support

### Phase 2: Build Integration (Part 1.2, 1.4-1.5)
1. Implement documentation dependency calculation in releng
2. Implement DMS upload step
3. Integrate into Release template

### Phase 3: Solution Support (Part 2)
1. Implement documentation aggregation for solutions
2. Add to release template assembling of documentation zips for solutions (conditional build step)
3. Testing with real solutions


---

## Dependencies

### Systems
- **Components Registry** - add `doc` section support
- **releng (SetDistributionMojo)** - calculate doc dependencies
- **DMS Client** - upload/download documentation artifacts
- **TeamCity** - release templates modifications

### Configuration
- Documentation component registration
- Language-specific artifact naming convention

---

## Benefits

### Part 1 Benefits
1. **Traceability** - explicit link between software and documentation versions
2. **Automation** - no manual documentation artifact management

### Part 2 Benefits
1. **Complete solution documentation** - all sub-component docs in one place
2. **Customer convenience** - single download for all solution documentation
3. **Reduced manual work** - no manual documentation collection

---

## TODO Action Items

1. **`majorVersion` is optional:**
   - If **specified**: resolve to latest released version from that version line (e.g., `"2.5"` → `2.5.45`)
   - If **not specified**: resolve to latest released version across all versions of doc component
   - Example without majorVersion:
     ```groovy
     doc {
         component = "doc_mycomponent"
         // Will resolve to latest: doc_mycomponent:3.1.15
     }
     ```
DONE!

2. **No version ranges support for doc section:**
   - Doc section can be defined per version range (like distribution/build), but the `majorVersion` field itself doesn't support range syntax
   - Use version ranges at component level instead:
     ```groovy
     "(1,2)" {
         doc { component = "doc_mycomponent"; majorVersion = "1" }
     }
     "(2,)" {
         doc { component = "doc_mycomponent"; majorVersion = "2" }
     }
     ```

3. **Use `majorVersion` (not `lineVersion`):**
   - Keep consistent with existing `majorVersionFormat` terminology in jira section
   - Rename would be confusing across the codebase

4. **Validation: single doc component per version:**
   - Ensure no overlapping version ranges with different doc components
   - Validator checks that version ranges with doc sections don't create conflicts

5. **Doc components must have `distribution.GAV`:**
   - Required field for doc components
   - GAV must be artifact-based (no `file:` entities allowed)
   - Validation ensures GAV format is correct

6. **`supportedLanguages` not needed in component config:**
    DONE!

7. **Doc component identification:**
   - A component is a "doc component" if it's referenced in another component's `doc` section
   - No explicit flag needed - determined by reference

---

## Open Questions

1. **Artifact naming convention:** Should we enforce strict naming for language-specific docs?
   - Current proposal: `{artifactId}-{version}-{language}.zip`

2. **Missing documentation handling:** What if some sub-components don't have documentation?
   - Proposal: Proceed to the next

6. **Failure handling:** Should documentation upload failure block the release?
   - Proposal: Yes
---

