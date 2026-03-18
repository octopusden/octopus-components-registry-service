# OKD Platform Patterns Relevant to `components-registry-ui`

This file summarizes the current deployment and security conventions discovered in the existing
platform repositories and reference applications.

## Source Repositories

- `service-deployment`
- `service-config`
- `octopus-dms-ui`
- `octopus-api-gateway`

## Current Deployment Model

### TeamCity + Helm + `service-deployment`

The existing F1 deployment flow is centered on:

- TeamCity template `RnDProcessesAutomation_IdpComponentOkdDeploy`
- Helm chart `spring-cloud`
- environment values in `okd/deployments/<env>/<component>.yml`
- common defaults in `okd/deployments/<env>/default.yml`

Observed behavior from the TeamCity template:

- `COMPONENT_NAME` maps to `okd/deployments/%DEPLOYMENT_ENVIRONMENT%/%COMPONENT_NAME%.yml`
- `HELM_RELEASE` defaults to `%COMPONENT_NAME%-%DEPLOYMENT_ENVIRONMENT%`
- chart values are injected through:
  - `image.tag=%BUILD_NUMBER%`
  - `componentName=%COMPONENT_NAME%`
  - `configLabel=%DEPLOYMENT_CONFIG_LABEL%`
  - `dockerRegistry=%DOCKER_REGISTRY%`
  - `route.clusterDomain=%OKD_APPS_DOMAIN%`
  - `additionalProfile=%F1_OKD_DEPLOYMENT_ADDITIONAL_PROFILE%`
- deploy sequence is: `oc login` -> `helm repo add/update` -> `helm lint` -> `helm upgrade --dry-run` -> `helm upgrade --atomic --install`

### Helm chart `spring-cloud`

The current chart expects a Spring-style service:

- container port `8080`
- `SPRING_PROFILES_ACTIVE`
- `SPRING_CLOUD_CONFIG_LABEL`
- config injected through `envFrom` secrets:
  - `application`
  - `application-<profile>`
  - `<componentName>`
  - `<componentName>-<profile>`
- optional `Route`
- optional `ServiceMonitor`
- HTTP probes

This strongly suggests the chart is optimized for Spring Boot services, not plain nginx/static-only
frontends.

## Existing Values Examples

### `service-deployment`

Current examples in `test`:

- `dms-ui.yml`
  - `image.name: octopusden/dms-ui`
  - `route.enabled: true`
- `components-registry-service.yml`
  - `image.name: f1/components-registry-service-staging`

Shared defaults in `test/default.yml`:

- `deploymentEnvironment: test`
- `profile: cloud-qa`
- `configLabel: master`
- `replicas: 1`
- `SPRING_CLOUD_CONFIG_URI` points to in-cluster `f1-config-server`
- probes and metrics are expected

## Current Runtime Config Model

### `service-config`

Configuration hierarchy:

- `application.yml`
- `application-{profile}.yml`
- `{service}.yml`
- `{service}-{profile}.yml`

Relevant observed files:

- `dms-ui.yaml`
  - Spring Security OAuth2 client setup
  - logout URL based on `dms-ui.hostname`
- `dms-ui-cloud-qa.yaml`
  - gateway route to `dms-test.f1.svc.cluster.local:8080`
  - `dms-ui.hostname` is environment-specific and points to the external UI route host
- `components-registry-service.yml`
  - current CRS server-side service config

## Reference App: `octopus-dms-ui`

`octopus-dms-ui` is not a static frontend container. It is a Spring Boot WebFlux BFF that:

- serves frontend assets from the application JAR
- performs OAuth2 login against Keycloak
- uses Spring Cloud Gateway
- proxies `/auth/**` and `/rest/api/**`
- exposes Actuator for health and metrics
- consumes Spring Cloud Config

Operationally this is important because it fits the current `spring-cloud` chart much better than a
plain Vite/nginx SPA would.

## Reference Security/Gateway Model: `octopus-api-gateway`

The API gateway docs confirm the target direction:

- `api-gateway` is the external entry point for microservices
- Keycloak/OAuth2 and `TokenRelay` are part of the standard security model
- `dms-ui` and `api-gateway` together implement a BFF/gateway access pattern

Implication for `components-registry-ui`:

- the initial demo deployment may skip gateway/security
- the final deployment should remain compatible with a future gateway-based entrypoint
- frontend API URLs and routing must stay configurable and must not assume direct cluster-internal
  endpoints

## Decision Pressure for `components-registry-ui`

The main architecture/deployment choice that must be resolved:

1. Spring Boot BFF / embedded frontend similar to `dms-ui`
2. Separate UI service/container, likely static assets
3. Embedded UI inside `components-registry-service`

The current platform tooling most naturally supports option 1 or 3.

## Constraints That Should Be Preserved Even for Demo

- avoid hardcoded backend hosts in the frontend
- use environment-configurable API base path
- keep room for a future gateway route in front of the app
- do not design the demo deploy path in a way that blocks later TeamCity/Helm automation
- identify all manual dependencies: Keycloak client, secrets, DNS/route names, config-server data,
  service accounts, image registry naming
