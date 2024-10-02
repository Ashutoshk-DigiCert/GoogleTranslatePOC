package com.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.translate.v3.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TranslateWithGlossary {

    public static void main(String[] args) throws Exception {
        // **Your Project and Resource Details**
        String projectId = "translate-project-a-512";
        String location = "us-central1";
        String glossaryId = "my-glossary_id";
        String bucketName = "glossaries11";
        String glossaryFileName = "glossaries1.csv";

        // **1. Create Glossary (if needed)**
        // Uncomment if you want to create the glossary programmatically
        createGlossary(projectId, location, glossaryId, bucketName, glossaryFileName);

        // **2. Translate Text using the Glossary**
        translateTextWithGlossary(projectId, location, glossaryId);

        deleteGlossary(projectId, location, glossaryId);
    }

    public static void getGlossaryDetails(String projectId, String location, String glossaryId) throws IOException {
        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            GlossaryName glossaryName = GlossaryName.of(projectId, location, glossaryId);
            Glossary glossary = client.getGlossary(glossaryName);

            System.out.println("Glossary name: " + glossary.getName());
            System.out.println("Entry count: " + glossary.getEntryCount());
            System.out.println("Input URI: " + glossary.getInputConfig().getGcsSource().getInputUri());

            // You might need to add permissions to access the GCS file directly
            // Alternatively, you can print out a few sample entries if available in the API response
        } catch (ApiException e) {
            System.err.println("Error getting glossary details: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void deleteGlossary(String projectId, String location, String glossaryId) throws IOException, InterruptedException {
        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            GlossaryName glossaryName = GlossaryName.of(projectId, location, glossaryId);
            DeleteGlossaryRequest request = DeleteGlossaryRequest.newBuilder()
                    .setName(glossaryName.toString())
                    .build();

            OperationFuture<DeleteGlossaryResponse, DeleteGlossaryMetadata> future = client.deleteGlossaryAsync(request);
            future.get(180, TimeUnit.SECONDS);
            System.out.println("Glossary deleted successfully.");
        } catch (ExecutionException | TimeoutException e) {
            System.err.println("Error deleting glossary: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Method to create the glossary
    public static void createGlossary(String projectId, String location, String glossaryId,
                                      String bucketName, String glossaryFileName)
            throws IOException, InterruptedException {

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(projectId, location);
            String glossaryName = LocationName.of(projectId, location).toString() + "/glossaries/" + glossaryId;
            String inputUri = "gs://" + bucketName + "/" + glossaryFileName;

            // Check if the glossary already exists
            try {
                GetGlossaryRequest getRequest = GetGlossaryRequest.newBuilder()
                        .setName(glossaryName)
                        .build();
                Glossary existingGlossary = client.getGlossary(getRequest);
                System.out.println("Glossary already exists: " + existingGlossary.getName());
                return; // Glossary exists, no need to create it
            } catch (ApiException e) {
                if (e.getStatusCode().getCode() != StatusCode.Code.NOT_FOUND) {
                    throw e; // Rethrow if the error is not NOT_FOUND
                }
            }

            GlossaryInputConfig inputConfig = GlossaryInputConfig.newBuilder()
                    .setGcsSource(GcsSource.newBuilder().setInputUri(inputUri).build())
                    .build();

            Glossary glossary = Glossary.newBuilder()
                    .setName(glossaryName)
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

            System.out.println("Waiting for operation to complete...");
            Glossary createdGlossary = future.get(180, TimeUnit.SECONDS);
            System.out.println("Glossary created: " + createdGlossary.getName());
        } catch (TimeoutException | ExecutionException | ApiException e) {
            System.err.println("Error creating glossary: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void translateTextWithGlossary(String projectId, String location, String glossaryId)
            throws IOException {

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(projectId, location);
            String glossaryPath = LocationName.of(projectId, location).toString() + "/glossaries/" + glossaryId;

            TranslateTextGlossaryConfig glossaryConfig = TranslateTextGlossaryConfig.newBuilder()
                    .setGlossary(glossaryPath)
                    .setIgnoreCase(false)  // Set to false for exact matching
                    .build();

            List<String> phrasesToTranslate = Arrays.asList("DigiCon","one-time procurement",
                    "PIN", "pin", "One-time passcode", "DigiCert One Document Signing one-time passcode", "DigiCert One Document Signing CSC APIs", "AATL", "DigiCert ONE", "DigiCert"

            );

            for (String phrase : phrasesToTranslate) {
                TranslateTextRequest request = TranslateTextRequest.newBuilder()
                        .setParent(parent.toString())
                        .addContents(phrase)
                        .setSourceLanguageCode("en")
                        .setTargetLanguageCode("hi")
                        .setMimeType("text/plain")
                        .setGlossaryConfig(glossaryConfig)
                        .build();

                TranslateTextResponse response = client.translateText(request);

                System.out.println("Original: " + phrase);
                System.out.println("Translated: " + response.getGlossaryTranslations(0).getTranslatedText());
                System.out.println();
            }
        } catch (ApiException e) {
            System.err.println("Error during translation: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

