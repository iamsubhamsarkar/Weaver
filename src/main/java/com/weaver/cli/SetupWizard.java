package com.weaver.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * First-run setup wizard. Stores API keys in ~/.weaver/credentials.yml
 * Only runs once per device. User can re-run with /configure command.
 */
@Component
public class SetupWizard {

    private static final Logger log = LoggerFactory.getLogger(SetupWizard.class);
    private static final Path WEAVER_HOME = Path.of(System.getProperty("user.home"), ".weaver");
    private static final Path CREDENTIALS_FILE = WEAVER_HOME.resolve("credentials.yml");
    private static final Path EXPIRY_FILE = WEAVER_HOME.resolve("api-expiry.yml");

    private final Map<String, ProviderInfo> providers = new LinkedHashMap<>();

    public SetupWizard() {
        providers.put("groq", new ProviderInfo("Groq", "https://console.groq.com", "GROQ_API_KEY", "gsk_"));
        providers.put("gemini", new ProviderInfo("Google Gemini", "https://aistudio.google.com/apikey", "GEMINI_API_KEY", "AI"));
        providers.put("cerebras", new ProviderInfo("Cerebras", "https://cloud.cerebras.ai", "CEREBRAS_API_KEY", "csk-"));
        providers.put("mistral", new ProviderInfo("Mistral", "https://console.mistral.ai", "MISTRAL_API_KEY", ""));
        providers.put("openrouter", new ProviderInfo("OpenRouter", "https://openrouter.ai/keys", "OPENROUTER_API_KEY", "sk-or-"));
    }

    public record ProviderInfo(String displayName, String signupUrl, String envVar, String keyPrefix) {}

