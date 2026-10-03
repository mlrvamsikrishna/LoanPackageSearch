package com.example.loanpackagesearch.model;

/**
 * Represents a single page within a document version.
 *
 * This class encapsulates the OCR text content of a page along with its metadata.
 * Pages are the atomic unit of the search system - search results reference specific pages.
 *
 * Key design decisions:
 * - Immutable once created for thread-safety
 * - pageNumber is 1-indexed for user-friendly display
 * - ocrText can be null/empty if OCR failed for this page
 */
public class Page {
    private final int pageNumber;      // 1-indexed page number within the version
    private final String ocrText;      // The extracted OCR text content
    private final int charCount;       // Total characters in OCR text (for memory tracking)

    /**
     * Constructs a Page with page number and OCR text content.
     *
     * @param pageNumber 1-indexed page number within the parent version
     * @param ocrText the OCR text extracted from this page (can be empty)
     */
    public Page(int pageNumber, String ocrText) {
        this.pageNumber = pageNumber;
        this.ocrText = ocrText != null ? ocrText : "";
        this.charCount = this.ocrText.length();
    }

    // Getters
    public int getPageNumber() {
        return pageNumber;
    }

    public String getOcrText() {
        return ocrText;
    }

    public int getCharCount() {
        return charCount;
    }

    /**
     * Checks if this page has any OCR text content.
     *
     * @return true if page has non-empty OCR text
     */
    public boolean hasContent() {
        return !ocrText.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "Page{" +
                "pageNumber=" + pageNumber +
                ", charCount=" + charCount +
                '}';
    }
}

