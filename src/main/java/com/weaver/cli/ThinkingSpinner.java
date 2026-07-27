package com.weaver.cli;

/**
 * Animated thinking spinner shown while waiting for LLM response.
 * Runs on a separate thread so it doesn't block the main agent loop.
 */
public class ThinkingSpinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String CLEAR_LINE = "\r\033[K";

    private volatile boolean running = false;
    private Thread spinnerThread;
    private String message = "Thinking";

    public void start() {
        start("Thinking");
    }

    public void start(String message) {
        this.message = message;
        this.running = true;
        this.spinnerThread = new Thread(() -> {
            int frame = 0;
            while (running) {
                System.out.print(CLEAR_LINE + "\033[2m  " + FRAMES[frame % FRAMES.length] + " " + this.message + "...\033[0m");
                System.out.flush();
                frame++;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Clear the spinner line when done
            System.out.print(CLEAR_LINE);
            System.out.flush();
        });
        spinnerThread.setDaemon(true);
        spinnerThread.start();
    }

    public void stop() {
        running = false;
        if (spinnerThread != null) {
            try {
                spinnerThread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void updateMessage(String newMessage) {
        this.message = newMessage;
    }

    public boolean isRunning() {
        return running;
    }
}
