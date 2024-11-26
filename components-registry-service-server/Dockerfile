ARG IMAGE=eclipse-temurin:11-jdk
FROM $IMAGE

ARG BUILD_VERSION

WORKDIR /app

EXPOSE 4567

COPY build/libs/components-registry-service-server-${BUILD_VERSION:-1.0-SNAPSHOT}.jar app.jar
CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
