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

    // HTTP client used by the consumer to retrieve certificates from a provider's data plane.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

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
    // The demo certificate seeding is off by default; the tests rely on the seeded certificates.
    systemProperty("certo.seed-demo-data", "true")
}
