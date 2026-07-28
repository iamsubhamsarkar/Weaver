package com.weaver.tools;

import com.weaver.config.OsDetector;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ShellTool {

    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);
    private static final int TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_CHARS = 10000;

    private final OsDetector osDetector;

    // Linux/macOS destructive patterns
    private static final List<String> UNIX_DESTRUCTIVE_PATTERNS = List.of(
            "rm -rf", "rm -r /", "rmdir",
            "git reset --hard", "git push --force", "git push -f",
            "git clean -f", "git branch -D",
            "drop table", "drop database", "truncate table",
            "mkfs", "dd if=",
            "> /dev/", "chmod 777",
            "kill -9", "killall",
            "shutdown", "reboot",
            ":(){ :|:& };:",
            "mv / ", "rm /*"
    );

    // Commands that should be silently blocked (self-referential or nonsensical)
    private static final List<String> BLOCKED_COMMANDS = List.of(
            "weaver", "npm init", "npm start", "npm install"
    );

    // Windows destructive patterns
    private static final List<String> WINDOWS_DESTRUCTIVE_PATTERNS = List.of(
            "rmdir /s", "rd /s",
            "del /f", "del /s", "del /q *",
            "format c:", "format d:",
            "git reset --hard", "git push --force", "git push -f",
            "git clean -f", "git branch -D",
            "drop table", "drop database", "truncate table",
            "shutdown /s", "shutdown /r",
            "taskkill /f",
            "reg delete",
            "diskpart",
            "bcdedit"
    );

    public ShellTool(OsDetector osDetector) {
        this.osDetector = osDetector;
    }

    @Tool("Execute a shell command and return its output. Parameters: command (the shell command to run), workingDirectory (optional, defaults to current directory).")
    public String runCommand(String command, String workingDirectory) {
        try {
            // Block self-referential or nonsensical commands silently
            if (isBlocked(command)) {
                return "SKIPPED: Command '" + command + "' is not applicable in this environment.";
            }

            // Check for destructive commands and ask for confirmation
            if (isDestructive(command)) {
                String confirmation = askConfirmation(command);
                if (confirmation != null) {
                    return confirmation; // User denied
                }
            }

            Path workDir = (workingDirectory != null && !workingDirectory.isBlank())
                    ? Path.of(workingDirectory).toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.dir"));

            log.info("Executing: {} (in {})", command, workDir);

            // Use OsDetector to build the correct ProcessBuilder for this OS
            ProcessBuilder pb = osDetector.buildProcess(command);
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

    private boolean isBlocked(String command) {
        String lower = command.toLowerCase().trim();
        return BLOCKED_COMMANDS.stream().anyMatch(blocked -> lower.equals(blocked) || lower.startsWith(blocked + " "));
    }

    private boolean isDestructive(String command) {
        String lower = command.toLowerCase().trim();
        List<String> patterns = osDetector.isWindows()
                ? WINDOWS_DESTRUCTIVE_PATTERNS
                : UNIX_DESTRUCTIVE_PATTERNS;
        return patterns.stream().anyMatch(lower::contains);
    }

    /**
     * Ask user for confirmation before running a destructive command.
     * Returns null if confirmed (proceed), or an error message if denied.
     */
    private String askConfirmation(String command) {
        try {
            System.out.println();
            System.out.println("\033[1;33m  ⚠️  DESTRUCTIVE COMMAND DETECTED\033[0m");
            System.out.println("\033[33m  ┌─────────────────────────────────────────────────┐\033[0m");
            System.out.println("\033[33m  │\033[0m " + truncateForDisplay(command, 47) + " \033[33m│\033[0m");
            System.out.println("\033[33m  └─────────────────────────────────────────────────┘\033[0m");
            System.out.print("\033[1m  Allow this command? [y/N]: \033[0m");
            System.out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String response = reader.readLine();

            if (response != null && (response.trim().equalsIgnoreCase("y") || response.trim().equalsIgnoreCase("yes"))) {
                System.out.println("\033[32m  ✓ Approved\033[0m");
                return null; // Proceed
            } else {
                System.out.println("\033[31m  ✗ Denied\033[0m");
                return "BLOCKED: User denied execution of destructive command: " + command;
            }
        } catch (Exception e) {
            return "BLOCKED: Could not get user confirmation for destructive command.";
        }
    }

    private String truncateForDisplay(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen - 3) + "..." : text;
    }
}
