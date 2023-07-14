## Dockerfile

This is a Dockerfile for building a container with OpenJDK 11.
By default, it uses `openjdk:11` image.
To build with custom image use `--build-arg` option:

``
docker build --build-arg IMAGE=docker.io/customspace/openjdk11:latest .
``

## Project properties

| Name                                            | Description                       |
|-------------------------------------------------|-----------------------------------|
| components-registry.supportedGroupIds           | Supported group ids.              |
| components-registry.supportedSystems            | Supported systems.                |
| components-registry.version-name.service-branch | Name of a service branch version. |
| components-registry.version-name.service        | Name of a service version.        |
| components-registry.version-name.minor          | Name of a minor version.          |