package com.example.loanpackagesearch.model;

import java.util.*;

/**
 * Represents a version of a document within a loan package.
 *
 * A document can have multiple versions (e.g., "Version 1", "Version 2"), and each
 * version contains multiple pages. This class manages the pages within a version.
 *
 * Key design decisions:
 * - Uses a LinkedHashMap to maintain insertion order for pages
 * - Immutable after construction via unmodifiable collection
 * - Provides convenient access to pages by number
 */
public class Version {
    private final String versionId;          // Unique identifier for this version (e.g., "v1", "v2")
    private final String versionName;        // Display name (e.g., "Version 1", "Original")
    private final List<Page> pages;          // List of pages in order

    /**
     * Constructs a Version with ID, name, and list of pages.
     *
     * @param versionId unique identifier for this version
     * @param versionName display name for this version
     * @param pages list of Page objects in order
     */
    public Version(String versionId, String versionName, List<Page> pages) {
        this.versionId = versionId;
        this.versionName = versionName;
        // Create an unmodifiable copy to prevent external modification
        this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
    }

    // Getters
    public String getVersionId() {
        return versionId;
    }

    public String getVersionName() {
        return versionName;
    }

    public List<Page> getPages() {
        return pages;
    }

    /**
     * Gets the total number of pages in this version.
     *
     * @return page count
     */
    public int getPageCount() {
        return pages.size();
    }

    /**
     * Gets a page by its page number (1-indexed).
     *
     * @param pageNumber 1-indexed page number
     * @return the Page object, or null if not found
     */
    public Page getPageByNumber(int pageNumber) {
        for (Page page : pages) {
            if (page.getPageNumber() == pageNumber) {
                return page;
            }
        }
        return null;
    }

    /**
     * Gets the total character count across all pages.
     * Used for memory management and statistics.
     *
     * @return total character count
     */
    public int getTotalCharCount() {
        return pages.stream().mapToInt(Page::getCharCount).sum();
    }

    @Override
    public String toString() {
        return "Version{" +
                "versionId='" + versionId + '\'' +
                ", versionName='" + versionName + '\'' +
                ", pageCount=" + pages.size() +
                '}';
    }
}

