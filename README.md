## Dockerfile

This is a Dockerfile for building a container with OpenJDK 11.
By default, it uses `openjdk:11` image.
To build with custom image use `--build-arg` option:

``
docker build --build-arg IMAGE=docker.io/customspace/openjdk11:latest .
``