    /**
     * Returns true if setup has been completed (credentials file exists with at least one key).
     */
    public boolean isConfigured() {
        if (!Files.exists(CREDENTIALS_FILE)) return false;
        try {
            String content = Files.readString(CREDENTIALS_FILE);
            // Check if there's at least one non-placeholder key
            return content.lines().anyMatch(line ->
                line.contains("api-key:") && !line.contains("YOUR_") && !line.trim().endsWith(":"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Run the interactive first-time setup wizard.
     */
    public void runSetup() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("""
            \033[1;36m
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
              🧙 WEAVER FIRST-TIME SETUP
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            \033[0m
              Weaver needs at least ONE free AI API key to work.
              All providers below are FREE (no credit card needed).
              Keys are stored locally at: ~/.weaver/credentials.yml

              Press Enter to skip any provider you don't want.
            """);

        Map<String, String> keys = new LinkedHashMap<>();
        Map<String, String> expiries = new LinkedHashMap<>();

        for (Map.Entry<String, ProviderInfo> entry : providers.entrySet()) {
            String id = entry.getKey();
            ProviderInfo info = entry.getValue();

            System.out.printf("\033[1m  [%s]\033[0m %s%n", info.displayName(), info.signupUrl());
            System.out.printf("  API Key: ");
            System.out.flush();

            String key = reader.readLine();
            if (key != null) key = key.trim();

            if (key != null && !key.isEmpty()) {
                keys.put(id, key);
                System.out.printf("  \033[32m✓ %s key saved\033[0m%n", info.displayName());

                // Ask for expiry date (optional)
                System.out.printf("  Expiry date (YYYY-MM-DD) or days until expiry (e.g. 30), or Enter to skip: ");
                System.out.flush();
                String expiryInput = reader.readLine();
                if (expiryInput != null && !expiryInput.trim().isEmpty()) {
                    expiryInput = expiryInput.trim();
                    String expiryDate = parseExpiryInput(expiryInput);
                    if (expiryDate != null) {
                        expiries.put(id, expiryDate);
                        System.out.printf("  \033[33m⏰ Expiry set: %s\033[0m%n", expiryDate);
                    }
                }
            } else {
                System.out.printf("  \033[2m  Skipped\033[0m%n");
            }
            System.out.println();
        }

        if (keys.isEmpty()) {
            System.out.println("\033[1;31m  ⚠ No keys provided! Weaver won't work without at least one API key.\033[0m");
            System.out.println("  Run the app again or use /configure to set up keys later.\n");
            return;
        }

        // Save credentials
        saveCredentials(keys);
        saveExpiries(expiries);

        System.out.printf("""
            \033[1;32m
              ✓ Setup complete! %d provider(s) configured.
              Keys saved to: ~/.weaver/credentials.yml
            \033[0m
              You can re-run setup anytime with the /configure command.
            %n""", keys.size());
    }

    /**
     * Load saved credentials and return them as properties for Spring to pick up.
     */
    public Map<String, String> loadCredentials() {
        Map<String, String> props = new HashMap<>();
        if (!Files.exists(CREDENTIALS_FILE)) return props;

        try {
            List<String> lines = Files.readAllLines(CREDENTIALS_FILE);
            String currentProvider = null;

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;

                // Parse simple YAML: "groq:" or "  api-key: xxx"
                if (!line.startsWith("api-key") && line.endsWith(":")) {
                    currentProvider = line.substring(0, line.length() - 1).trim();
                } else if (line.startsWith("api-key:") && currentProvider != null) {
                    String key = line.substring("api-key:".length()).trim();
                    if (!key.isEmpty() && !key.startsWith("YOUR_")) {
                        props.put("weaver.providers." + currentProvider + ".api-key", key);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load credentials: {}", e.getMessage());
        }
        return props;
    }

    /**
     * Check API key expiry and print warnings for keys expiring soon.
     */
    public void checkExpiryWarnings() {
        if (!Files.exists(EXPIRY_FILE)) return;

        try {
            List<String> lines = Files.readAllLines(EXPIRY_FILE);
            LocalDate today = LocalDate.now();
            String currentProvider = null;

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;

                if (!line.startsWith("expiry") && line.endsWith(":")) {
                    currentProvider = line.substring(0, line.length() - 1).trim();
                } else if (line.startsWith("expiry:") && currentProvider != null) {
                    String dateStr = line.substring("expiry:".length()).trim();
                    try {
                        LocalDate expiryDate = LocalDate.parse(dateStr);
                        long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, expiryDate);

                        if (daysLeft < 0) {
                            System.out.printf("\033[1;31m  ⚠ %s API key EXPIRED %d day(s) ago! Renew at: %s\033[0m%n",
                                    getDisplayName(currentProvider), Math.abs(daysLeft),
                                    getSignupUrl(currentProvider));
                        } else if (daysLeft <= 3) {
                            System.out.printf("\033[1;31m  ⚠ %s API key expires in %d day(s)! Renew at: %s\033[0m%n",
                                    getDisplayName(currentProvider), daysLeft,
                                    getSignupUrl(currentProvider));
                        } else if (daysLeft <= 7) {
                            System.out.printf("\033[1;33m  ⏰ %s API key expires in %d day(s). Renew at: %s\033[0m%n",
                                    getDisplayName(currentProvider), daysLeft,
                                    getSignupUrl(currentProvider));
                        } else if (daysLeft <= 14) {
                            System.out.printf("\033[33m  📅 %s API key expires in %d day(s).\033[0m%n",
                                    getDisplayName(currentProvider), daysLeft);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException e) {
            log.warn("Failed to check expiry: {}", e.getMessage());
        }
    }

    /**
     * Update a single provider's key (used by /configure command for partial updates).
     */
    public void updateProvider(String providerId, String apiKey, String expiryDate) throws IOException {
        Map<String, String> existing = loadAllKeys();
        existing.put(providerId, apiKey);
        saveCredentials(existing);

        if (expiryDate != null) {
            Map<String, String> expiries = loadAllExpiries();
            expiries.put(providerId, expiryDate);
            saveExpiries(expiries);
        }
    }

    private void saveCredentials(Map<String, String> keys) throws IOException {
        Files.createDirectories(WEAVER_HOME);

        StringBuilder sb = new StringBuilder();
        sb.append("# Weaver API Credentials\n");
        sb.append("# Auto-generated by setup wizard. Edit manually or run /configure\n");
        sb.append("# Location: ~/.weaver/credentials.yml\n\n");

        for (Map.Entry<String, String> entry : keys.entrySet()) {
            sb.append(entry.getKey()).append(":\n");
            sb.append("  api-key: ").append(entry.getValue()).append("\n\n");
        }

        Files.writeString(CREDENTIALS_FILE, sb.toString());

        // Set restrictive permissions (owner-only read/write)
        try {
            Set<java.nio.file.attribute.PosixFilePermission> perms = Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(CREDENTIALS_FILE, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows doesn't support POSIX permissions
        }

        log.info("Credentials saved to {}", CREDENTIALS_FILE);
    }

    private void saveExpiries(Map<String, String> expiries) throws IOException {
        if (expiries.isEmpty()) return;
        Files.createDirectories(WEAVER_HOME);

        StringBuilder sb = new StringBuilder();
        sb.append("# Weaver API Key Expiry Dates\n");
        sb.append("# Format: YYYY-MM-DD\n\n");

        for (Map.Entry<String, String> entry : expiries.entrySet()) {
            sb.append(entry.getKey()).append(":\n");
            sb.append("  expiry: ").append(entry.getValue()).append("\n\n");
        }

        Files.writeString(EXPIRY_FILE, sb.toString());
    }

    private Map<String, String> loadAllKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        if (!Files.exists(CREDENTIALS_FILE)) return keys;
        try {
            List<String> lines = Files.readAllLines(CREDENTIALS_FILE);
            String currentProvider = null;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                if (!line.startsWith("api-key") && line.endsWith(":")) {
                    currentProvider = line.substring(0, line.length() - 1).trim();
                } else if (line.startsWith("api-key:") && currentProvider != null) {
                    String key = line.substring("api-key:".length()).trim();
                    if (!key.isEmpty()) keys.put(currentProvider, key);
                }
            }
        } catch (IOException ignored) {}
        return keys;
    }

    private Map<String, String> loadAllExpiries() {
        Map<String, String> expiries = new LinkedHashMap<>();
        if (!Files.exists(EXPIRY_FILE)) return expiries;
        try {
            List<String> lines = Files.readAllLines(EXPIRY_FILE);
            String currentProvider = null;
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                if (!line.startsWith("expiry") && line.endsWith(":")) {
                    currentProvider = line.substring(0, line.length() - 1).trim();
                } else if (line.startsWith("expiry:") && currentProvider != null) {
                    String date = line.substring("expiry:".length()).trim();
                    if (!date.isEmpty()) expiries.put(currentProvider, date);
                }
            }
        } catch (IOException ignored) {}
        return expiries;
    }

    private String parseExpiryInput(String input) {
        // Try parsing as date (YYYY-MM-DD)
        try {
            LocalDate.parse(input);
            return input;
        } catch (Exception ignored) {}

        // Try parsing as number of days
        try {
            int days = Integer.parseInt(input);
            return LocalDate.now().plusDays(days).format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (NumberFormatException ignored) {}

        return null;
    }

    private String getDisplayName(String providerId) {
        ProviderInfo info = providers.get(providerId);
        return info != null ? info.displayName() : providerId;
    }

    private String getSignupUrl(String providerId) {
        ProviderInfo info = providers.get(providerId);
        return info != null ? info.signupUrl() : "";
    }
}
