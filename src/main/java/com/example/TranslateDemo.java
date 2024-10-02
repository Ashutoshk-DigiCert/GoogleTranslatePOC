package com.example; // Replace with your package name

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.translate.v3.*;
import com.google.api.gax.core.FixedCredentialsProvider;

import java.io.FileInputStream;
import java.io.IOException;

public class TranslateDemo {
    public static void main(String[] args) throws Exception {
        String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        System.out.println(credentialsPath);
        if (credentialsPath == null || credentialsPath.isEmpty()) {
            throw new IOException("GOOGLE_APPLICATION_CREDENTIALS environment variable not set.");
        }

        try (FileInputStream credentialsStream = new FileInputStream(credentialsPath)) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream);

            TranslationServiceSettings settings = TranslationServiceSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();

            try (TranslationServiceClient client = TranslationServiceClient.create(settings)) {
                LocationName parent = LocationName.of("translate-project-a-512", "us-central1");
                String text = "pin";

                TranslateTextRequest request =
                        TranslateTextRequest.newBuilder()
                                .setParent(parent.toString())
                                .setMimeType("text/plain")
                                .setSourceLanguageCode("en-US")
                                .setTargetLanguageCode("hi")
                                .addContents(text)
                                .build();


                TranslateTextResponse response = client.translateText(request);
                System.out.println(response.getTranslationsList().get(0).getTranslatedText());
            } catch (Exception e) { // More specific catch block
                System.err.println("Translation API call failed: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (IOException e) {  // Catch credential loading errors separately
            System.err.println("Error loading credentials: " + e.getMessage());
            e.printStackTrace();
        }
    }
}