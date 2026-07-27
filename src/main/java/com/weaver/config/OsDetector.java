package com.weaver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Detects the OS and determines the appropriate shell to use.
 * Supports override via ~/.weaver/config.yml for power users.
 *
 * Auto-detection (default):
 *   - Linux/macOS: /bin/sh -c
 *   - Windows: cmd.exe /c
 *
 * Override in ~/.weaver/config.yml:
 *   shell: powershell -Command
 *   shell: /bin/bash -c
 *   shell: wsl bash -c
 */
@Component
public class OsDetector {

    private static final Logger log = LoggerFactory.getLogger(OsDetector.class);
    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".weaver", "config.yml");

    public enum OsType { WINDOWS, LINUX, MACOS, UNKNOWN }

    private final OsType osType;
    private final String shellCommand;
    private final String shellFlag;
    private final boolean isWindows;

    public OsDetector() {
        this.osType = detectOs();
        this.isWindows = (osType == OsType.WINDOWS);

        // Check for user override
        String[] shellOverride = loadShellOverride();
        if (shellOverride != null) {
            this.shellCommand = shellOverride[0];
            this.shellFlag = shellOverride.length > 1 ? shellOverride[1] : "";
            log.info("Shell override from config: {} {}", shellCommand, shellFlag);
        } else {
            // Default based on OS
            if (isWindows) {
                this.shellCommand = "cmd.exe";
                this.shellFlag = "/c";
            } else {
                this.shellCommand = "/bin/sh";
                this.shellFlag = "-c";
            }
        }

        log.info("OS detected: {} | Shell: {} {}", osType, shellCommand, shellFlag);
    }

    public OsType getOsType() {
        return osType;
    }

    public String getOsName() {
        return switch (osType) {
            case WINDOWS -> "Windows";
            case LINUX -> "Linux";
            case MACOS -> "macOS";
            default -> "Unknown";
        };
    }

    public String getShellCommand() {
        return shellCommand;
    }

    public String getShellFlag() {
        return shellFlag;
    }

    public boolean isWindows() {
        return isWindows;
    }

    /**
     * Build a ProcessBuilder for executing a shell command on any OS.
     */
    public ProcessBuilder buildProcess(String command) {
        if (shellFlag.isEmpty()) {
            return new ProcessBuilder(shellCommand, command);
        }
        return new ProcessBuilder(shellCommand, shellFlag, command);
    }

    /**
     * Save shell override to ~/.weaver/config.yml
     */
    public void saveShellOverride(String shell) throws IOException {
        Path configDir = CONFIG_FILE.getParent();
        Files.createDirectories(configDir);

        // Read existing config or start fresh
        StringBuilder config = new StringBuilder();
        if (Files.exists(CONFIG_FILE)) {
            List<String> lines = Files.readAllLines(CONFIG_FILE);
            boolean shellFound = false;
            for (String line : lines) {
                if (line.trim().startsWith("shell:")) {
                    config.append("shell: ").append(shell).append("\n");
                    shellFound = true;
                } else {
                    config.append(line).append("\n");
                }
            }
            if (!shellFound) {
                config.append("shell: ").append(shell).append("\n");
            }
        } else {
            config.append("# Weaver Configuration\n");
            config.append("# Auto-detected OS: ").append(osType).append("\n\n");
            config.append("shell: ").append(shell).append("\n");
        }

        Files.writeString(CONFIG_FILE, config.toString());
        log.info("Shell override saved to {}", CONFIG_FILE);
    }

    private static OsType detectOs() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) return OsType.WINDOWS;
        if (osName.contains("mac") || osName.contains("darwin")) return OsType.MACOS;
        if (osName.contains("linux") || osName.contains("nix") || osName.contains("nux")) return OsType.LINUX;
        return OsType.UNKNOWN;
    }

    /**
     * Load shell override from ~/.weaver/config.yml
     * Returns [command, flag] or null if no override.
     */
    private String[] loadShellOverride() {
        if (!Files.exists(CONFIG_FILE)) return null;

        try {
            List<String> lines = Files.readAllLines(CONFIG_FILE);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("shell:")) {
                    String shellValue = trimmed.substring("shell:".length()).trim();
                    if (!shellValue.isEmpty()) {
                        // Split into command and flag: "cmd.exe /c" -> ["cmd.exe", "/c"]
                        String[] parts = shellValue.split("\\s+", 2);
                        return parts;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read config: {}", e.getMessage());
        }
        return null;
    }
}
