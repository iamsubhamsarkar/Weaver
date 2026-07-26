package com.weaver.config;

import com.weaver.cli.SetupWizard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads API keys from ~/.weaver/credentials.yml into the Spring Environment
 * before any beans are created. This makes saved keys available to ProviderRegistry.
 */
public class CredentialsInitializer implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CredentialsInitializer.class);
    private static final Path CREDENTIALS_FILE = Path.of(System.getProperty("user.home"), ".weaver", "credentials.yml");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Files.exists(CREDENTIALS_FILE)) return;

        Map<String, Object> props = new HashMap<>();

        try {
            List<String> lines = Files.readAllLines(CREDENTIALS_FILE);
            String currentProvider = null;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue;

                if (!trimmed.startsWith("api-key") && trimmed.endsWith(":")) {
                    currentProvider = trimmed.substring(0, trimmed.length() - 1).trim();
                } else if (trimmed.startsWith("api-key:") && currentProvider != null) {
                    String key = trimmed.substring("api-key:".length()).trim();
                    if (!key.isEmpty() && !key.startsWith("YOUR_")) {
                        props.put("weaver.providers." + currentProvider + ".api-key", key);
                    }
                }
            }
        } catch (Exception e) {
            // Silently fail - keys can come from other sources
        }

        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("weaverCredentials", props));
        }
    }
}
