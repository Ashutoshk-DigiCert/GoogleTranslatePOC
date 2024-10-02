package com.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.StatusCode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.translate.v3.*;
import com.google.api.gax.rpc.ApiException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GoogleTranslateService {

    private static final String PROJECT_ID = "translate-project-a-512";
    private static final String LOCATION = "us-central1";
    private static final String GLOSSARY_ID = "my-glossary_id";
    private static final String BUCKET_NAME = "glossaries11";
    private static final String GLOSSARY_FILE_NAME = "glossaries1.csv";

    private static class PropertyEntry {
        String key;
        List<String> lines;
        boolean isComment;
        boolean isEmptyLine;
        int trailingEmptyLines;

        PropertyEntry(String key, boolean isComment, boolean isEmptyLine) {
            this.key = key;
            this.lines = new ArrayList<>();
            this.isComment = isComment;
            this.isEmptyLine = isEmptyLine;
            this.trailingEmptyLines = 0;
        }
    }

    private List<PropertyEntry> readPropertiesFile(String inputFile) throws IOException {
        List<PropertyEntry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8))) {
            String line;
            PropertyEntry currentEntry = null;
            int emptyLineCount = 0;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    emptyLineCount++;
                } else {
                    if (currentEntry != null) {
                        currentEntry.trailingEmptyLines = emptyLineCount;
                    }
                    emptyLineCount = 0;

                    if (line.trim().startsWith("#")) {
                        entries.add(new PropertyEntry(line, true, false));
                    } else {
                        int separatorIndex = line.indexOf('=');
                        if (separatorIndex > 0 && (currentEntry == null || !currentEntry.lines.get(currentEntry.lines.size() - 1).trim().endsWith("\\"))) {
                            if (currentEntry != null) {
                                entries.add(currentEntry);
                            }
                            String key = line.substring(0, separatorIndex).trim();
                            currentEntry = new PropertyEntry(key, false, false);
                            currentEntry.lines.add(line);
                        } else if (currentEntry != null) {
                            currentEntry.lines.add(line);
                        } else {
                            currentEntry = new PropertyEntry(line, false, false);
                            currentEntry.lines.add(line);
                        }
                    }
                }
            }
            if (currentEntry != null) {
                currentEntry.trailingEmptyLines = emptyLineCount;
                entries.add(currentEntry);
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
            String glossaryName = LocationName.of(PROJECT_ID, LOCATION).toString() + "/glossaries/" + GLOSSARY_ID;

            createGlossaryIfNotExists(client, parent, glossaryName);

            for (PropertyEntry entry : entries) {
                if (entry.isComment || entry.isEmptyLine) {
                    translatedEntries.add(entry);
                } else {
                    try {
                        PropertyEntry translatedEntry = new PropertyEntry(entry.key, false, false);
                        String fullValue = String.join("\n", entry.lines);
                        String translatedValue = translateValueWithGlossary(client, parent, fullValue, targetLanguage, glossaryName);
                        String[] translatedLines = translatedValue.split("\n");
                        translatedEntry.lines.addAll(List.of(translatedLines));
                        translatedEntry.trailingEmptyLines = entry.trailingEmptyLines;
                        translatedEntries.add(translatedEntry);
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

    private void createGlossaryIfNotExists(TranslationServiceClient client, LocationName parent, String glossaryName) throws IOException {
        try {
            // Attempt to get the glossary
            client.getGlossary(glossaryName);
            System.out.println("Glossary already exists: " + glossaryName);
        } catch (ApiException e) {
            // Get the StatusCode.Code and compare it using ordinal or integer comparison
            StatusCode.Code statusCode = e.getStatusCode().getCode();

            if (statusCode == StatusCode.Code.NOT_FOUND) {
                // Glossary not found, so attempt to create it
                System.out.println("Glossary not found. Attempting to create glossary: " + glossaryName);
                createGlossary(client, parent);
            } else {
                // Handle other exceptions (e.g., permission issues)
                throw new IOException("Error checking glossary: " + e.getMessage(), e);
            }
        }
    }



    private void createGlossary(TranslationServiceClient client, LocationName parent) throws IOException {
        String inputUri = "gs://" + BUCKET_NAME + "/" + GLOSSARY_FILE_NAME;

        GlossaryInputConfig inputConfig = GlossaryInputConfig.newBuilder()
                .setGcsSource(GcsSource.newBuilder().setInputUri(inputUri).build())
                .build();

        Glossary glossary = Glossary.newBuilder()
                .setName(LocationName.of(PROJECT_ID, LOCATION).toString() + "/glossaries/" + GLOSSARY_ID)
                .setLanguagePair(Glossary.LanguageCodePair.newBuilder()
                        .setSourceLanguageCode("en")
                        .setTargetLanguageCode("hi")
                        .build())
                .setInputConfig(inputConfig)
                .build();

        CreateGlossaryRequest request = CreateGlossaryRequest.newBuilder()
                .setParent(parent.toString())
                .setGlossary(glossary)
                .build();

        OperationFuture<Glossary, CreateGlossaryMetadata> future = client.createGlossaryAsync(request);

        try {
            Glossary createdGlossary = future.get(5, TimeUnit.MINUTES);
            System.out.println("Created glossary: " + createdGlossary.getName());
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("Error creating glossary: " + e.getMessage(), e);
        }
    }

    private String translateValueWithGlossary(TranslationServiceClient client, LocationName parent, String value, String targetLanguage, String glossaryName) {
        int separatorIndex = value.indexOf('=');
        String key = value.substring(0, separatorIndex + 1);
        String contentToTranslate = value.substring(separatorIndex + 1);

        // Remove line breaks and backslashes for translation
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

        // Reinsert line breaks and backslashes
        String[] originalLines = contentToTranslate.split("\n");
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
                if (entry.isComment) {
                    writer.write(entry.key);
                    writer.newLine();
                } else {
                    for (String line : entry.lines) {
                        writer.write(line);
                        writer.newLine();
                    }
                }

                // Add trailing empty lines
                for (int j = 0; j < entry.trailingEmptyLines; j++) {
                    writer.newLine();
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java GoogleTranslateService <targetLanguage>");
            System.exit(1);
        }

        String targetLanguage = args[0];
        String inputPropsFile = "src/main/resources/application.properties";
        String outputPropsFile = "src/main/resources/application_" + targetLanguage + ".properties";

        try {
            GoogleTranslateService translator = new GoogleTranslateService();
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