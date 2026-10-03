package com.example.loanpackagesearch.model;

import java.io.File;
import java.util.*;

/**
 * Represents the complete loan package containing all documents, versions, and pages.
 *
 * This is the root object of the data model. A loan package represents one complete
 * mortgage application including all supporting documents like loan applications,
 * bank statements, tax forms, pay slips, etc.
 *
 * Key design decisions:
 * - Maintains reference to source file for change detection
 * - Stores metadata about package (name, date, etc.)
 * - Provides hierarchical access to all contained data
 */
public class LoanPackage {
    private final String packageId;              // Unique identifier for this package
    private final String packageName;            // Display name
    private final Date createdDate;              // When the package was loaded
    private final File sourceFile;               // Reference to source file for change detection
    private final long sourceFileLastModified;   // Last modification timestamp
    private final Map<String, Document> documents;  // Map of document ID to Document

    /**
     * Constructs a LoanPackage with all necessary information.
     *
     * @param packageId unique identifier
     * @param packageName display name
     * @param sourceFile the source file this package was loaded from
     * @param documents map of document ID to Document objects
     */
    public LoanPackage(String packageId, String packageName, File sourceFile,
                       Map<String, Document> documents) {
        this.packageId = packageId;
        this.packageName = packageName;
        this.createdDate = new Date();
        this.sourceFile = sourceFile;
        this.sourceFileLastModified = sourceFile.lastModified();
        // Create an unmodifiable copy to prevent external modification
        this.documents = Collections.unmodifiableMap(new LinkedHashMap<>(documents));
    }

    // Getters
    public String getPackageId() {
        return packageId;
    }

    public String getPackageName() {
        return packageName;
    }

    public Date getCreatedDate() {
        return new Date(createdDate.getTime());  // Return copy for immutability
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public Collection<Document> getDocuments() {
        return documents.values();
    }

    public Map<String, Document> getDocumentMap() {
        return documents;
    }

    /**
     * Gets a document by ID.
     *
     * @param documentId the document ID
     * @return the Document object, or null if not found
     */
    public Document getDocument(String documentId) {
        return documents.get(documentId);
    }

    /**
     * Gets the total number of documents in this package.
     *
     * @return document count
     */
    public int getDocumentCount() {
        return documents.size();
    }

    /**
     * Gets the total page count across all documents and versions.
     *
     * @return total page count
     */
    public int getTotalPageCount() {
        return documents.values().stream()
                .mapToInt(Document::getTotalPageCount)
                .sum();
    }

    /**
     * Gets the total character count across entire package.
     * Used for memory management and statistics.
     *
     * @return total character count
     */
    public int getTotalCharCount() {
        return documents.values().stream()
                .mapToInt(Document::getTotalCharCount)
                .sum();
    }

    /**
     * Checks if the source file has been modified since this package was loaded.
     * This is used for detecting changes while the application is running.
     *
     * @return true if the source file has been modified
     */
    public boolean isSourceFileModified() {
        return sourceFile.lastModified() != sourceFileLastModified;
    }

    @Override
    public String toString() {
        return "LoanPackage{" +
                "packageId='" + packageId + '\'' +
                ", packageName='" + packageName + '\'' +
                ", documentCount=" + documents.size() +
                ", totalPages=" + getTotalPageCount() +
                '}';
    }
}

