ARG DOCKER_REGISTRY

FROM $DOCKER_REGISTRY/eclipse-temurin:11-jdk

WORKDIR /app

EXPOSE 4567

ARG BUILD_VERSION

COPY build/libs/components-registry-service-server-$BUILD_VERSION.jar app.jar

CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
