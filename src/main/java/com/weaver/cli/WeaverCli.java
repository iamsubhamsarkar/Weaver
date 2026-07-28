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

    // Track if spinner is active to avoid output conflicts
    private volatile boolean spinnerActive = false;

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

        // First-time setup
        if (!setupWizard.isConfigured()) {
            setupWizard.runSetup();
            if (setupWizard.isConfigured()) {
                System.out.println("\033[1;33m  ⟳ Restart Weaver to activate new keys.\033[0m\n");
                System.exit(0);
                return;
            }
        }

        // Hook callbacks — spinner stops BEFORE any output prints
        agent.setOutputCallback(text -> {
            stopSpinnerIfActive();
            printColored(text);
        });

        agent.setOnThinkingStart(() -> {
            spinnerActive = true;
            System.out.print("\033[2m  ⠋ Thinking...\033[0m");
            System.out.flush();
        });

        agent.setOnThinkingStop(() -> {
            stopSpinnerIfActive();
        });

        // Streaming: print token by token for the final response
        agent.setStreamTokenCallback(token -> {
            System.out.print(token);
            System.out.flush();
        });

        // Print banner
        printBanner();
        setupWizard.checkExpiryWarnings();

        // Main input loop
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            // Always show prompt cleanly on a new line
            System.out.print("\n\033[1;36mweaver>\033[0m ");
            System.out.flush();

            String input = reader.readLine();

            // Handle EOF (Ctrl+D) or quit
            if (input == null || input.trim().equalsIgnoreCase("/quit") || input.trim().equalsIgnoreCase("/exit")) {
                System.out.println("\n\033[33m👋 Goodbye!\033[0m");
                System.exit(0);
                return;
            }

            input = input.trim();
            if (input.isEmpty()) continue;

            // Slash commands
            if (input.startsWith("/")) {
                handleCommand(input);
                continue;
            }

            // Execute task
            executeTask(input);
        }
    }

    private void executeTask(String input) {
        long start = System.currentTimeMillis();
        log.info("USER INPUT: '{}'", input);

        try {
            // Run the agent (output callbacks handle real-time display)
            String response = agent.execute(input, sessionId);
            long elapsed = System.currentTimeMillis() - start;

            // Print completion separator
            System.out.println();
            System.out.println("\033[2m  ✓ Done (" + formatTime(elapsed) + ")\033[0m");

        } catch (Exception e) {
            stopSpinnerIfActive();
            System.out.println();
            System.out.println("\033[1;31m  ❌ Error: " + e.getMessage() + "\033[0m");
            log.error("Agent execution failed", e);
        }
    }

    private void stopSpinnerIfActive() {
        if (spinnerActive) {
            // Clear the spinner line
            System.out.print("\r\033[K");
            System.out.flush();
            spinnerActive = false;
        }
    }

    private String formatTime(long ms) {
        if (ms < 1000) return ms + "ms";
        return String.format("%.1fs", ms / 1000.0);
    }

    private void printColored(String text) {
        if (text == null || text.isEmpty()) return;

        if (text.contains("⚡") || text.contains("🧠")) {
            System.out.println("\033[1;34m" + text + "\033[0m");
        } else if (text.contains("🔧") || text.contains("📋")) {
            System.out.println("\033[33m" + text + "\033[0m");
        } else if (text.contains("←")) {
            System.out.println("\033[2m" + text + "\033[0m");
        } else if (text.contains("⚠")) {
            System.out.println("\033[1;33m" + text + "\033[0m");
        } else if (text.contains("🎯") || text.contains("📚")) {
            System.out.println("\033[1;35m" + text + "\033[0m");
        } else if (text.contains("🌐")) {
            System.out.println("\033[36m" + text + "\033[0m");
        } else {
            System.out.println(text);
        }
    }

    private void handleCommand(String cmd) {
        switch (cmd.toLowerCase().trim()) {
            case "/help" -> printHelp();
            case "/clear" -> {
                memoryStore.deleteMessages(sessionId);
                System.out.println("\033[33m  🧹 Conversation cleared.\033[0m");
            }
            case "/new" -> {
                sessionId = UUID.randomUUID().toString().substring(0, 8);
                System.out.println("\033[33m  🆕 New session: " + sessionId + "\033[0m");
            }
            case "/providers" -> {
                System.out.println("\n\033[1m  Registered Providers:\033[0m");
                providerRegistry.getAllProviders().forEach(p ->
                    System.out.printf("    [%d] %s (context: %dk)%n",
                            p.priority(), p.name(), p.contextWindow() / 1024));
                if (providerRegistry.getAllProviders().isEmpty()) {
                    System.out.println("    (none) — run /configure");
                }
            }
            case "/configure" -> {
                try {
                    setupWizard.runSetup();
                    if (setupWizard.isConfigured()) {
                        System.out.println("\033[1;33m  ⟳ Restart Weaver to activate new keys.\033[0m");
                    }
                } catch (Exception e) {
                    System.out.println("\033[31m  Setup failed: " + e.getMessage() + "\033[0m");
                }
            }
            case "/expiry" -> {
                System.out.println("\n\033[1m  API Key Expiry:\033[0m");
                setupWizard.checkExpiryWarnings();
            }
            case "/session" -> System.out.println("\033[2m  Session: " + sessionId + "\033[0m");
            default -> System.out.println("\033[31m  Unknown command. Type /help\033[0m");
        }
    }

    private void printBanner() {
        System.out.println("""
            \033[1;36m
            ╦ ╦┌─┐┌─┐┬  ┬┌─┐┬─┐
            ║║║├┤ ├─┤└┐┌┘├┤ ├┬┘
            ╚╩╝└─┘┴ ┴ └┘ └─┘┴└─
            \033[0m\033[2m  Autonomous Coding Agent v1.0\033[0m
            """);

        int count = providerRegistry.getAllProviders().size();
        if (count == 0) {
            System.out.println("\033[1;31m  ⚠ No providers! Run /configure\033[0m");
        } else {
            System.out.println("\033[32m  ✓ " + count + " AI provider(s) ready");
            System.out.println("  ✓ Primary: " + providerRegistry.getPrimaryProviderName() + "\033[0m");
        }
        System.out.println("\033[2m  Type /help for commands, /quit to exit\033[0m");
    }

    private void printHelp() {
        System.out.println("""

            \033[1mCommands:\033[0m
              /help        Show this help
              /configure   Set up or change API keys
              /expiry      Check API key expiry dates
              /clear       Clear conversation memory
              /new         Start new session
              /providers   List AI providers
              /quit        Exit

            \033[1mUsage:\033[0m Just type what you want:
              • Create a login page with HTML, CSS, JS
              • Fix the bug in main.py
              • Read config.yml and explain it
              • Search how to implement JWT in Node.js
            """);
    }
}
