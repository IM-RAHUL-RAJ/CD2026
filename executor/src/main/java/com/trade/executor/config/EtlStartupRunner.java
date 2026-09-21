package com.trade.executor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Launches the Sprint 7 DuckDB ETL trigger script in the background when the
 * executor application starts. Governed by the {@code etl.*} properties in
 * application.yml (etl.enabled, etl.python-script-path, etl.python-executable).
 * The script loads the Postgres trading data into DuckDB and keeps the Flask
 * analytics dashboard running on the frontend port.
 */
@Component
public class EtlStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EtlStartupRunner.class);

    @Value("${etl.enabled:true}")
    private boolean enabled;

    @Value("${etl.python-script-path:etl_trigger_service.py}")
    private String scriptPath;

    @Value("${etl.python-executable:python}")
    private String pythonExecutable;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("ETL startup runner disabled (etl.enabled=false)");
            return;
        }

        Path script = resolveScript(scriptPath);
        if (script == null) {
            log.error("ETL script '{}' not found; skipping ETL/dashboard startup", scriptPath);
            return;
        }

        Path logFile = script.getParent().resolve("etl_trigger_service.log");

        ProcessBuilder processBuilder = new ProcessBuilder(pythonExecutable, script.toString());
        processBuilder.directory(script.getParent().toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        log.info("Starting ETL trigger service: {} {} (logs -> {})", pythonExecutable, script, logFile);
        try {
            Process process = processBuilder.start();
            log.info("ETL trigger service started (pid {})", process.pid());
        } catch (Exception e) {
            log.error("Failed to start ETL trigger service: {}", e.getMessage(), e);
        }
    }

    /**
     * Resolves the configured script path relative to the working directory,
     * the working directory's {@code executor} subfolder, or any ancestor's
     * {@code executor} subfolder, so launching from executor/, the repo root,
     * or executor/target all work.
     */
    private Path resolveScript(String configuredPath) {
        Path configured = Paths.get(configuredPath);
        if (configured.isAbsolute() && Files.isRegularFile(configured)) {
            return configured;
        }

        Path start = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path current = start;
        for (int depth = 0; depth <= 3; depth++) {
            Path direct = current.resolve(configuredPath);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path inExecutor = current.resolve("executor").resolve(configuredPath);
            if (Files.isRegularFile(inExecutor)) {
                return inExecutor;
            }
            if (current.getParent() == null) {
                break;
            }
            current = current.getParent();
        }
        return null;
    }
}