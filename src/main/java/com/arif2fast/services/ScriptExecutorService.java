package com.arif2fast.services;

import com.arif2fast.models.ExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
@Slf4j
public class ScriptExecutorService {

    @Value("${file.manager.script.executor.command:cmd}")
    private String executorCommand;

    public ExecutionResult executeScript(String commandScript) {
        return executeScript(commandScript, null);
    }

    public ExecutionResult executeScript(String commandScript, String workingDirectory) {
        log.info("Executing script: {}", commandScript);
        return execute(commandScript, workingDirectory, false, null);
    }

    public ExecutionResult executeUpdateCommand(List<String> modules, String workingDirectory) {
        String moduleList = (modules == null || modules.isEmpty()) ? "" : String.join(",", modules);
        log.info("Executing Liquibase update for modules: {} in directory: {}",
                moduleList.isEmpty() ? "ALL" : moduleList, workingDirectory);

        // Build the Liquibase command
        String commandScript = buildLiquibaseCommand(modules);
        log.info("Command to execute: {}", commandScript);

        return executeScript(commandScript, workingDirectory);
    }

    public ExecutionResult executeUpdateCommandWithRealtimeOutput(List<String> modules, String workingDirectory,
            Consumer<String> outputConsumer) {
        String moduleList = (modules == null || modules.isEmpty()) ? "" : String.join(",", modules);
        log.info("Executing Liquibase update (realtime) for modules: {} in directory: {}",
                moduleList.isEmpty() ? "ALL" : moduleList, workingDirectory);

        String commandScript = buildLiquibaseCommand(modules);
        log.info("Command to execute: {}", commandScript);

        return executeScriptWithRealtimeOutput(commandScript, workingDirectory, outputConsumer);
    }

    public ExecutionResult executeScriptWithRealtimeOutput(String commandScript, String workingDirectory,
            Consumer<String> outputConsumer) {
        log.info("Executing script (realtime): {}", commandScript);
        return execute(commandScript, workingDirectory, true, outputConsumer);
    }

    private String buildLiquibaseCommand(List<String> modules) {
        String moduleList = (modules == null || modules.isEmpty()) ? "" : String.join(",", modules);
        StringBuilder liquibaseCmd = new StringBuilder("liquibase --prop-env=IDEV");
        if (!moduleList.isEmpty()) {
            liquibaseCmd.append(" --include-modules=").append(moduleList);
        }
        liquibaseCmd.append(" update");
        return liquibaseCmd.toString();
    }

    private ExecutionResult execute(String commandScript, String workingDirectory, boolean mergeErrorStream,
            Consumer<String> outputConsumer) {
        try {
            List<String> command = buildCommand(commandScript);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            if (workingDirectory != null && !workingDirectory.isEmpty()) {
                processBuilder.directory(new File(workingDirectory));
            }
            processBuilder.redirectErrorStream(mergeErrorStream);

            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        if (outputConsumer != null) {
                            outputConsumer.accept(line);
                        }
                    }
                } catch (IOException e) {
                    log.error("Error reading stdout", e);
                }
            });

            Thread errorThread = null;
            if (!mergeErrorStream) {
                errorThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            error.append(line).append("\n");
                        }
                    } catch (IOException e) {
                        log.error("Error reading stderr", e);
                    }
                });
                errorThread.start();
            }

            outputThread.start();
            outputThread.join();
            if (errorThread != null) {
                errorThread.join();
            }

            int exitCode = process.waitFor();
            log.info("Script execution completed with exit code: {}", exitCode);

            return ExecutionResult.builder()
                    .exitCode(exitCode)
                    .output(output.toString())
                    .error(error.toString())
                    .success(exitCode == 0)
                    .build();

        } catch (IOException | InterruptedException e) {
            log.error("Error executing script: {}", commandScript, e);
            String errorMsg = "Execution failed: " + e.getMessage();
            if (outputConsumer != null) {
                outputConsumer.accept("[ERROR] " + errorMsg);
            }
            return ExecutionResult.builder()
                    .exitCode(-1)
                    .output("")
                    .error(errorMsg)
                    .success(false)
                    .build();
        }
    }

    private List<String> buildCommand(String commandScript) {
        List<String> command = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            command.add("cmd");
            command.add("/c");
            command.add(commandScript);
        } else {
            command.add("bash");
            command.add("-c");
            command.add(commandScript);
        }

        return command;
    }
}
