package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class GoogleTranslateService {
    private static final Logger logger = LoggerFactory.getLogger(GoogleTranslateService.class);

   public static void main(String[] args) {
    logger.info("Starting Google Translate Service");
    if (args.length < 1) {
        logger.error("No arguments provided");
        logger.error("Usage: java GoogleTranslateService <targetLanguage1> <targetLanguage2> ... [deleteGlossary]");
        logger.error("   or: java GoogleTranslateService <targetLanguage> updateGlossary <glossaryPath>");
        logger.error("   or: java GoogleTranslateService <targetLanguage1> <targetLanguage2> ... --previous <previousVersionFile>");
        System.exit(1);
    }

    try {
        ConfigManager configManager = new ConfigManager();
        TranslationService translationService = new TranslationService(configManager);
        GlossaryManager glossaryManager = new GlossaryManager(configManager);

        if (args.length >= 3 && args[1].equals("updateGlossary")) {
            processMultipleGlossaryUpdates(args, glossaryManager);
            return;
        }

        List<String> targetLanguages = new ArrayList<>();
        String previousFile = null;
        boolean shouldDeleteGlossary = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--previous") && i + 1 < args.length) {
                previousFile = args[i + 1];
                i++; // Skip the next argument since it's the file path
            } else if (args[i].equals("deleteGlossary")) {
                shouldDeleteGlossary = true;
            } else {
                targetLanguages.add(args[i]);
            }
        }

        if (shouldDeleteGlossary) {
            for (String targetLanguage : targetLanguages) {
                try {
                    logger.info("Deleting glossary for language: {}", targetLanguage);
                    glossaryManager.deleteGlossary(targetLanguage);
                    logger.info("Successfully deleted glossary for language: {}", targetLanguage);
                } catch (IOException e) {
                    logger.error("Error deleting glossary for language {}: {}", targetLanguage, e.getMessage(), e);
                }
            }
            return;
        }

        for (String targetLanguage : targetLanguages) {
            try {
                logger.info("Processing translation for language: {}", targetLanguage);
                String inputPropsFile = configManager.getInputFilePath();
                String outputPropsFile = configManager.getOutputFilePath(targetLanguage);

                logger.info("Reading source properties from: {}", inputPropsFile);
                List<PropertyEntry> originalEntries = FileIO.readPropertiesFile(inputPropsFile);

                if (originalEntries.isEmpty()) {
                    logger.warn("No entries found in the source properties file: {}. Skipping translation for language: {}", inputPropsFile, targetLanguage);
                    continue;
                }

                logger.info("Starting translation process for {} entries", originalEntries.size());
                List<PropertyEntry> translatedEntries = translationService.translateProperties(originalEntries, targetLanguage, previousFile);

                Path outputPath = Paths.get(outputPropsFile);
                Files.createDirectories(outputPath.getParent());

                logger.info("Writing translated properties to: {}", outputPropsFile);
                FileIO.writePropertiesUtf8(translatedEntries, outputPath.toString());
            } catch (IOException e) {
                logger.error("Error processing language {}: {}", targetLanguage, e.getMessage(), e);
            }
        }

        if (previousFile != null) {
            String inputPropsFile = configManager.getInputFilePath();
            Files.copy(Paths.get(inputPropsFile), Paths.get(previousFile), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Updated backup file: {}", previousFile);
        }

    } catch (Exception e) {
        logger.error("Fatal error during execution: {}", e.getMessage(), e);
        System.exit(1);
    }
}

    private static void processMultipleGlossaryUpdates(String[] args, GlossaryManager glossaryManager) {
        logger.info("Processing multiple glossary updates");

        for (int i = 0; i < args.length; i += 3) {
            if (i + 2 >= args.length) {
                logger.error("Incomplete arguments for glossary update at position {}", i);
                continue;
            }

            String targetLanguage = args[i];
            String command = args[i + 1];
            String glossaryPath = args[i + 2];

            if (!"updateGlossary".equals(command)) {
                logger.error("Invalid command '{}' for language {}. Expected 'updateGlossary'", command, targetLanguage);
                continue;
            }

            try {
                logger.info("Processing glossary update for language: {} with file: {}", targetLanguage, glossaryPath);
                glossaryManager.processGlossaryUpdate(glossaryPath, targetLanguage);
                logger.info("Successfully completed glossary update for language: {}", targetLanguage);
            } catch (IOException e) {
                logger.error("Error updating glossary for language {}: {}", targetLanguage, e.getMessage(), e);
            }
        }
    }
}