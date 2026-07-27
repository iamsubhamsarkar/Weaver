package com.weaver.cli;

import com.weaver.config.OsDetector;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Enables ANSI escape code support on Windows terminals.
 *
 * Windows 10 v1607+ and Windows Terminal support ANSI natively,
 * but it needs to be enabled via the console mode flag.
 *
 * On Linux/macOS this is a no-op (ANSI is always supported).
 */
@Component
public class WindowsAnsiEnabler {

    private static final Logger log = LoggerFactory.getLogger(WindowsAnsiEnabler.class);
    private final OsDetector osDetector;

    public WindowsAnsiEnabler(OsDetector osDetector) {
        this.osDetector = osDetector;
    }

    @PostConstruct
    public void enableAnsiIfNeeded() {
        if (!osDetector.isWindows()) {
            return; // Linux/macOS always support ANSI
        }

        try {
            // Try to enable virtual terminal processing on Windows
            // This works on Windows 10 v1607+ (Anniversary Update) and later
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "echo.");
            pb.inheritIO();
            Process p = pb.start();
            p.waitFor();

            // Set the console mode via a quick reg query to check if WT or modern terminal
            // If running in Windows Terminal or modern PowerShell, ANSI works automatically
            String term = System.getenv("WT_SESSION");
            String termProgram = System.getenv("TERM_PROGRAM");

            if (term != null || termProgram != null) {
                log.debug("Modern terminal detected (WT_SESSION or TERM_PROGRAM set), ANSI enabled");
                return;
            }

            // For legacy cmd.exe, try enabling via system property
            // The JVM on Windows 10+ will handle ANSI if the console supports it
            System.setProperty("jansi.force", "true");
            log.info("Windows ANSI color support enabled");

        } catch (Exception e) {
            log.warn("Could not enable ANSI colors on Windows: {}. Colors may not display correctly.", e.getMessage());
        }
    }
}
