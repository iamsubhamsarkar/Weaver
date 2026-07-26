package com.weaver.cli;

import com.weaver.agent.WeaverAgent;
import com.weaver.memory.WeaverMemoryStore;
import com.weaver.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "weaver.cli.enabled", havingValue = "true", matchIfMissing = true)
public class WeaverCli implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WeaverCli.class);

    private final WeaverAgent agent;
    private final ProviderRegistry providerRegistry;
    private final WeaverMemoryStore memoryStore;
    private final SetupWizard setupWizard;
    private String sessionId;

    public WeaverCli(WeaverAgent agent, ProviderRegistry providerRegistry,
                     WeaverMemoryStore memoryStore, SetupWizard setupWizard) {
        this.agent = agent;
        this.providerRegistry = providerRegistry;
        this.memoryStore = memoryStore;
        this.setupWizard = setupWizard;
    }

    @Override
    public void run(String... args) throws Exception {
        sessionId = UUID.randomUUID().toString().substring(0, 8);

        // First-time setup: if no credentials found, run wizard
        if (!setupWizard.isConfigured()) {
            setupWizard.runSetup();

            // If user provided keys, we need to restart to pick them up
            if (setupWizard.isConfigured()) {
                System.out.println("\033[1;33m  ⟳ Restart Weaver to activate new keys: mvn spring-boot:run\033[0m\n");
                System.exit(0);
                return;
            }
        }

        // Hook output callback
        agent.setOutputCallback(this::printColored);

        // Print banner
        printBanner();

        // Check and display expiry warnings
        setupWizard.checkExpiryWarnings();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String input;

        while (true) {
            System.out.print("\n\033[1;36mweaver>\033[0m ");
            System.out.flush();
            input = reader.readLine();

            if (input == null || input.trim().equalsIgnoreCase("/quit") || input.trim().equalsIgnoreCase("/exit")) {
                System.out.println("\n\033[33m👋 Goodbye!\033[0m");
                System.exit(0);
                break;
            }

            input = input.trim();
            if (input.isEmpty()) continue;

            // Handle commands
            if (input.startsWith("/")) {
                handleCommand(input);
                continue;
            }

            // Execute the agent
            try {
                long start = System.currentTimeMillis();
                String response = agent.execute(input, sessionId);
                long elapsed = System.currentTimeMillis() - start;

                System.out.println("\n\033[1;32m━━━ Response ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\033[0m");
                System.out.println(response);
                System.out.println("\033[2m(" + elapsed + "ms)\033[0m");
            } catch (Exception e) {
                System.out.println("\n\033[1;31m❌ Error: " + e.getMessage() + "\033[0m");
                log.error("Agent execution failed", e);
            }
        }
    }

    private void handleCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        switch (lower) {
            case "/help" -> printHelp();
            case "/clear" -> {
                memoryStore.deleteMessages(sessionId);
                System.out.println("\033[33m🧹 Conversation cleared.\033[0m");
            }
            case "/new" -> {
                sessionId = UUID.randomUUID().toString().substring(0, 8);
                System.out.println("\033[33m🆕 New session: " + sessionId + "\033[0m");
            }
            case "/providers" -> {
                System.out.println("\n\033[1mRegistered Providers:\033[0m");
                providerRegistry.getAllProviders().forEach(p ->
                    System.out.printf("  [%d] %s (context: %dk tokens)%n",
                            p.priority(), p.name(), p.contextWindow() / 1024));
                if (providerRegistry.getAllProviders().isEmpty()) {
                    System.out.println("  (none) - run /configure to add API keys");
                }
            }
            case "/configure" -> {
                try {
                    setupWizard.runSetup();
                    if (setupWizard.isConfigured()) {
                        System.out.println("\033[1;33m  ⟳ Restart Weaver to activate new keys.\033[0m");
                    }
                } catch (Exception e) {
                    System.out.println("\033[31mSetup failed: " + e.getMessage() + "\033[0m");
                }
            }
            case "/expiry" -> {
                System.out.println("\n\033[1mAPI Key Expiry Status:\033[0m");
                setupWizard.checkExpiryWarnings();
                System.out.println("  \033[2mSet expiry dates with /configure\033[0m");
            }
            case "/session" -> System.out.println("\033[33mSession: " + sessionId + "\033[0m");
            default -> System.out.println("\033[31mUnknown command: " + cmd + ". Type /help\033[0m");
        }
    }

    private void printColored(String text) {
        if (text.contains("⚡")) {
            System.out.println("\033[1;34m" + text + "\033[0m");
        } else if (text.contains("🔧")) {
            System.out.println("\033[33m" + text + "\033[0m");
        } else if (text.contains("←")) {
            System.out.println("\033[2m" + text + "\033[0m");
        } else if (text.contains("⚠")) {
            System.out.println("\033[31m" + text + "\033[0m");
        } else if (text.contains("🎯")) {
            System.out.println("\033[1;35m" + text + "\033[0m");
        } else {
            System.out.println(text);
        }
    }

    private void printBanner() {
        System.out.println("""
            \033[1;36m
            ╦ ╦┌─┐┌─┐┬  ┬┌─┐┬─┐
            ║║║├┤ ├─┤└┐┌┘├┤ ├┬┘
            ╚╩╝└─┘┴ ┴ └┘ └─┘┴└─
            \033[0m\033[2m  Autonomous Coding Agent v1.0
              Powered by free-tier AI APIs\033[0m
            """);

        int providerCount = providerRegistry.getAllProviders().size();
        if (providerCount == 0) {
            System.out.println("\033[1;31m  ⚠ No providers active! Run /configure to add API keys.\033[0m");
        } else {
            System.out.println("\033[32m  ✓ " + providerCount + " AI provider(s) ready\033[0m");
            System.out.println("\033[32m  ✓ Primary: " + providerRegistry.getPrimaryProviderName() + "\033[0m");
        }
        System.out.println("\033[2m  Type /help for commands, /quit to exit\033[0m");
    }

    private void printHelp() {
        System.out.println("""
            \033[1m
            Commands:
            \033[0m  /help        Show this help
              /configure   Run API key setup wizard (add/change keys)
              /expiry      Check API key expiry status
              /clear       Clear conversation memory
              /new         Start a new session
              /providers   List registered AI providers
              /session     Show current session ID
              /quit        Exit Weaver

            \033[1mUsage:\033[0m
              Just type what you want done. Examples:
              • "Read main.py and add error handling to the parse function"
              • "Create a REST API with Express.js for a todo app"
              • "Fix the failing test in UserServiceTest.java"
              • "Search for how to implement JWT auth in Spring Boot"
            """);
    }
}
