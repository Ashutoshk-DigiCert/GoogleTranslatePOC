package com.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
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
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GoogleTranslateService {
    private static final Logger logger = LoggerFactory.getLogger(GoogleTranslateService.class);
    private final Properties config;
    private final String glossaryFileName;

    public GoogleTranslateService(String targetLanguage) throws IOException {
        logger.info("Initializing GoogleTranslateService for target language: {}", targetLanguage);
        this.config = loadConfig();
        this.glossaryFileName = String.format(config.getProperty("glossary.file.format"), targetLanguage.toLowerCase());
        logger.debug("Glossary file name set to: {}", glossaryFileName);
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
            logger.error("No target languages provided");
            System.err.println("Usage: java GoogleTranslateService <targetLanguage1> <targetLanguage2> ...");
            System.exit(1);
        }

        List<String> targetLanguages = Arrays.asList(args);
        logger.info("Processing translations for languages: {}", targetLanguages);

        try {
            for (String targetLanguage : targetLanguages) {
                logger.info("Processing translation for language: {}", targetLanguage);
                GoogleTranslateService translator = new GoogleTranslateService(targetLanguage);
                Properties config = translator.config;

                String inputPropsFile = config.getProperty("file.input.path");
                String outputPropsFile = String.format(config.getProperty("file.output.path.format"), targetLanguage);

                logger.info("Reading source properties from: {}", inputPropsFile);
                List<PropertyEntry> originalEntries = translator.readPropertiesFile(inputPropsFile);

                logger.info("Starting translation process for {} entries", originalEntries.size());
                List<PropertyEntry> translatedEntries = translator.translateProperties(originalEntries, targetLanguage);

                Path outputPath = Paths.get(outputPropsFile);
                Files.createDirectories(outputPath.getParent());

                logger.info("Writing translated properties to: {}", outputPropsFile);
                writePropertiesUtf8(translatedEntries, outputPath.toString());
                logger.info("Successfully completed translation for language: {}", targetLanguage);
            }
            logger.info("Translation process completed successfully for all languages");
        } catch (IOException e) {
            logger.error("Fatal error during translation process: {}", e.getMessage(), e);
            System.err.println("Error during translation process: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
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
            logger.info("Successfully loaded configuration properties");
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

    public List<PropertyEntry> translateProperties(List<PropertyEntry> entries, String targetLanguage) throws IOException {
        logger.info("Starting translation process for target language: {}", targetLanguage);
        String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (credentialsPath == null || credentialsPath.isEmpty()) {
            logger.error("GOOGLE_APPLICATION_CREDENTIALS environment variable is not set");
            throw new IOException("Environment variable GOOGLE_APPLICATION_CREDENTIALS is not set");
        }

        GoogleCredentials credentials;
        try (FileInputStream credentialsStream = new FileInputStream(credentialsPath)) {
            credentials = GoogleCredentials.fromStream(credentialsStream);
            logger.debug("Successfully loaded Google credentials from: {}", credentialsPath);
        }

        List<PropertyEntry> translatedEntries = new ArrayList<>();

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(getProjectId(), getLocation());
            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String glossaryName = LocationName.of(getProjectId(), getLocation()).toString() + "/glossaries/" + glossaryId;

            boolean glossaryExists = createGlossaryIfNotExists(client, parent, glossaryName, targetLanguage);
            logger.info("Glossary status for {}: exists={}", targetLanguage, glossaryExists);

            int totalEntries = entries.size();
            int processedEntries = 0;

            for (PropertyEntry entry : entries) {
                processedEntries++;
                if (entry.type == EntryType.COMMENT || entry.type == EntryType.EMPTY_LINE) {
                    translatedEntries.add(entry);
                    continue;
                }

                try {
                    String fullValue = String.join("\n", entry.lines);
                    logger.debug("Translating entry {}/{}: key={}", processedEntries, totalEntries, entry.key);
                    String translatedValue;
                    if (glossaryExists) {
                        translatedValue = translateValueWithGlossary(client, parent, fullValue, targetLanguage, glossaryName);
                    } else {
                        translatedValue = translateValueWithoutGlossary(client, parent, fullValue, targetLanguage);
                    }
                    String[] translatedLines = translatedValue.split("\n");
                    translatedEntries.add(new PropertyEntry(entry.key, Arrays.asList(translatedLines), EntryType.PROPERTY));

                    if (processedEntries % 100 == 0) {
                        logger.info("Translation progress: {}/{} entries completed", processedEntries, totalEntries);
                    }
                } catch (Exception e) {
                    logger.error("Failed to translate property: {} for language {}. Error: {}", entry.key, targetLanguage, e.getMessage(), e);
                    translatedEntries.add(entry);
                }
            }

            logger.info("Translation completed for language {}. Successfully translated {}/{} entries",
                    targetLanguage, processedEntries, totalEntries);
        } catch (ApiException e) {
            logger.error("Error creating TranslationServiceClient: {}", e.getMessage(), e);
            throw new IOException("Error creating TranslationServiceClient: " + e.getMessage(), e);
        }

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