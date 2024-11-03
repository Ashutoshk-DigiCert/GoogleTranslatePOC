package com.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.translate.v3.CreateGlossaryMetadata;
import com.google.cloud.translate.v3.CreateGlossaryRequest;
import com.google.cloud.translate.v3.DeleteGlossaryMetadata;
import com.google.cloud.translate.v3.DeleteGlossaryResponse;
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
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

public class GoogleTranslateService {
    private static final Logger logger = LoggerFactory.getLogger(GoogleTranslateService.class);
    private final Properties config;
    private final String glossaryFileName;
    private static final String CACHE_DIR = "translation_cache";
    private Map<String, PropertyEntry> previousTranslations;

    public GoogleTranslateService(String targetLanguage) throws IOException {
        logger.info("Initializing GoogleTranslateService for target language: {}", targetLanguage);
        this.config = loadConfig();
        this.glossaryFileName = String.format(config.getProperty("glossary.file.format"), targetLanguage.toLowerCase());
        this.previousTranslations = loadPreviousTranslations(targetLanguage);
        logger.info("Glossary file name set to: {}", glossaryFileName);
    }

    private static void writePropertiesUtf8(List<PropertyEntry> entries, String filename) throws IOException {
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

    public static void main(String[] args) {
        logger.info("Starting Google Translate Service");
        if (args.length < 1) {
            logger.error("No arguments provided");
            logger.error("Usage: java GoogleTranslateService <targetLanguage1> <targetLanguage2> ... [deleteGlossary]");
            logger.error("   or: java GoogleTranslateService <targetLanguage> updateGlossary <glossaryPath>");
            System.exit(1);
        }

        try {
            if (args.length >= 3 && args[1].equals("updateGlossary")) {
                processMultipleGlossaryUpdates(args);
            } else {
                List<String> targetLanguages = new ArrayList<>(Arrays.asList(args));
                boolean shouldDeleteGlossary = targetLanguages.remove("deleteGlossary");

                if (shouldDeleteGlossary) {
                    for (String targetLanguage : targetLanguages) {
                        try {
                            GoogleTranslateService translator = new GoogleTranslateService(targetLanguage);
                            translator.deleteGlossary(targetLanguage);
                            logger.info("Glossary deletion attempt completed for language: {}", targetLanguage);
                        } catch (IOException e) {
                            logger.error("Error deleting glossary for language {}: {}", targetLanguage, e.getMessage(), e);
                        }
                    }
                } else {
                    for (String targetLanguage : targetLanguages) {
                        try {
                            logger.info("Processing translation for language: {}", targetLanguage);
                            GoogleTranslateService translator = new GoogleTranslateService(targetLanguage);
                            Properties config = translator.config;

                            String inputPropsFile = config.getProperty("file.input.path");
                            String outputPropsFile = String.format(config.getProperty("file.output.path.format"), targetLanguage);

                            logger.info("Reading source properties from: {}", inputPropsFile);
                            List<PropertyEntry> originalEntries = translator.readPropertiesFile(inputPropsFile);

                            if (originalEntries.isEmpty()) {
                                logger.warn("No entries found in the source properties file: {}. Skipping translation for language: {}", inputPropsFile, targetLanguage);
                                continue;
                            }

                            logger.info("Starting translation process for {} entries", originalEntries.size());
                            List<PropertyEntry> translatedEntries = translator.translateProperties(originalEntries, targetLanguage);

                            Path outputPath = Paths.get(outputPropsFile);
                            Files.createDirectories(outputPath.getParent());

                            logger.info("Writing translated properties to: {}", outputPropsFile);
                            writePropertiesUtf8(translatedEntries, outputPath.toString());
                            logger.info("Successfully completed translation for language: {}", targetLanguage);
                        } catch (IOException e) {
                            logger.error("Fatal error during translation process for language {}: {}", targetLanguage, e.getMessage(), e);
                            logger.error("Error during translation process:{} ", e.getMessage());
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Fatal error during execution: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void processMultipleGlossaryUpdates(String[] args) {
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
                GoogleTranslateService translator = new GoogleTranslateService(targetLanguage);
                translator.processGlossaryUpdate(glossaryPath, targetLanguage);
                logger.info("Successfully completed glossary update for language: {}", targetLanguage);
            } catch (IOException e) {
                logger.error("Error updating glossary for language {}: {}", targetLanguage, e.getMessage(), e);
            }
        }
    }

    private Properties loadConfig() throws IOException {
        logger.debug("Loading configuration from application.properties");
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                logger.error("Failed to find application.properties file");
                throw new IOException("Unable to find application.properties");
            }
            properties.load(input);
            logger.debug("Successfully loaded configuration properties");
            return properties;
        } catch (IOException e) {
            logger.error("Error loading configuration: {}", e.getMessage(), e);
            throw e;
        }
    }

    private String getProjectId() {
        return config.getProperty("google.project.id");
    }

    private String getLocation() {
        return config.getProperty("google.location");
    }

    private String getBucketName() {
        return config.getProperty("google.bucket.name");
    }

    private List<PropertyEntry> readPropertiesFile(String inputFile) throws IOException {
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
            EntryType currentType = null;

            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("#")) {
                    if (currentType != null) {
                        entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                        currentLines.clear();
                    }
                    currentKey = line;
                    currentLines.add(line);
                    currentType = EntryType.COMMENT;
                } else if (line.trim().isEmpty()) {
                    if (currentType != null) {
                        entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                        currentLines.clear();
                    }
                    currentKey = "";
                    currentLines.add(line);
                    currentType = EntryType.EMPTY_LINE;
                } else {
                    int separatorIndex = line.indexOf('=');
                    if (separatorIndex > 0 && (currentType != EntryType.PROPERTY || !currentLines.get(currentLines.size() - 1).trim().endsWith("\\"))) {
                        if (currentType != null) {
                            entries.add(new PropertyEntry(currentKey, new ArrayList<>(currentLines), currentType));
                            currentLines.clear();
                        }
                        currentKey = line.substring(0, separatorIndex).trim();
                        currentLines.add(line);
                        currentType = EntryType.PROPERTY;
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

    private Map<String, PropertyEntry> loadPreviousTranslations(String targetLanguage) throws IOException {
        String cacheFile = String.format("%s/cache_%s.properties", CACHE_DIR, targetLanguage);
        Map<String, PropertyEntry> cache = new HashMap<>();
        Path cachePath = Paths.get(cacheFile);

        if (Files.exists(cachePath)) {
            List<PropertyEntry> entries = readPropertiesFile(cacheFile);
            for (PropertyEntry entry : entries) {
                if (entry.type == EntryType.PROPERTY) {
                    cache.put(entry.key, entry);
                }
            }
        }

        return cache;
    }

    private void savePreviousTranslations(List<PropertyEntry> entries, String targetLanguage) throws IOException {
        String cacheFile = String.format("%s/cache_%s.properties", CACHE_DIR, targetLanguage);
        Path cachePath = Paths.get(cacheFile);
        Files.createDirectories(cachePath.getParent());
        writePropertiesUtf8(entries, cacheFile);
    }



    public List<PropertyEntry> translateProperties(List<PropertyEntry> entries, String targetLanguage) throws IOException {
        logger.info("Starting optimized translation process for target language: {}", targetLanguage);

        // Create a map of property keys to their original entries for easier lookup
        Map<String, PropertyEntry> currentEntriesMap = new HashMap<>();
        for (PropertyEntry entry : entries) {
            if (entry.type == EntryType.PROPERTY) {
                currentEntriesMap.put(entry.key, entry);
            }
        }

        // Create a list of keys for diff comparison
        List<String> currentKeys = new ArrayList<>(currentEntriesMap.keySet());
        List<String> previousKeys = new ArrayList<>(previousTranslations.keySet());

        // Calculate diff between current and previous versions
        Patch<String> patch = DiffUtils.diff(previousKeys, currentKeys);
        List<PropertyEntry> translatedEntries = new ArrayList<>();

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(getProjectId(), getLocation());
            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String glossaryName = parent.toString() + "/glossaries/" + glossaryId;
            boolean glossaryExists = createGlossaryIfNotExists(client, parent, glossaryName, targetLanguage);

            // Set to keep track of modified keys
            Set<String> modifiedKeys = new HashSet<>();

            // Identify modified or new keys from the patch
            for (AbstractDelta<String> delta : patch.getDeltas()) {
                for (String key : delta.getTarget().getLines()) {
                    modifiedKeys.add(key);
                }
            }

            // Process each entry
            for (PropertyEntry entry : entries) {
                if (entry.type != EntryType.PROPERTY) {
                    // Keep non-property entries unchanged
                    translatedEntries.add(entry);
                    continue;
                }

                PropertyEntry translatedEntry;
                if (modifiedKeys.contains(entry.key)) {
                    // Only translate if the key is new or modified
                    logger.info("Translating modified/new entry: {}", entry.key);
                    try {
                        String translatedValue;
                        if (glossaryExists) {
                            translatedValue = translateValueWithGlossary(client, parent, String.join("\n", entry.lines), targetLanguage, glossaryName);
                        } else {
                            translatedValue = translateValueWithoutGlossary(client, parent, String.join("\n", entry.lines), targetLanguage);
                        }
                        String[] translatedLines = translatedValue.split("\n");
                        translatedEntry = new PropertyEntry(entry.key, Arrays.asList(translatedLines), EntryType.PROPERTY);
                    } catch (Exception e) {
                        logger.error("Failed to translate modified entry: {}. Using original entry.", entry.key, e);
                        translatedEntry = entry;
                    }
                } else {
                    // Use cached translation for unchanged entries
                    PropertyEntry cachedEntry = previousTranslations.get(entry.key);
                    if (cachedEntry != null) {
                        logger.debug("Using cached translation for: {}", entry.key);
                        translatedEntry = cachedEntry;
                    } else {
                        // Fallback to translation if somehow not in cache
                        logger.warn("Cache miss for unchanged entry: {}. Performing translation.", entry.key);
                        try {
                            String translatedValue;
                            if (glossaryExists) {
                                translatedValue = translateValueWithGlossary(client, parent, String.join("\n", entry.lines), targetLanguage, glossaryName);
                            } else {
                                translatedValue = translateValueWithoutGlossary(client, parent, String.join("\n", entry.lines), targetLanguage);
                            }
                            String[] translatedLines = translatedValue.split("\n");
                            translatedEntry = new PropertyEntry(entry.key, Arrays.asList(translatedLines), EntryType.PROPERTY);
                        } catch (Exception e) {
                            logger.error("Failed to translate cache miss entry: {}. Using original entry.", entry.key, e);
                            translatedEntry = entry;
                        }
                    }
                }
                translatedEntries.add(translatedEntry);
            }
        }

        // Update cache with new translations
        savePreviousTranslations(translatedEntries, targetLanguage);

        return translatedEntries;
    }


    private boolean createGlossaryIfNotExists(TranslationServiceClient client, LocationName parent, String glossaryName, String targetLanguage) {
        logger.info("Checking if glossary exists: {}", glossaryName);
        try {
            client.getGlossary(glossaryName);
            logger.info("Glossary already exists: {}", glossaryName);
            return true;
        } catch (ApiException e) {
            StatusCode.Code statusCode = e.getStatusCode().getCode();

            if (statusCode == StatusCode.Code.NOT_FOUND) {
                logger.info("Glossary not found. Checking if target language exists in the glossary file...");
                if (isLanguageInGlossaryFile(targetLanguage)) {
                    logger.info("Target language found in glossary file. Attempting to create glossary: {}", glossaryName);
                    try {
                        createGlossary(client, parent, targetLanguage);
                        return true;
                    } catch (IOException createException) {
                        logger.error("Failed to create glossary: {}", createException.getMessage(), createException);
                        return false;
                    }
                } else {
                    logger.warn("Target language not found in glossary file. Skipping glossary creation.");
                    return false;
                }
            } else {
                logger.error("Error checking glossary: {}", e.getMessage(), e);
                return false;
            }
        }
    }

    private boolean isLanguageInGlossaryFile(String targetLanguage) {
        logger.debug("Checking if language {} exists in glossary file: {}", targetLanguage, glossaryFileName);
        try {
            Storage storage = StorageOptions.newBuilder().setProjectId(getProjectId()).build().getService();
            Blob blob = storage.get(getBucketName(), this.glossaryFileName);

            if (blob == null) {
                logger.error("Glossary file not found in Google Cloud Storage: {}", this.glossaryFileName);
                return false;
            }

            String content = new String(blob.getContent(), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");

            if (lines.length > 0) {
                String[] headers = lines[0].split(",");
                for (String header : headers) {
                    if (header.trim().equalsIgnoreCase(targetLanguage)) {
                        logger.info("Found target language {} in glossary file", targetLanguage);
                        return true;
                    }
                }
            }

            logger.warn("Target language {} not found in glossary file", targetLanguage);
            return false;
        } catch (Exception e) {
            logger.error("Error reading glossary file from Google Cloud Storage: {}", e.getMessage(), e);
            return false;
        }
    }

    private void createGlossary(TranslationServiceClient client, LocationName parent, String targetLanguage) throws IOException {
        logger.info("Starting glossary creation process for language: {}", targetLanguage);
        try {
            String inputUri = String.format("gs://%s/%s", getBucketName(), this.glossaryFileName);
            logger.debug("Input URI for glossary: {}", inputUri);

            if (!isValidLanguageCode(targetLanguage)) {
                logger.error("Invalid target language code: {}", targetLanguage);
                throw new IllegalArgumentException("Invalid target language code: " + targetLanguage);
            }

            String glossaryId = String.format(config.getProperty("glossary.name.format"), targetLanguage.toLowerCase());
            String fullGlossaryName = LocationName.of(getProjectId(), getLocation()).toString() + "/glossaries/" + glossaryId;

            GcsSource gcsSource = GcsSource.newBuilder()
                    .setInputUri(inputUri)
                    .build();

            GlossaryInputConfig inputConfig = GlossaryInputConfig.newBuilder()
                    .setGcsSource(gcsSource)
                    .build();

            Glossary.LanguageCodePair languageCodePair = Glossary.LanguageCodePair.newBuilder()
                    .setSourceLanguageCode("en")
                    .setTargetLanguageCode(targetLanguage)
                    .build();

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

            int timeoutMinutes = Integer.parseInt(config.getProperty("glossary.creation.timeout.minutes", "5"));
            try {
                Glossary createdGlossary = future.get(timeoutMinutes, TimeUnit.MINUTES);
                logger.info("Successfully created glossary: {}", createdGlossary.getName());

                verifyGlossaryCreation(client, createdGlossary.getName());

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

        } catch (ApiException e) {
            handleApiException(e);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid configuration: {}", e.getMessage(), e);
            throw new IOException("Invalid configuration: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error during glossary creation: {}", e.getMessage(), e);
            throw new IOException("Unexpected error during glossary creation: " + e.getMessage(), e);
        }
    }

    private void handleExecutionException(ExecutionException e) throws IOException {
        Throwable cause = e.getCause();
        if (cause instanceof ApiException) {
            ApiException apiException = (ApiException) cause;
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

    private void verifyGlossaryCreation(TranslationServiceClient client, String glossaryName) throws IOException {
        logger.info("Verifying glossary creation: {}", glossaryName);
        try {
            Glossary glossary = client.getGlossary(glossaryName);
            if (glossary == null) {
                logger.error("Glossary was created but cannot be retrieved: {}", glossaryName);
                throw new IOException("Glossary was created but cannot be retrieved");
            }

            logger.info("Glossary verification successful - Name: {}, Entry count: {}, Submit time: {}",
                    glossary.getName(), glossary.getEntryCount(), glossary.getSubmitTime());
        } catch (ApiException e) {
            logger.error("Failed to verify glossary creation: {}", e.getMessage(), e);
            throw new IOException("Failed to verify glossary creation: " + e.getMessage(), e);
        }
    }

    private boolean isValidLanguageCode(String languageCode) {
        logger.debug("Validating language code: {}", languageCode);
        if (languageCode == null || languageCode.isEmpty()) {
            logger.warn("Invalid language code: null or empty");
            return false;
        }

        String languagePattern = "^[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?$";
        boolean isValid = languageCode.matches(languagePattern);
        logger.debug("Language code {} validation result: {}", languageCode, isValid);
        return isValid;
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

    public void deleteGlossary(String targetLanguage) throws IOException {
        logger.info("Attempting to delete glossary for language: {}", targetLanguage);
        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String glossaryName = LocationName.of(getProjectId(), getLocation()).toString() + "/glossaries/" + glossaryId;

            try {
                OperationFuture<DeleteGlossaryResponse, DeleteGlossaryMetadata> future = client.deleteGlossaryAsync(glossaryName);
                DeleteGlossaryResponse response = future.get(5, TimeUnit.MINUTES);
                logger.info("Successfully deleted glossary: {}", response.getName());
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                if (e.getCause() instanceof ApiException) {
                    ApiException apiException = (ApiException) e.getCause();
                    if (apiException.getStatusCode().getCode() == StatusCode.Code.NOT_FOUND) {
                        logger.info("Glossary not found. No deletion required: {}", glossaryName);
                    } else {
                        logger.error("Error deleting glossary: {}", apiException.getMessage(), apiException);
                        throw new IOException("Error deleting glossary", apiException);
                    }
                } else {
                    logger.error("Error deleting glossary: {}", e.getMessage(), e);
                    throw new IOException("Error deleting glossary", e);
                }
            }
        } catch (Exception e) {
            logger.error("Error creating TranslationServiceClient: {}", e.getMessage(), e);
            throw new IOException("Error creating TranslationServiceClient: " + e.getMessage(), e);
        }
    }

    private void uploadGlossaryToCloudStorage(String filePath, String targetLanguage) throws IOException {
        logger.info("Uploading glossary file to Cloud Storage for language: {}", targetLanguage);

        try {
            Storage storage = StorageOptions.newBuilder()
                    .setProjectId(getProjectId())
                    .build()
                    .getService();

            String glossaryFileName = String.format(config.getProperty("glossary.file.format"), targetLanguage.toLowerCase());
            BlobId blobId = BlobId.of(getBucketName(), glossaryFileName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();

            Path path = Paths.get(filePath);
            byte[] content = Files.readAllBytes(path);

            Blob blob = storage.create(blobInfo, content);

            if (blob == null || !blob.exists()) {
                throw new IOException("Failed to verify uploaded file in Cloud Storage");
            }

            logger.info("Successfully uploaded glossary file {} to bucket {}", glossaryFileName, getBucketName());

        } catch (StorageException e) {
            logger.error("Error uploading glossary file to Cloud Storage: {}", e.getMessage(), e);
            throw new IOException("Failed to upload glossary file to Cloud Storage: " + e.getMessage(), e);
        }
    }

    public void processGlossaryUpdate(String glossaryFilePath, String targetLanguage) throws IOException {
        logger.info("Processing glossary update for language: {}", targetLanguage);

        try {
            Path glossaryPath = Paths.get(glossaryFilePath);
            if (!Files.exists(glossaryPath)) {
                logger.warn("Glossary file not found at: {}. Skipping update for language: {}", glossaryFilePath, targetLanguage);
                return;
            }

            logger.info("Step 1: Uploading glossary file: {}", glossaryFilePath);
            uploadGlossaryToCloudStorage(glossaryFilePath, targetLanguage);

            logger.info("Step 2: Deleting existing glossary for language: {}", targetLanguage);
            try {
                deleteGlossary(targetLanguage);
            } catch (IOException e) {
                if (e.getMessage().contains("NOT_FOUND")) {
                    logger.info("No existing glossary found to delete. Proceeding with creation.");
                } else {
                    throw e;
                }
            }

            logger.info("Step 3: Creating new glossary for language: {}", targetLanguage);
            try (TranslationServiceClient client = TranslationServiceClient.create()) {
                LocationName parent = LocationName.of(getProjectId(), getLocation());
                createGlossary(client, parent, targetLanguage);
            }

            logger.info("Successfully processed glossary update for language: {}", targetLanguage);
        } catch (Exception e) {
            logger.error("Error processing glossary update: {}", e.getMessage(), e);
            throw new IOException("Failed to process glossary update: " + e.getMessage(), e);
        }
    }

    private enum EntryType {
        PROPERTY, COMMENT, EMPTY_LINE
    }

    private static class PropertyEntry {
        String key;
        List<String> lines;
        EntryType type;

        PropertyEntry(String key, List<String> lines, EntryType type) {
            this.key = key;
            this.lines = lines;
            this.type = type;
        }
    }
}