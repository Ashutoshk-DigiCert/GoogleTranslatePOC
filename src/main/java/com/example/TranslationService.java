package com.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.translate.v3.CreateGlossaryMetadata;
import com.google.cloud.translate.v3.CreateGlossaryRequest;
import com.google.cloud.translate.v3.GcsSource;
import com.google.cloud.translate.v3.Glossary;
import com.google.cloud.translate.v3.GlossaryInputConfig;
import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslateTextGlossaryConfig;
import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.TranslationServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TranslationService {
    private static final Logger logger = LoggerFactory.getLogger(TranslationService.class);
    private final ConfigManager configManager;

    public TranslationService(ConfigManager configManager) {
        this.configManager = configManager;
    }

public List<PropertyEntry> translateProperties(List<PropertyEntry> entries, String targetLanguage, String previousVersionFile) throws IOException {
    logger.info("Starting translation process for target language: {}", targetLanguage);
    String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
    if (credentialsPath == null || credentialsPath.isEmpty()) {
        logger.error("GOOGLE_APPLICATION_CREDENTIALS environment variable is not set");
        throw new IOException("Environment variable GOOGLE_APPLICATION_CREDENTIALS is not set");
    }

    List<PropertyEntry> translatedEntries = new ArrayList<>();
    Map<String, PropertyEntry> existingTranslationsMap = new HashMap<>();

    try (TranslationServiceClient client = TranslationServiceClient.create()) {
        LocationName parent = LocationName.of(configManager.getProjectId(), configManager.getLocation());
        String glossaryId = "glossary-" + targetLanguage.toLowerCase();
        String glossaryName = LocationName.of(configManager.getProjectId(), configManager.getLocation()).toString() + "/glossaries/" + glossaryId;

        boolean glossaryExists = createGlossaryIfNotExists(client, parent, glossaryName, targetLanguage);
        logger.info("Glossary status for {}: exists={}", targetLanguage, glossaryExists);

        List<PropertyEntry> modifiedEntries = entries;

        if (previousVersionFile != null) {
            String currentFile = configManager.getInputFilePath();
            modifiedEntries = getModifiedEntries(previousVersionFile, currentFile);
            logger.info("Processing {} modified/new entries for incremental translation", modifiedEntries.size());

            String existingTranslationsFile = configManager.getOutputFilePath(targetLanguage);
            try {
                List<PropertyEntry> existingTranslations = FileIO.readPropertiesFile(existingTranslationsFile);
                logger.info("Loaded {} existing translations from {}", existingTranslations.size(), existingTranslationsFile);

                for (PropertyEntry entry : existingTranslations) {
                    if (entry.type == PropertyEntry.EntryType.PROPERTY) {
                        existingTranslationsMap.put(entry.key, entry);
                    }
                }
            } catch (IOException e) {
                logger.warn("No existing translations found at: {}. Creating new file.", existingTranslationsFile);
            }
        }

        for (PropertyEntry entry : entries) {
            if (entry.type == PropertyEntry.EntryType.COMMENT || entry.type == PropertyEntry.EntryType.EMPTY_LINE) {
                translatedEntries.add(entry);
                continue;
            }

            try {
                boolean isModified = modifiedEntries.stream()
                    .anyMatch(e -> e.key.equals(entry.key));

                if (previousVersionFile != null) {
                    if (isModified || !existingTranslationsMap.containsKey(entry.key)) {
                        String fullValue = String.join("\n", entry.lines);
                        logger.info("Translating modified/new entry: {}", entry.key);
                        String translatedValue = glossaryExists ?
                            translateValueWithGlossary(client, parent, fullValue, targetLanguage, glossaryName) :
                            translateValueWithoutGlossary(client, parent, fullValue, targetLanguage);
                        translatedEntries.add(new PropertyEntry(entry.key, List.of(translatedValue.split("\n")), PropertyEntry.EntryType.PROPERTY));
                    } else {
                        translatedEntries.add(existingTranslationsMap.get(entry.key));
                    }
                } else {
                    String fullValue = String.join("\n", entry.lines);
                    logger.info("Translating entry: {}", entry.key);
                    String translatedValue = glossaryExists ?
                        translateValueWithGlossary(client, parent, fullValue, targetLanguage, glossaryName) :
                        translateValueWithoutGlossary(client, parent, fullValue, targetLanguage);
                    translatedEntries.add(new PropertyEntry(entry.key, List.of(translatedValue.split("\n")), PropertyEntry.EntryType.PROPERTY));
                }
            } catch (Exception e) {
                logger.error("Failed to translate property: {} for language {}. Error: {}", entry.key, targetLanguage, e.getMessage(), e);
                translatedEntries.add(entry);
            }
        }
    } catch (ApiException e) {
        logger.error("Error creating TranslationServiceClient: {}", e.getMessage(), e);
        throw new IOException("Error creating TranslationServiceClient: " + e.getMessage(), e);
    }

    return translatedEntries;
}

    private List<PropertyEntry> getModifiedEntries(String originalFile, String newFile) throws IOException {
        List<PropertyEntry> originalEntries = FileIO.readPropertiesFile(originalFile);
        List<PropertyEntry> newEntries = FileIO.readPropertiesFile(newFile);
        List<PropertyEntry> modifiedEntries = new ArrayList<>();

        logger.info("Comparing files for changes:");
        logger.info("Original file: {}", originalFile);
        logger.info("New file: {}", newFile);

        Map<String, PropertyEntry> originalMap = new HashMap<>();
        originalEntries.forEach(entry -> {
            if (entry.type == PropertyEntry.EntryType.PROPERTY) {
                originalMap.put(entry.key, entry);
            }
        });

        for (PropertyEntry newEntry : newEntries) {
            if (newEntry.type == PropertyEntry.EntryType.PROPERTY) {
                PropertyEntry originalEntry = originalMap.get(newEntry.key);
                if (originalEntry == null) {
                    logger.info("New entry detected - Key: {}, Value: {}", newEntry.key, String.join("", newEntry.lines));
                    modifiedEntries.add(newEntry);
                } else if (!originalEntry.lines.equals(newEntry.lines)) {
                    logger.info("Modified entry detected - Key: {}", newEntry.key);
                    logger.info("  Original value: {}", String.join("", originalEntry.lines));
                    logger.info("  New value: {}", String.join("", newEntry.lines));
                    modifiedEntries.add(newEntry);
                }
            }
        }

        logger.info("Total modified/new entries detected: {}", modifiedEntries.size());
        return modifiedEntries;
    }


    private boolean createGlossaryIfNotExists(TranslationServiceClient client, LocationName parent, String glossaryName, String targetLanguage) {
        logger.info("Checking if glossary exists: {}", glossaryName);
        try {
            client.getGlossary(glossaryName);
            logger.info("Glossary already exists: {}", glossaryName);
            return true;
        } catch (ApiException e) {
            if (e.getStatusCode().getCode() == StatusCode.Code.NOT_FOUND) {
                logger.info("Glossary not found. Attempting to create glossary: {}", glossaryName);
                try {
                    createGlossary(client, parent, targetLanguage);
                    return true;
                } catch (IOException createException) {
                    logger.error("Failed to create glossary: {}", createException.getMessage(), createException);
                    return false;
                }
            } else {
                logger.error("Error checking glossary: {}", e.getMessage(), e);
                return false;
            }
        }
    }

    private void createGlossary(TranslationServiceClient client, LocationName parent, String targetLanguage) throws IOException {
        logger.info("Starting glossary creation process for language: {}", targetLanguage);
        String inputUri = String.format("gs://%s/%s", configManager.getBucketName(), configManager.getGlossaryFileName(targetLanguage));
        logger.debug("Input URI for glossary: {}", inputUri);

        GcsSource gcsSource = GcsSource.newBuilder().setInputUri(inputUri).build();
        GlossaryInputConfig inputConfig = GlossaryInputConfig.newBuilder().setGcsSource(gcsSource).build();

        Glossary.LanguageCodePair languageCodePair = Glossary.LanguageCodePair.newBuilder()
                .setSourceLanguageCode("en")
                .setTargetLanguageCode(targetLanguage)
                .build();

        String glossaryId = "glossary-" + targetLanguage.toLowerCase();
        String fullGlossaryName = LocationName.of(configManager.getProjectId(), configManager.getLocation()).toString() + "/glossaries/" + glossaryId;

        Glossary glossary = Glossary.newBuilder()
                .setName(fullGlossaryName)
                .setLanguagePair(languageCodePair)
                .setInputConfig(inputConfig)
                .build();

        CreateGlossaryRequest request = CreateGlossaryRequest.newBuilder()
                .setParent(parent.toString())
                .setGlossary(glossary)
                .build();

        logger.info("Creating glossary with configuration - ID: {}, Source: en, Target: {}, URI: {}",
                glossaryId, targetLanguage, inputUri);

        OperationFuture<Glossary, CreateGlossaryMetadata> future = client.createGlossaryAsync(request);

        int timeoutMinutes = Integer.parseInt(configManager.getConfig().getProperty("glossary.creation.timeout.minutes", "5"));
        try {
            Glossary createdGlossary = future.get(timeoutMinutes, TimeUnit.MINUTES);
            logger.info("Successfully created glossary: {}", createdGlossary.getName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Glossary creation was interrupted", e);
            throw new IOException("Glossary creation was interrupted", e);
        } catch (ExecutionException e) {
            handleExecutionException(e);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.error("Glossary creation timed out after {} minutes", timeoutMinutes, e);
            throw new IOException("Glossary creation timed out after " + timeoutMinutes + " minutes", e);
        }
    }

    private void handleExecutionException(ExecutionException e) throws IOException {
        Throwable cause = e.getCause();
        if (cause instanceof ApiException apiException) {
            handleApiException(apiException);
        } else {
            logger.error("Error during glossary creation", e);
            throw new IOException("Error during glossary creation", e);
        }
    }

    private void handleApiException(ApiException e) throws IOException {
        StatusCode.Code code = e.getStatusCode().getCode();
        String message = String.format("API error during glossary creation (Code: %s): %s",
                code, e.getMessage());

        switch (code) {
            case ALREADY_EXISTS:
                logger.info("Glossary already exists. Proceeding with existing glossary.");
                break;
            case INVALID_ARGUMENT:
                logger.error("Invalid glossary configuration: {}", e.getMessage(), e);
                throw new IOException("Invalid glossary configuration: " + e.getMessage(), e);
            case PERMISSION_DENIED:
                logger.error("Permission denied. Please check credentials and project permissions.", e);
                throw new IOException("Permission denied. Please check your credentials and project permissions.", e);
            case NOT_FOUND:
                logger.error("Resource not found. Please check if the glossary file exists in the GCS bucket.", e);
                throw new IOException("Resource not found. Please check if the glossary file exists in the GCS bucket.", e);
            case RESOURCE_EXHAUSTED:
                logger.error("Resource quota exceeded. Please try again later.", e);
                throw new IOException("Resource quota exceeded. Please try again later.", e);
            default:
                logger.error(message, e);
                throw new IOException(message, e);
        }
    }

    private String translateValueWithGlossary(TranslationServiceClient client, LocationName parent, String value, String targetLanguage, String glossaryName) {
        logger.debug("Translating value with glossary - Target language: {}, Glossary: {}", targetLanguage, glossaryName);
        int separatorIndex = value.indexOf('=');
        String key = value.substring(0, separatorIndex + 1);
        String contentToTranslate = value.substring(separatorIndex + 1);

        String cleanContent = contentToTranslate.replaceAll("\\s*\\\\\n\\s*", " ").trim();
        logger.trace("Content to translate: {}", cleanContent);

        TranslateTextGlossaryConfig glossaryConfig = TranslateTextGlossaryConfig.newBuilder()
                .setGlossary(glossaryName)
                .build();

        TranslateTextRequest request = TranslateTextRequest.newBuilder()
                .setParent(parent.toString())
                .setMimeType("text/plain")
                .setSourceLanguageCode("en")
                .setTargetLanguageCode(targetLanguage)
                .addContents(cleanContent)
                .setGlossaryConfig(glossaryConfig)
                .build();

        try {
            TranslateTextResponse response = client.translateText(request);
            String translatedText = response.getGlossaryTranslations(0).getTranslatedText().trim();
            logger.debug("Successfully translated text with glossary for key: {}", key);
            return formatTranslatedText(key, contentToTranslate, translatedText);
        } catch (Exception e) {
            logger.error("Error translating text with glossary - Key: {}, Error: {}", key, e.getMessage(), e);
            throw e;
        }
    }

    private String translateValueWithoutGlossary(TranslationServiceClient client, LocationName parent, String value, String targetLanguage) {
        logger.debug("Translating value without glossary - Target language: {}", targetLanguage);
        int separatorIndex = value.indexOf('=');
        String key = value.substring(0, separatorIndex + 1);
        String contentToTranslate = value.substring(separatorIndex + 1);

        String cleanContent = contentToTranslate.replaceAll("\\s*\\\\\n\\s*", " ").trim();
        logger.trace("Content to translate: {}", cleanContent);

        TranslateTextRequest request = TranslateTextRequest.newBuilder()
                .setParent(parent.toString())
                .setMimeType("text/plain")
                .setSourceLanguageCode("en")
                .setTargetLanguageCode(targetLanguage)
                .addContents(cleanContent)
                .build();

        try {
            TranslateTextResponse response = client.translateText(request);
            String translatedText = response.getTranslations(0).getTranslatedText().trim();
            logger.debug("Successfully translated text without glossary for key: {}", key);
            return formatTranslatedText(key, contentToTranslate, translatedText);
        } catch (Exception e) {
            logger.error("Error translating text without glossary - Key: {}, Error: {}", key, e.getMessage(), e);
            throw e;
        }
    }

    private String formatTranslatedText(String key, String originalContent, String translatedText) {
        logger.trace("Formatting translated text - Key: {}", key);
        String[] originalLines = originalContent.split("\n");
        StringBuilder result = new StringBuilder(key);
        for (int i = 0; i < originalLines.length; i++) {
            String originalLine = originalLines[i].trim();
            if (i > 0) {
                result.append("\n");
                result.append(getLeadingWhitespace(originalLines[i]));
            }
            if (i == originalLines.length - 1 || !originalLine.endsWith("\\")) {
                result.append(translatedText);
                translatedText = "";
            } else {
                int splitIndex = Math.min(originalLine.length() - 1, translatedText.length());
                result.append(translatedText.substring(0, splitIndex).trim()).append(" \\");
                translatedText = translatedText.substring(splitIndex).trim();
            }
        }

        String finalResult = result.toString().trim();
        if (finalResult.endsWith("\\")) {
            finalResult = finalResult.substring(0, finalResult.length() - 1).trim();
        }

        return finalResult;
    }

    private String getLeadingWhitespace(String s) {
        StringBuilder whitespace = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c)) {
                whitespace.append(c);
            } else {
                break;
            }
        }
        return whitespace.toString();
    }

}