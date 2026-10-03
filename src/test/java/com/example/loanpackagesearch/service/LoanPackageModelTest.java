package com.example.loanpackagesearch.service;

import com.example.loanpackagesearch.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for data model classes.
 *
 * Tests verify:
 * - Correct page creation and content access
 * - Version management and page retrieval
 * - Document hierarchy and metadata
 * - LoanPackage integrity and modification detection
 */
public class LoanPackageModelTest {

    private LoanPackage testPackage;

    @BeforeEach
    public void setUp() {
        // Create test data hierarchy
        Page page1 = new Page(1, "This is page one content");
        Page page2 = new Page(2, "This is page two content");
        Page page3 = new Page(3, "");  // Empty page

        java.util.List<Page> pages = java.util.Arrays.asList(page1, page2, page3);
        Version version1 = new Version("v1", "Version 1", pages);

        java.util.Map<String, Version> versions = new java.util.LinkedHashMap<>();
        versions.put("v1", version1);

        Document document = new Document("doc-001", "Test Document", "Test", versions);

        java.util.Map<String, Document> documents = new java.util.LinkedHashMap<>();
        documents.put("doc-001", document);

        java.io.File tempFile;
        try {
            tempFile = java.io.File.createTempFile("test", ".txt");
            tempFile.deleteOnExit();
        } catch (Exception e) {
            fail("Failed to create temp file");
            return;
        }

        testPackage = new LoanPackage("pkg-001", "Test Package", tempFile, documents);
    }

    @Test
    public void testPageCreation() {
        Page page = new Page(1, "Test content");
        assertEquals(1, page.getPageNumber());
        assertEquals("Test content", page.getOcrText());
        assertTrue(page.hasContent());
        assertEquals(12, page.getCharCount());
    }

    @Test
    public void testPageWithEmptyContent() {
        Page page = new Page(1, "");
        assertFalse(page.hasContent());
        assertEquals(0, page.getCharCount());
    }

    @Test
    public void testPageWithNullContent() {
        Page page = new Page(1, null);
        assertFalse(page.hasContent());
        assertEquals(0, page.getCharCount());
    }

    @Test
    public void testVersionPageRetrieval() {
        Version version = testPackage.getDocuments().iterator().next().getVersions().iterator().next();
        assertEquals(3, version.getPageCount());

        Page page = version.getPageByNumber(1);
        assertNotNull(page);
        assertEquals("This is page one content", page.getOcrText());

        Page notFound = version.getPageByNumber(99);
        assertNull(notFound);
    }

    @Test
    public void testDocumentMetadata() {
        Document doc = testPackage.getDocument("doc-001");
        assertNotNull(doc);
        assertEquals("Test Document", doc.getDocumentName());
        assertEquals("Test", doc.getDocumentType());
        assertEquals(1, doc.getVersionCount());
    }

    @Test
    public void testPackagePageCount() {
        assertEquals(3, testPackage.getTotalPageCount());
    }

    @Test
    public void testPackageCharCount() {
        // Page 1: "This is page one content" = 24 chars
        // Page 2: "This is page two content" = 24 chars
        // Page 3: "" = 0 chars
        // Total: 48 chars
        assertEquals(48, testPackage.getTotalCharCount());
    }

    @Test
    public void testSearchResultSorting() {
        SearchResult r1 = new SearchResult("d1", "Doc A", "Type", "v1", "V1", 1, "snippet", 0.9);
        SearchResult r2 = new SearchResult("d2", "Doc B", "Type", "v1", "V1", 1, "snippet", 0.8);
        SearchResult r3 = new SearchResult("d3", "Doc C", "Type", "v1", "V1", 1, "snippet", 0.8);

        java.util.List<SearchResult> results = java.util.Arrays.asList(r2, r3, r1);
        java.util.Collections.sort(results);

        assertEquals(0.9, results.get(0).getRelevanceScore());
        assertEquals(0.8, results.get(1).getRelevanceScore());
        assertEquals("Doc B", results.get(1).getDocumentName());  // Tie-breaker
    }

    @Test
    public void testSearchResultLocationPath() {
        SearchResult result = new SearchResult("d1", "Loan App", "Application", "v1", "Original", 5, "test", 0.8);
        String path = result.getLocationPath();
        assertTrue(path.contains("Loan App"));
        assertTrue(path.contains("Original"));
        assertTrue(path.contains("Page 5"));
    }

    @Test
    public void testImmutability() {
        java.util.Collection<Document> docs = testPackage.getDocuments();
        // Verify returned collection is unmodifiable by trying to modify the underlying collection
        // Collections returned by getDocuments() should throw UnsupportedOperationException
        assertThrows(Exception.class, () -> {
            // Try to add to collection - should fail for unmodifiable collection
            java.util.List<Document> list = new java.util.ArrayList<>(docs);
            testPackage.getDocuments().clear();  // This should throw
        });
    }
}


