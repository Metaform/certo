package org.metaform.certo.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * Public operational endpoints, modeled on redline's {@code InfoController}: a {@code /health} liveness probe,
 * a {@code /readiness} probe, and a {@code /info} descriptor. None is a CCM protocol path, so the siglet
 * security interceptor never applies (it is registered only on the protocol paths) — all are reachable without
 * a token, as container probes require, and they expose no tenant data.
 *
 * <p><b>Liveness vs readiness.</b> {@code /health} answers "is the process up?" and is deliberately dependency
 * free — a liveness failure makes the orchestrator <em>restart</em> the pod, so it must not depend on the DB
 * (a DB blip must not trigger a restart storm). {@code /readiness} answers "can this instance serve traffic
 * now?" and <em>does</em> verify the database connection — a readiness failure only removes the pod from
 * rotation until the dependency recovers. Wire the k8s {@code livenessProbe} to {@code /health} and the
 * {@code readinessProbe} to {@code /readiness}.
 */
@RestController
public class InfoController {

    private static final Logger LOG = LoggerFactory.getLogger(InfoController.class);
    private static final String APPLICATION = "Certo";
    // Hardcoded to match the build version (build.gradle.kts), as redline's InfoController does. No actuator
    // build-info is on the classpath; bump here if the project version changes.
    private static final String VERSION = "0.1.0";
    /** Seconds the driver may take to validate the connection before reporting the DB unreachable. */
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public InfoController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** {@code GET /health} — liveness: the process is up. Dependency-free by design (never touches the DB). */
    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "UP", "application", APPLICATION, "version", VERSION);
    }

    /**
     * {@code GET /readiness} — readiness: the process is up <em>and</em> its database is reachable. Returns
     * {@code 200 {"status":"UP"}} when a connection validates, or {@code 503 {"status":"DOWN"}} otherwise.
     */
    @GetMapping(path = "/readiness", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> readiness() {
        if (databaseReachable()) {
            return ResponseEntity.ok(Map.of("status", "UP"));
        }
        return ResponseEntity.status(SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
    }

    /** {@code GET /info} — a static service descriptor. */
    @GetMapping(path = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> info() {
        return Map.of("name", "Certo API", "description", "Company Certificate Management (CX-0135) data-plane");
    }

    private boolean databaseReachable() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            LOG.warn("Readiness check: database not reachable: {}", e.getMessage());
            return false;
        }
    }
}
