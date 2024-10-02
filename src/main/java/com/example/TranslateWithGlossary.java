package com.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.translate.v3.*;

import java.io.IOException;
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
    }

    // Method to create the glossary
    public static void createGlossary(String projectId, String location, String glossaryId,
                                     String bucketName, String glossaryFileName)
            throws IOException, InterruptedException {

        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            LocationName parent = LocationName.of(projectId, location);
            String glossaryName = LocationName.of(projectId, location).toString() + "/glossaries/" + glossaryId;
            String inputUri = "gs://" + bucketName + "/" + glossaryFileName;

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
            String glossary = LocationName.of(projectId, location).toString() + "/glossaries/" + glossaryId;

            TranslateTextGlossaryConfig glossaryConfig = TranslateTextGlossaryConfig.newBuilder()
                    .setGlossary(glossary)
                    .build();

            TranslateTextRequest request = TranslateTextRequest.newBuilder()
                    .setParent(parent.toString())
                    .addAllContents(java.util.Arrays.asList("pin", "apple", "milk")) // Change here
                    .setSourceLanguageCode("en")
                    .setTargetLanguageCode("hi")
                    .setMimeType("text/plain")
                    .setGlossaryConfig(glossaryConfig)
                    .build();

            TranslateTextResponse response = client.translateText(request);
            for (Translation translation : response.getGlossaryTranslationsList()) {
                System.out.println("Translated text: " + translation.getTranslatedText());
            }

        } catch (ApiException e) {
            System.err.println("Error during translation: " + e.getMessage());
            e.printStackTrace();
        }
    }
}