package com.weaver.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class CodebaseTools {

    private static final Logger log = LoggerFactory.getLogger(CodebaseTools.class);

    @Tool("Read the full contents of a file at the given path. Returns the file content as a string.")
    public String readFile(String path) {
        try {
            Path filePath = Path.of(path).toAbsolutePath().normalize();
            if (!Files.exists(filePath)) {
                return "ERROR: File not found: " + filePath;
            }
            if (Files.size(filePath) > 500_000) {
                return "ERROR: File too large (>500KB). Use readFileLines for large files.";
            }
            String content = Files.readString(filePath);
            log.info("📄 Read file: {} ({} chars)", filePath, content.length());
            return content;
        } catch (IOException e) {
            return "ERROR reading file: " + e.getMessage();
        }
    }

    @Tool("Read specific lines from a file. Parameters: path, startLine (1-based), endLine (1-based, inclusive).")
    public String readFileLines(String path, int startLine, int endLine) {
        try {
            Path filePath = Path.of(path).toAbsolutePath().normalize();
            if (!Files.exists(filePath)) {
                return "ERROR: File not found: " + filePath;
            }
            List<String> lines = Files.readAllLines(filePath);
            int start = Math.max(0, startLine - 1);
            int end = Math.min(lines.size(), endLine);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(String.format("%4d | %s%n", i + 1, lines.get(i)));
            }
            return sb.toString();
        } catch (IOException e) {
            return "ERROR reading file lines: " + e.getMessage();
        }
    }

    @Tool("List files and directories at the given path. Shows a tree structure up to 2 levels deep.")
    public String listDirectory(String path) {
        try {
            Path dirPath = Path.of(path).toAbsolutePath().normalize();
            if (!Files.exists(dirPath)) {
                return "ERROR: Directory not found: " + dirPath;
            }
            if (!Files.isDirectory(dirPath)) {
                return "ERROR: Not a directory: " + dirPath;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(dirPath).append("/\n");
            listDirRecursive(dirPath, sb, "", 0, 2);
            log.info("📁 Listed directory: {}", dirPath);
            return sb.toString();
        } catch (IOException e) {
            return "ERROR listing directory: " + e.getMessage();
        }
    }

    private void listDirRecursive(Path dir, StringBuilder sb, String indent, int depth, int maxDepth) throws IOException {
        if (depth >= maxDepth) return;
        try (Stream<Path> stream = Files.list(dir).sorted()) {
            List<Path> entries = stream
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .filter(p -> !p.getFileName().toString().equals("node_modules"))
                .filter(p -> !p.getFileName().toString().equals("target"))
                .filter(p -> !p.getFileName().toString().equals("build"))
                .collect(Collectors.toList());

            for (int i = 0; i < entries.size(); i++) {
                Path entry = entries.get(i);
                boolean isLast = (i == entries.size() - 1);
                String connector = isLast ? "└── " : "├── ";
                String childIndent = indent + (isLast ? "    " : "│   ");

                if (Files.isDirectory(entry)) {
                    sb.append(indent).append(connector).append(entry.getFileName()).append("/\n");
                    listDirRecursive(entry, sb, childIndent, depth + 1, maxDepth);
                } else {
                    sb.append(indent).append(connector).append(entry.getFileName()).append("\n");
                }
            }
        }
    }

    @Tool("Write content to a file. Creates the file if it doesn't exist, creates parent directories as needed.")
    public String writeFile(String path, String content) {
        try {
            Path filePath = Path.of(path).toAbsolutePath().normalize();
            Files.createDirectories(filePath.getParent());

            // Safety check: don't overwrite a larger file with significantly smaller content
            if (Files.exists(filePath)) {
                long existingSize = Files.size(filePath);
                long newSize = content.length();
                if (existingSize > 500 && newSize < existingSize / 3) {
                    log.warn("BLOCKED: writeFile would shrink {} from {} to {} chars (>66% reduction)",
                            filePath, existingSize, newSize);
                    return "ERROR: Refusing to overwrite " + filePath + " (" + existingSize
                            + " chars) with much smaller content (" + newSize
                            + " chars). Use editFile for modifications, or delete the file first if you intend to replace it.";
                }
            }

            Files.writeString(filePath, content);
            log.info("✍️ Wrote file: {} ({} chars)", filePath, content.length());
            return "Successfully wrote " + content.length() + " characters to " + filePath;
        } catch (IOException e) {
            return "ERROR writing file: " + e.getMessage();
        }
    }

    @Tool("Replace a specific string in a file with a new string. Use for surgical edits. Parameters: path, oldString, newString.")
    public String editFile(String path, String oldString, String newString) {
        try {
            Path filePath = Path.of(path).toAbsolutePath().normalize();
            if (!Files.exists(filePath)) {
                return "ERROR: File not found: " + filePath;
            }
            String content = Files.readString(filePath);
            if (!content.contains(oldString)) {
                return "ERROR: oldString not found in file. Make sure it matches exactly (including whitespace).";
            }
            String newContent = content.replace(oldString, newString);
            Files.writeString(filePath, newContent);

            // Generate and display colored diff
            String diff = com.weaver.cli.DiffDisplay.generateReplacementDiff(
                    filePath.toString(), oldString, newString);
            System.out.println(diff);

            log.info("✏️ Edited file: {} (replaced {} chars)", filePath, oldString.length());
            return "Successfully replaced text in " + filePath;
        } catch (IOException e) {
            return "ERROR editing file: " + e.getMessage();
        }
    }

    @Tool("Search for files containing a text pattern (case-insensitive). Parameters: directory path, search pattern. Returns matching file paths and lines.")
    public String searchFiles(String directory, String pattern) {
        try {
            Path dirPath = Path.of(directory).toAbsolutePath().normalize();
            if (!Files.exists(dirPath)) {
                return "ERROR: Directory not found: " + dirPath;
            }
            List<String> results = new ArrayList<>();
            String lowerPattern = pattern.toLowerCase();

            Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() > 200_000) return FileVisitResult.CONTINUE;
                    try {
                        String content = Files.readString(file);
                        String[] lines = content.split("\n");
                        for (int i = 0; i < lines.length; i++) {
                            if (lines[i].toLowerCase().contains(lowerPattern)) {
                                results.add(file + ":" + (i + 1) + ": " + lines[i].trim());
                                if (results.size() >= 30) return FileVisitResult.TERMINATE;
                            }
                        }
                    } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (name.equals("node_modules") || name.equals(".git") || name.equals("target")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (results.isEmpty()) {
                return "No matches found for '" + pattern + "' in " + dirPath;
            }
            log.info("🔍 Search found {} matches for '{}'", results.size(), pattern);
            return String.join("\n", results);
        } catch (IOException e) {
            return "ERROR searching files: " + e.getMessage();
        }
    }
}
