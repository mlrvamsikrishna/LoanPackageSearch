package com.example.loanpackagesearch.model;

/**
 * Represents a single search result - a page that matched the search query.
 *
 * This DTO contains all the information needed to display a search result to the user:
 * - Which document the match came from
 * - Which version and page within that document
 * - A snippet showing the matching text
 * - A relevance score
 *
 * Key design decisions:
 * - Immutable for thread-safety
 * - Relevance score is normalized (0.0 to 1.0)
 * - Text snippet is pre-truncated to avoid huge strings
 */
public class SearchResult implements Comparable<SearchResult> {
    private final String documentName;       // Name of the document
    private final String documentType;       // Type of document
    private final String versionName;        // Name of the version
    private final int pageNumber;            // Page number within the version
    private final String pageSnippet;        // Text snippet showing match context
    private final double relevanceScore;     // Relevance score (0.0 to 1.0)

    // For internal reference (not displayed)
    private final String documentId;
    private final String versionId;

    /**
     * Constructs a SearchResult with all necessary information.
     *
     * @param documentId internal document ID
     * @param documentName display name of document
     * @param documentType type of document
     * @param versionId internal version ID
     * @param versionName display name of version
     * @param pageNumber page number (1-indexed)
     * @param pageSnippet text snippet showing context
     * @param relevanceScore score from 0.0 to 1.0
     */
    public SearchResult(String documentId, String documentName, String documentType,
                        String versionId, String versionName, int pageNumber,
                        String pageSnippet, double relevanceScore) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.documentType = documentType;
        this.versionId = versionId;
        this.versionName = versionName;
        this.pageNumber = pageNumber;
        this.pageSnippet = pageSnippet != null ? pageSnippet : "";
        this.relevanceScore = Math.max(0.0, Math.min(1.0, relevanceScore));  // Clamp to [0,1]
    }

    // Getters for display
    public String getDocumentName() {
        return documentName;
    }

    public String getDocumentType() {
        return documentType;
    }

    public String getVersionName() {
        return versionName;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public String getPageSnippet() {
        return pageSnippet;
    }

    public double getRelevanceScore() {
        return relevanceScore;
    }

    // Getters for internal reference
    public String getDocumentId() {
        return documentId;
    }

    public String getVersionId() {
        return versionId;
    }

    /**
     * Implements Comparable to sort by relevance score in descending order.
     * When relevance scores are equal, sorts by document name (alphabetical).
     *
     * @param other another SearchResult to compare
     * @return comparison value following Comparable contract
     */
    @Override
    public int compareTo(SearchResult other) {
        // Sort by relevance score (descending)
        if (this.relevanceScore != other.relevanceScore) {
            return Double.compare(other.relevanceScore, this.relevanceScore);
        }
        // Tie-breaker: sort by document name (ascending)
        return this.documentName.compareTo(other.documentName);
    }

    /**
     * Returns a user-friendly representation of this result showing
     * the document hierarchy: DocumentName > Version > Page X
     *
     * @return formatted location string
     */
    public String getLocationPath() {
        return String.format("%s > %s > Page %d",
                documentName, versionName, pageNumber);
    }

    @Override
    public String toString() {
        return "SearchResult{" +
                "document='" + documentName + '\'' +
                ", page=" + pageNumber +
                ", relevance=" + String.format("%.2f", relevanceScore) +
                '}';
    }
}

