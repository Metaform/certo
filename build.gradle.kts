plugins {
    java
    id("org.springframework.boot") version "4.0.6"
}

group = "org.metaform"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.6"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // OAuth2/JWT authentication + scope authorization on the management API (/management/**). The CCM
    // protocol surface keeps its own siglet-token interceptor and is untouched by Spring Security.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Persistence: Spring Data JPA (Hibernate) over one datasource — H2 (embedded) for dev/test,
    // Postgres for the `prod` profile. Optimistic locking (@Version) + @Transactional provide the
    // concurrency control that replaces the former in-memory JVM locks.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    // NATS/JetStream client for publishing certificate-exchange events onto the platform's shared
    // `edc-events` stream. Version-matched to the platform's EDC events-nats bridge and the CX-VE
    // onboarding API, so all three speak to the same server through the same client.
    implementation("io.nats:jnats:2.25.3")

    // HTTP client used by the consumer to retrieve certificates from a provider's data plane.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Failsafe: retry with exponential backoff around outbound OkHttp calls — the same retry library
    // Eclipse Dataspace Components uses for its EdcHttpClient.
    implementation("dev.failsafe:failsafe:3.3.2")

    // JOSE/JWT + JWKS for verifying siglet-minted security tokens on the CCM protocol layer.
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    // Ed25519 (EdDSA) support for nimbus-jose-jwt — siglet signs with Ed25519 by default.
    implementation("com.google.crypto.tink:tink:1.13.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        // Mockito is unused; excluding it stops its inline mock-maker from self-attaching a
        // ByteBuddy Java agent at test time (which JDK 25 warns about and will later disallow).
        exclude(group = "org.mockito")
    }
    // Spring Boot 4 splits the MockMvc test slice (@AutoConfigureMockMvc / @WebMvcTest) into its own module.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    // Fake provider endpoint for asserting the consumer's outbound acceptance callback.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // MockMvc security integration (auto-applies the filter chain) for the management-API auth tests.
    testImplementation("org.springframework.security:spring-security-test")
    // A real NATS server for the event-publishing test: the @DomainEvents -> after-commit -> JetStream
    // path spans Spring Data, the transaction manager and the NATS client, and only an end-to-end run
    // proves it. Requires Docker; the test skips itself when none is available.
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Sample certificate seeding is off by default; the tests rely on the seeded certificates.
    systemProperty("certo.seed-sample-data", "true")
}

// Build the container image from the Dockerfile via Gradle: `./gradlew dockerBuild`.
// The multi-stage Dockerfile compiles the app itself (bootJar), so this task only invokes `docker build`.
// (Spring Boot's plugin also provides `bootBuildImage` — a buildpacks image with no Dockerfile — for free.)
tasks.register<Exec>("dockerBuild") {
    group = "docker"
    description = "Builds the certo image (metaform/certo:$version and :latest) from the Dockerfile."
    commandLine("docker", "build", "-t", "metaform/certo:$version", "-t", "metaform/certo:latest", ".")
}
