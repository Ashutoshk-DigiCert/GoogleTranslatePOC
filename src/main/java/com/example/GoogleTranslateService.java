package com.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.translate.v3.*;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GoogleTranslateService {

    private static final String PROJECT_ID = "translate-project-a-512";
    private static final String LOCATION = "us-central1";
    private static final String BUCKET_NAME = "glossaries11";
    private final String glossaryFileName;

    public GoogleTranslateService(String targetLanguage) {
        this.glossaryFileName = generateGlossaryFileName(targetLanguage);
    }

    private String generateGlossaryFileName(String targetLanguage) {
        return String.format("glossaries_%s.csv", targetLanguage.toLowerCase());
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

    private enum EntryType {
        PROPERTY, COMMENT, EMPTY_LINE
    }

    private List<PropertyEntry> readPropertiesFile(String inputFile) throws IOException {
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
        }
        return entries;
    }

    public List<PropertyEntry> translateProperties(List<PropertyEntry> entries, String targetLanguage) throws IOException {
        String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (credentialsPath == null || credentialsPath.isEmpty()) {
            throw new IOException("Environment variable GOOGLE_APPLICATION_CREDENTIALS is not set");
        }

        GoogleCredentials credentials;
        try (FileInputStream credentialsStream = new FileInputStream(credentialsPath)) {
            credentials = GoogleCredentials.fromStream(credentialsStream);
        }

        List<PropertyEntry> translatedEntries = new ArrayList<>();

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(PROJECT_ID, LOCATION);
            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String glossaryName = LocationName.of(PROJECT_ID, LOCATION).toString() + "/glossaries/" + glossaryId;

            boolean glossaryExists = createGlossaryIfNotExists(client, parent, glossaryName, targetLanguage);

            for (PropertyEntry entry : entries) {
                if (entry.type == EntryType.COMMENT || entry.type == EntryType.EMPTY_LINE) {
                    translatedEntries.add(entry);
                } else {
                    try {
                        String fullValue = String.join("\n", entry.lines);
                        String translatedValue;
                        if (glossaryExists) {
                            translatedValue = translateValueWithGlossary(client, parent, fullValue, targetLanguage, glossaryName);
                        } else {
                            translatedValue = translateValueWithoutGlossary(client, parent, fullValue, targetLanguage);
                        }
                        String[] translatedLines = translatedValue.split("\n");
                        translatedEntries.add(new PropertyEntry(entry.key, Arrays.asList(translatedLines), EntryType.PROPERTY));
                    } catch (Exception e) {
                        System.err.println("Failed to translate property: " + entry.key + ". Error: " + e.getMessage());
                        translatedEntries.add(entry);
                    }
                }
            }
        } catch (ApiException e) {
            throw new IOException("Error creating TranslationServiceClient: " + e.getMessage(), e);
        }

        return translatedEntries;
    }

    private boolean createGlossaryIfNotExists(TranslationServiceClient client, LocationName parent, String glossaryName, String targetLanguage) {
        try {
            client.getGlossary(glossaryName);
            System.out.println("Glossary already exists: " + glossaryName);
            return true;
        } catch (ApiException e) {
            StatusCode.Code statusCode = e.getStatusCode().getCode();

            if (statusCode == StatusCode.Code.NOT_FOUND) {
                System.out.println("Glossary not found. Checking if target language exists in the glossary file...");
                if (isLanguageInGlossaryFile(targetLanguage)) {
                    System.out.println("Target language found in glossary file. Attempting to create glossary: " + glossaryName);
                    try {
                        createGlossary(client, parent, targetLanguage);
                        return true;
                    } catch (IOException createException) {
                        System.err.println("Failed to create glossary: " + createException.getMessage());
                        return false;
                    }
                } else {
                    System.out.println("Target language not found in glossary file. Skipping glossary creation.");
                    return false;
                }
            } else {
                System.err.println("Error checking glossary: " + e.getMessage());
                return false;
            }
        }
    }

    private boolean isLanguageInGlossaryFile(String targetLanguage) {
        try {
            Storage storage = StorageOptions.newBuilder().setProjectId(PROJECT_ID).build().getService();
            Blob blob = storage.get(BUCKET_NAME, this.glossaryFileName);

            if (blob == null) {
                System.err.println("Glossary file not found in Google Cloud Storage: " + this.glossaryFileName);
                return false;
            }

            String content = new String(blob.getContent(), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");

            if (lines.length > 0) {
                String[] headers = lines[0].split(",");
                for (String header : headers) {
                    if (header.trim().equalsIgnoreCase(targetLanguage)) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            System.err.println("Error reading glossary file from Google Cloud Storage: " + e.getMessage());
            return false;
        }
    }

    private void createGlossary(TranslationServiceClient client, LocationName parent, String targetLanguage) throws IOException {
        try {
            LocationName P = LocationName.of(PROJECT_ID, LOCATION);
            System.out.println("Parent location: " + P);

            String inputUri = "gs://" + BUCKET_NAME + "/" + this.glossaryFileName;
            System.out.println("Input URI: " + inputUri);

            if (!isValidLanguageCode(targetLanguage)) {
                throw new IllegalArgumentException("Invalid target language code: " + targetLanguage);
            }

            String glossaryId = "glossary-" + targetLanguage.toLowerCase();

            GlossaryInputConfig inputConfig = GlossaryInputConfig.newBuilder()
                    .setGcsSource(GcsSource.newBuilder().setInputUri(inputUri).build())
                    .build();

            Glossary glossary = Glossary.newBuilder()
                    .setName(LocationName.of(PROJECT_ID, LOCATION).toString() + "/glossaries/" + glossaryId)
                    .setLanguagePair(Glossary.LanguageCodePair.newBuilder()
                            .setSourceLanguageCode("en")
                            .setTargetLanguageCode(targetLanguage)
                            .build())
                    .setInputConfig(inputConfig)
                    .build();

            CreateGlossaryRequest request = CreateGlossaryRequest.newBuilder()
                    .setParent(parent.toString())
                    .setGlossary(glossary)
                    .build();

            System.out.println("Creating glossary with ID: " + glossaryId);
            System.out.println("Source language: en");
            System.out.println("Target language: " + targetLanguage);

            OperationFuture<Glossary, CreateGlossaryMetadata> future = client.createGlossaryAsync(request);
            Glossary createdGlossary = future.get(5, TimeUnit.MINUTES);
            System.out.println("Created glossary: " + createdGlossary.getName());
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            System.err.println("Error creating glossary: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getMessage());
            }
            e.printStackTrace();
            throw new IOException("Error creating glossary", e);
        } catch (ApiException e) {
            System.err.println("ApiException during glossary creation: " + e.getMessage());
            System.err.println("Error code: " + e.getStatusCode().getCode());
            System.err.println("Error description: " + e.getStatusCode().toString());
            e.printStackTrace();
            throw new IOException("Error creating glossary", e);
        }
    }

    private boolean isValidLanguageCode(String languageCode) {
        return languageCode != null && languageCode.matches("^[a-zA-Z]{2,3}(-[a-zA-Z]{2,3})?$");
    }
    private String translateValueWithGlossary(TranslationServiceClient client, LocationName parent, String value, String targetLanguage, String glossaryName) {
        int separatorIndex = value.indexOf('=');
        String key = value.substring(0, separatorIndex + 1);
        String contentToTranslate = value.substring(separatorIndex + 1);

        String cleanContent = contentToTranslate.replaceAll("\\s*\\\\\n\\s*", " ").trim();

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

        TranslateTextResponse response = client.translateText(request);
        String translatedText = response.getGlossaryTranslations(0).getTranslatedText().trim();

        return formatTranslatedText(key, contentToTranslate, translatedText);
    }

    private String translateValueWithoutGlossary(TranslationServiceClient client, LocationName parent, String value, String targetLanguage) {
        int separatorIndex = value.indexOf('=');
        String key = value.substring(0, separatorIndex + 1);
        String contentToTranslate = value.substring(separatorIndex + 1);

        String cleanContent = contentToTranslate.replaceAll("\\s*\\\\\n\\s*", " ").trim();

        TranslateTextRequest request = TranslateTextRequest.newBuilder()
                .setParent(parent.toString())
                .setMimeType("text/plain")
                .setSourceLanguageCode("en")
                .setTargetLanguageCode(targetLanguage)
                .addContents(cleanContent)
                .build();

        TranslateTextResponse response = client.translateText(request);
        String translatedText = response.getTranslations(0).getTranslatedText().trim();

        return formatTranslatedText(key, contentToTranslate, translatedText);
    }

    private String formatTranslatedText(String key, String originalContent, String translatedText) {
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

    private static void writePropertiesUtf8(List<PropertyEntry> entries, String filename) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename), StandardCharsets.UTF_8))) {
            for (PropertyEntry entry : entries) {
                for (String line : entry.lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    public void deleteGlossary(String targetLanguage) throws IOException {
        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String glossaryName = LocationName.of(PROJECT_ID, LOCATION).toString() + "/glossaries/" + glossaryId;

            System.out.println("Attempting to delete glossary: " + glossaryName);

            try {
                OperationFuture<DeleteGlossaryResponse, DeleteGlossaryMetadata> future = client.deleteGlossaryAsync(glossaryName);
                DeleteGlossaryResponse response = future.get(5, TimeUnit.MINUTES);
                System.out.println("Deleted glossary: " + response.getName());
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                if (e.getCause() instanceof ApiException) {
                    ApiException apiException = (ApiException) e.getCause();
                    if (apiException.getStatusCode().getCode() == StatusCode.Code.NOT_FOUND) {
                        System.out.println("Glossary not found. No deletion required.");
                    } else {
                        System.err.println("Error deleting glossary: " + apiException.getMessage());
                        throw new IOException("Error deleting glossary", apiException);
                    }
                } else {
                    System.err.println("Error deleting glossary: " + e.getMessage());
                    throw new IOException("Error deleting glossary", e);
                }
            }
        } catch (Exception e) {
            throw new IOException("Error creating TranslationServiceClient: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: java GoogleTranslateService <targetLanguage> [deleteGlossary]");
            System.exit(1);
        }

        String targetLanguage = args[0];
        boolean shouldDeleteGlossary = args.length == 2 && args[1].equalsIgnoreCase("deleteGlossary");

        String inputPropsFile = "src/main/resources/messages_en.properties";
        String outputPropsFile = "src/main/resources/messages_" + targetLanguage + ".properties";

        try {
            GoogleTranslateService translator = new GoogleTranslateService(targetLanguage);

            if (shouldDeleteGlossary) {
                translator.deleteGlossary(targetLanguage);
                System.out.println("Glossary deletion attempt completed.");
                return;
            }

            List<PropertyEntry> originalEntries = translator.readPropertiesFile(inputPropsFile);
            List<PropertyEntry> translatedEntries = translator.translateProperties(originalEntries, targetLanguage);

            Path outputPath = Paths.get(outputPropsFile);
            Files.createDirectories(outputPath.getParent());

            writePropertiesUtf8(translatedEntries, outputPath.toString());
            System.out.println("Translation complete. Output file: " + outputPropsFile);
        } catch (IOException e) {
            System.err.println("Error during translation process: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}