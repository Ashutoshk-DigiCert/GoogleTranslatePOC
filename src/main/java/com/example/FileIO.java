package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class FileIO {
    private static final Logger logger = LoggerFactory.getLogger(FileIO.class);

    public static List<PropertyEntry> readPropertiesFile(String inputFile) throws IOException {
        logger.info("Reading properties file: {}", inputFile);
        List<PropertyEntry> entries = new ArrayList<>();
        Path inputPath = Paths.get(inputFile);

        if (!Files.exists(inputPath)) {
            logger.warn("Source properties file does not exist: {}. Please add the source file.", inputFile);
            return entries;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8))) {
            String line;
            List<String> currentLines = new ArrayList<>();
            String currentKey = null;
            PropertyEntry.EntryType currentType = null;

            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("#")) {
                    if (currentType != null) {
                        entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                        currentLines.clear();
                    }
                    currentKey = line;
                    currentLines.add(line);
                    currentType = PropertyEntry.EntryType.COMMENT;
                } else if (line.trim().isEmpty()) {
                    if (currentType != null) {
                        entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                        currentLines.clear();
                    }
                    currentKey = "";
                    currentLines.add(line);
                    currentType = PropertyEntry.EntryType.EMPTY_LINE;
                } else {
                    int separatorIndex = line.indexOf('=');
                    if (separatorIndex > 0 && (currentType != PropertyEntry.EntryType.PROPERTY || !currentLines.get(currentLines.size() - 1).trim().endsWith("\\"))) {
                        if (currentType != null) {
                            entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                            currentLines.clear();
                        }
                        currentKey = line.substring(0, separatorIndex).trim();
                        currentLines.add(line);
                        currentType = PropertyEntry.EntryType.PROPERTY;
                    } else {
                        currentLines.add(line);
                    }
                }
            }
            if (currentType != null) {
                entries.add(new PropertyEntry(currentKey, currentLines, currentType));
            }
            logger.info("Successfully read {} entries from properties file", entries.size());
            return entries;
        } catch (IOException e) {
            logger.error("Error reading properties file {}: {}", inputFile, e.getMessage(), e);
            throw e;
        }
    }

    public static void writePropertiesUtf8(List<PropertyEntry> entries, String filename) throws IOException {
        logger.info("Writing translated properties to file: {}", filename);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename), StandardCharsets.UTF_8))) {
            for (PropertyEntry entry : entries) {
                for (String line : entry.lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            logger.info("Successfully wrote {} entries to file", entries.size());
        } catch (IOException e) {
            logger.error("Error writing properties file: {}", e.getMessage(), e);
            throw e;
        }
    }
}