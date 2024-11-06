package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private final Properties config;

    public ConfigManager() throws IOException {
        this.config = loadConfig();
    }

    public Properties getConfig() {
        return config;
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

    public String getProjectId() {
        return config.getProperty("google.project.id");
    }

    public String getLocation() {
        return config.getProperty("google.location");
    }

    public String getBucketName() {
        return config.getProperty("google.bucket.name");
    }

    public String getGlossaryFileName(String targetLanguage) {
        return String.format(config.getProperty("glossary.file.format"), targetLanguage.toLowerCase());
    }

    public String getInputFilePath() {
        return config.getProperty("file.input.path");
    }

    public String getOutputFilePath(String targetLanguage) {
        return String.format(config.getProperty("file.output.path.format"), targetLanguage);
    }
}