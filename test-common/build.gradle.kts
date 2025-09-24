plugins {
    id("java-test-fixtures")
}

dependencies {
    testFixturesImplementation(project(":components-registry-service-core"))
    testFixturesImplementation(project(":component-resolver-core"))
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind:${project.properties["jackson.version"]}")
    testFixturesImplementation(platform("org.junit:junit-bom:${project.properties["junit-jupiter.version"]}"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-engine")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-params")

    testFixturesImplementation (platform("org.springframework.boot:spring-boot-dependencies:${project.properties["spring-boot.version"]}"))
    testFixturesImplementation ("org.springframework.boot:spring-boot-starter-actuator")
    testFixturesImplementation ("org.springframework.boot:spring-boot-starter-web")
    testFixturesImplementation ("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "21"
}
