# Build stage
# Gradle 9.5 on JDK 21 runs the wrapper; certo's Gradle toolchain auto-provisions JDK 25 (Foojay) for the
# actual compile, exactly as it builds locally.
FROM gradle:9.5-jdk21 AS build
WORKDIR /app

# Copy gradle files for dependency caching (Kotlin DSL)
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src ./src

# Build the application. bootJar (not build/assemble) produces a single executable fat jar — no -plain.jar.
RUN ./gradlew bootJar --no-daemon

# Runtime stage — JRE 25 (certo is compiled to Java 25 bytecode)
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Create non-root user
RUN addgroup -S certo && adduser -S certo -G certo
USER certo:certo

# Copy the built jar from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Expose application port
EXPOSE 8080

# Default to the prod profile (Postgres + a real siglet). Supply the datasource and
# certo.security.siglet-base-url via environment at run time.
ENV SPRING_PROFILES_ACTIVE=prod

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
