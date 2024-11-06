package com.example;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.translate.v3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GlossaryManager {
    private static final Logger logger = LoggerFactory.getLogger(GlossaryManager.class);
    private final ConfigManager configManager;

    public GlossaryManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void deleteGlossary(String targetLanguage) throws IOException {
        logger.info("Attempting to delete glossary for language: {}", targetLanguage);
        try (TranslationServiceClient client = TranslationServiceClient.create()) {
            String glossaryId = "glossary-" + targetLanguage.toLowerCase();
            String glossaryName = LocationName.of(configManager.getProjectId(), configManager.getLocation()).toString() + "/glossaries/" + glossaryId;

            try {
                OperationFuture<DeleteGlossaryResponse, DeleteGlossaryMetadata> future = client.deleteGlossaryAsync(glossaryName);
                DeleteGlossaryResponse response = future.get(5, TimeUnit.MINUTES);
                logger.info("Successfully deleted glossary: {}", response.getName());
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                if (e.getCause() instanceof ApiException apiException) {
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

    public void uploadGlossaryToCloudStorage(String filePath, String targetLanguage) throws IOException {
        logger.info("Uploading glossary file to Cloud Storage for language: {}", targetLanguage);

        try {
            Storage storage = StorageOptions.newBuilder()
                    .setProjectId(configManager.getProjectId())
                    .build()
                    .getService();

            String glossaryFileName = String.format(configManager.getConfig().getProperty("glossary.file.format"), targetLanguage.toLowerCase());
            BlobId blobId = BlobId.of(configManager.getBucketName(), glossaryFileName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();

            Path path = Paths.get(filePath);
            byte[] content = Files.readAllBytes(path);

            Blob blob = storage.create(blobInfo, content);

            if (blob == null || !blob.exists()) {
                throw new IOException("Failed to verify uploaded file in Cloud Storage");
            }

            logger.info("Successfully uploaded glossary file {} to bucket {}", glossaryFileName, configManager.getBucketName());

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
                LocationName parent = LocationName.of(configManager.getProjectId(), configManager.getLocation());
                createGlossary(client, parent, targetLanguage);
            }

            logger.info("Successfully processed glossary update for language: {}", targetLanguage);
        } catch (Exception e) {
            logger.error("Error processing glossary update: {}", e.getMessage(), e);
            throw new IOException("Failed to process glossary update: " + e.getMessage(), e);
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
}