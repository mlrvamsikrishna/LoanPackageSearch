package com.example.loanpackagesearch.model;

import java.util.*;

/**
 * Represents a document within a loan package.
 *
 * A document can have one or more versions, and each version contains pages.
 * Examples of documents: "Loan Application", "Bank Statements", "Tax Documents", etc.
 *
 * Key design decisions:
 * - Uses TreeMap to maintain versions in sorted order
 * - Provides factory method for common document types
 * - Immutable collection access to prevent external modification
 */
public class Document {
    private final String documentId;         // Unique identifier (e.g., "loan_app_001")
    private final String documentName;       // Display name (e.g., "Loan Application")
    private final String documentType;       // Type of document (e.g., "Loan Application", "Bank Statement")
    private final Map<String, Version> versions;  // Map of version ID to Version objects

    /**
     * Constructs a Document with ID, name, type, and versions.
     *
     * @param documentId unique identifier for this document
     * @param documentName display name for this document
     * @param documentType type/category of document
     * @param versions map of version ID to Version objects
     */
    public Document(String documentId, String documentName, String documentType,
                    Map<String, Version> versions) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.documentType = documentType;
        // Create an unmodifiable copy to prevent external modification
        this.versions = Collections.unmodifiableMap(new TreeMap<>(versions));
    }

    // Getters
    public String getDocumentId() {
        return documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public String getDocumentType() {
        return documentType;
    }

    public Collection<Version> getVersions() {
        return versions.values();
    }

    public Map<String, Version> getVersionMap() {
        return versions;
    }

    /**
     * Gets a specific version by ID.
     *
     * @param versionId the version ID to retrieve
     * @return the Version object, or null if not found
     */
    public Version getVersion(String versionId) {
        return versions.get(versionId);
    }

    /**
     * Gets the total number of versions in this document.
     *
     * @return version count
     */
    public int getVersionCount() {
        return versions.size();
    }

    /**
     * Gets the total page count across all versions.
     *
     * @return total page count
     */
    public int getTotalPageCount() {
        return versions.values().stream()
                .mapToInt(Version::getPageCount)
                .sum();
    }

    /**
     * Gets the total character count across all versions and pages.
     * Used for memory management and statistics.
     *
     * @return total character count
     */
    public int getTotalCharCount() {
        return versions.values().stream()
                .mapToInt(Version::getTotalCharCount)
                .sum();
    }

    @Override
    public String toString() {
        return "Document{" +
                "documentId='" + documentId + '\'' +
                ", documentName='" + documentName + '\'' +
                ", documentType='" + documentType + '\'' +
                ", versions=" + versions.size() +
                '}';
    }
}

