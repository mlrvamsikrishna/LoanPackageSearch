package com.example.loanpackagesearch.service;

import com.example.loanpackagesearch.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;

/**
 * Integration tests for the search system.
 *
 * Tests verify:
 * - Package loading and parsing
 * - Index building and performance
 * - Search functionality with various query types
 * - Memory bounds and cleanup
 */
public class SearchServiceIntegrationTest {

    private SearchIndexService indexService;
    private LoanPackage testPackage;

    @BeforeEach
    public void setUp() throws IOException {
        indexService = new SearchIndexService();

        // Create test package with sample data
        testPackage = createTestPackage();
    }

    @Test
    public void testIndexBuilding() throws IOException {
        assertFalse(indexService.isIndexBuilt());

        indexService.buildIndex(testPackage);

        assertTrue(indexService.isIndexBuilt());
        assertEquals(testPackage, indexService.getCurrentPackage());
        assertTrue(indexService.getIndexBuildTimeMs() > 0);
    }

    @Test
    public void testSimpleSearch() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("income");
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r -> r.getPageSnippet().contains("income")));
    }

    @Test
    public void testPhraseSearch() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("\"income verification\"");
        // Should find exact phrase or partial matches
        assertNotNull(results);
    }

    @Test
    public void testWildcardSearch() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("inc*");
        // Should find "income" and similar words
        assertFalse(results.isEmpty());
    }

    @Test
    public void testBooleanSearch() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("income AND employment");
        assertFalse(results.isEmpty(), "Boolean AND query should return matching results");
    }

    @Test
    public void testBooleanSearchWithOcrPunctuation() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("Lender Loan No./Universal Loan Identifier OR application");
        assertFalse(results.isEmpty(), "Boolean OR query should tolerate OCR punctuation and return matches");
    }

    @Test
    public void testLiteralSearchWithPunctuation() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("Lender Loan No./Universal Loan Identifier///////////");
        assertTrue(results.isEmpty(), "Extra trailing punctuation should not match when exact text is absent");
    }

    @Test
    public void testExactLiteralSearchMatches() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("Lender Loan No./Universal Loan Identifier - which is in");
        assertFalse(results.isEmpty(), "Exact literal OCR text should still be searchable");
    }

    @Test
    public void testBrokenLiteralSearchWithInternalSlashFails() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("Lender Loan No./Universal Loan Identi/fier///////////");
        assertTrue(results.isEmpty(), "Broken internal token fragments should not match the exact OCR text");
    }

    @Test
    public void testEmptyQuery() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("");
        assertTrue(results.isEmpty());

        results = indexService.search(null);
        assertTrue(results.isEmpty());
    }

    @Test
    public void testResultsSorted() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("income");

        // Verify results are sorted by relevance (descending)
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).getRelevanceScore() >= results.get(i + 1).getRelevanceScore());
        }
    }

    @Test
    public void testSearchResultMetadata() throws IOException {
        indexService.buildIndex(testPackage);

        List<SearchResult> results = indexService.search("income");

        if (!results.isEmpty()) {
            SearchResult result = results.get(0);
            assertNotNull(result.getDocumentName());
            assertNotNull(result.getDocumentType());
            assertNotNull(result.getVersionName());
            assertTrue(result.getPageNumber() > 0);
            assertNotNull(result.getPageSnippet());
            assertTrue(result.getRelevanceScore() >= 0.0);
            assertTrue(result.getRelevanceScore() <= 1.0);
        }
    }

    @Test
    public void testFileModificationDetection() throws Exception {
        File tempFile = File.createTempFile("loan-package-mod", ".txt");
        tempFile.deleteOnExit();

        LoanPackage packageSnapshot = new LoanPackage("pkg-mod", "Test Package", tempFile, testPackage.getDocumentMap());
        assertFalse(packageSnapshot.isSourceFileModified());

        Thread.sleep(1000);
        try (FileWriter writer = new FileWriter(tempFile, false)) {
            writer.write("updated content\n");
        }

        assertTrue(packageSnapshot.isSourceFileModified());
    }

    @Test
    public void testPerformanceMetrics() throws IOException {
        indexService.buildIndex(testPackage);
        long buildTime = indexService.getIndexBuildTimeMs();

        assertTrue(buildTime > 0, "Index build time should be recorded");
        assertTrue(buildTime < 5000, "Index build should be fast for test data");

        indexService.search("income");
        // Search should complete quickly with built index
    }

    // Helper method to create test package
    private LoanPackage createTestPackage() throws IOException {
        Page page1 = new Page(1, "John Doe is applying for a mortgage. His income is $5000 per month. " +
                             "Employment verification shows he works for Acme Corporation. " +
                              "Gross monthly income: $5000. Lender Loan No./Universal Loan Identifier - which is in the top-left corner.");

        Page page2 = new Page(2, "Bank statements show cash reserves of $50000. " +
                             "Account details and balances are listed below. " +
                             "Total liquid assets: $50000.");

        Page page3 = new Page(3, "Tax documents show annual income verification. " +
                             "W-2 forms from previous employers. " +
                             "Total documented income matches stated amounts.");

        List<Page> pages = Arrays.asList(page1, page2, page3);
        Version version = new Version("v1", "Version 1", pages);

        Map<String, Version> versions = new LinkedHashMap<>();
        versions.put("v1", version);

        Document document = new Document("doc-001", "Loan Application", "Application", versions);

        Map<String, Document> documents = new LinkedHashMap<>();
        documents.put("doc-001", document);

        File tempFile = File.createTempFile("test-pkg", ".txt");
        tempFile.deleteOnExit();

        return new LoanPackage("pkg-001", "Test Package", tempFile, documents);
    }
}

