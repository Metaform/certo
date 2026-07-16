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

    // Persistence: Spring Data JPA (Hibernate) over one datasource — H2 (embedded) for dev/test,
    // Postgres for the `prod` profile. Optimistic locking (@Version) + @Transactional provide the
    // concurrency control that replaces the former in-memory JVM locks.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Sample certificate seeding is off by default; the tests rely on the seeded certificates.
    systemProperty("certo.seed-sample-data", "true")
}
