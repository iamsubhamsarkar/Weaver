package com.weaver.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class ShellTool {

    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);
    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_CHARS = 10000;

    @Tool("Execute a shell command and return its output. Parameters: command (the shell command to run), workingDirectory (optional, defaults to current directory).")
    public String runCommand(String command, String workingDirectory) {
        try {
            Path workDir = (workingDirectory != null && !workingDirectory.isBlank())
                    ? Path.of(workingDirectory).toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.dir"));

            log.info("Executing: {} (in {})", command, workDir);

            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > MAX_OUTPUT_CHARS) {
                        output.append("\n... [OUTPUT TRUNCATED] ...\n");
                        break;
                    }
                }
            }

            boolean completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "ERROR: Command timed out after " + TIMEOUT_SECONDS + " seconds.\nPartial output:\n" + output;
            }

            int exitCode = process.exitValue();
            String result = output.toString();

            if (exitCode != 0) {
                return "Command exited with code " + exitCode + ":\n" + result;
            }

            return result.isEmpty() ? "(command produced no output)" : result;
        } catch (Exception e) {
            return "ERROR executing command: " + e.getMessage();
        }
    }

    @Tool("Execute a shell command in the current working directory.")
    public String run(String command) {
        return runCommand(command, null);
    }
}
