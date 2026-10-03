package com.example.loanpackagesearch.controller;

import com.example.loanpackagesearch.model.Document;
import com.example.loanpackagesearch.model.LoanPackage;
import com.example.loanpackagesearch.model.Page;
import com.example.loanpackagesearch.model.SearchResult;
import com.example.loanpackagesearch.model.Version;
import com.example.loanpackagesearch.service.LoanPackageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchApiControllerPaginationTest {

    private SearchApiController controller;
    private LoanPackageService loanPackageService;

    @BeforeEach
    void setUp() {
        controller = new SearchApiController();
        loanPackageService = mock(LoanPackageService.class);
        ReflectionTestUtils.setField(controller, "loanPackageService", loanPackageService);
    }

    @Test
    void searchReturnsPaginatedResultsAndMetadata() throws IOException {
        when(loanPackageService.searchWithMetrics("income")).thenReturn(searchResult(sampleResults(6), "income", "income", false));

        ResponseEntity<?> response = controller.search("income", 2, 2);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) body.get("pagination");
        assertNotNull(pagination);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(6, body.get("totalResults"));
        assertEquals(2, body.get("resultCount"));
        assertEquals(2, pagination.get("currentPage"));
        assertEquals(3, pagination.get("totalPages"));
        assertEquals(2, pagination.get("offset"));
        assertTrue((Boolean) pagination.get("hasNextPage"));
        assertTrue((Boolean) pagination.get("hasPreviousPage"));
        assertEquals(2, results.size());
        assertEquals("Doc 3", results.get(0).get("documentName"));

        @SuppressWarnings("unchecked")
        Map<String, Object> timings = (Map<String, Object>) body.get("timings");
        assertNotNull(timings);
        assertEquals("plain-and", timings.get("queryMode"));
        assertEquals("income", timings.get("normalizedQuery"));
    }

    @Test
    void searchNormalizesOffsetBeyondLastPage() throws IOException {
        when(loanPackageService.searchWithMetrics("income")).thenReturn(searchResult(sampleResults(5), "income", "income", false));

        ResponseEntity<?> response = controller.search("income", 2, 99);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) body.get("pagination");
        assertNotNull(pagination);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(4, pagination.get("offset"));
        assertEquals(3, pagination.get("currentPage"));
        assertEquals(3, pagination.get("totalPages"));
        assertFalse((Boolean) pagination.get("hasNextPage"));
        assertTrue((Boolean) pagination.get("hasPreviousPage"));
        assertEquals(1, results.size());
        assertEquals("Doc 5", results.get(0).get("documentName"));
    }

    @Test
    void searchReturnsEmptyFirstPageMetadataWhenNoMatches() throws IOException {
        when(loanPackageService.searchWithMetrics("income")).thenReturn(searchResult(List.of(), "income", "income", false));

        ResponseEntity<?> response = controller.search("income", 50, 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> pagination = (Map<String, Object>) body.get("pagination");
        assertNotNull(pagination);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, body.get("totalResults"));
        assertEquals(0, body.get("resultCount"));
        assertEquals(0, pagination.get("currentPage"));
        assertEquals(0, pagination.get("totalPages"));
        assertFalse((Boolean) pagination.get("hasNextPage"));
        assertFalse((Boolean) pagination.get("hasPreviousPage"));
        assertTrue(results.isEmpty());
    }

    @Test
    void getPackageTreeRefreshesBeforeReturningData() throws IOException {
        LoanPackage pkg = samplePackage();
        when(loanPackageService.getCurrentPackage()).thenReturn(pkg);
        when(loanPackageService.isCurrentPackageDefault()).thenReturn(true);

        ResponseEntity<?> response = controller.getPackageTree();

        assertEquals(200, response.getStatusCode().value());
        inOrder(loanPackageService).verify(loanPackageService).refreshPackageIfModified();
        verify(loanPackageService).getCurrentPackage();
    }

    @Test
    void getPackageStructureRefreshesBeforeReturningData() throws IOException {
        LoanPackage pkg = samplePackage();
        when(loanPackageService.getCurrentPackage()).thenReturn(pkg);

        ResponseEntity<?> response = controller.getPackageStructure();

        assertEquals(200, response.getStatusCode().value());
        inOrder(loanPackageService).verify(loanPackageService).refreshPackageIfModified();
        verify(loanPackageService).getCurrentPackage();
    }

    @Test
    void getPageRefreshesBeforeResolvingDocument() throws IOException {
        LoanPackage pkg = samplePackage();
        when(loanPackageService.getCurrentPackage()).thenReturn(pkg);

        ResponseEntity<?> response = controller.getPage("doc-1", "v1", 1);

        assertEquals(200, response.getStatusCode().value());
        inOrder(loanPackageService).verify(loanPackageService).refreshPackageIfModified();
        verify(loanPackageService).getCurrentPackage();
    }

    @Test
    void getMetricsRefreshesBeforeReadingMetrics() throws IOException {
        LoanPackageService.PerformanceMetrics performanceMetrics = new LoanPackageService.PerformanceMetrics(10, 20, 30, 1024 * 1024, 3, 300);
        LoanPackageService.SearchRequestMetrics searchMetrics = new LoanPackageService.SearchRequestMetrics(1, 0, false, 0, 0, 2, 3, 4, 5, 15, "plain-and", "income", 2);
        when(loanPackageService.getLastMetrics()).thenReturn(performanceMetrics);
        when(loanPackageService.getLastSearchMetrics()).thenReturn(searchMetrics);

        ResponseEntity<?> response = controller.getMetrics();

        assertEquals(200, response.getStatusCode().value());
        inOrder(loanPackageService).verify(loanPackageService).refreshPackageIfModified();
        verify(loanPackageService).getLastMetrics();
        verify(loanPackageService).getLastSearchMetrics();
    }

    private LoanPackageService.SearchRequestResult searchResult(List<SearchResult> results,
                                                                String normalizedQuery,
                                                                String originalQuery,
                                                                boolean reloaded) {
        LoanPackageService.SearchRequestMetrics metrics = new LoanPackageService.SearchRequestMetrics(
                1,
                reloaded ? 10 : 0,
                reloaded,
                reloaded ? 6 : 0,
                reloaded ? 4 : 0,
                2,
                3,
                4,
                1,
                11,
                "plain-and",
                normalizedQuery,
                results.size());
        return new LoanPackageService.SearchRequestResult(results, metrics);
    }

    private List<SearchResult> sampleResults(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new SearchResult(
                        "doc-" + i,
                        "Doc " + i,
                        "Application",
                        "v1",
                        "Version 1",
                        i,
                        "snippet " + i,
                        1.0 - (i * 0.01)))
                .toList();
    }

    private LoanPackage samplePackage() throws IOException {
        Page page = new Page(1, "Sample page content");
        Version version = new Version("v1", "Version 1", List.of(page));
        Map<String, Version> versions = new LinkedHashMap<>();
        versions.put("v1", version);
        Document document = new Document("doc-1", "Doc 1", "Application", versions);
        Map<String, Document> documents = new LinkedHashMap<>();
        documents.put("doc-1", document);
        File source = File.createTempFile("controller-sample", ".txt");
        source.deleteOnExit();
        return new LoanPackage("pkg-1", "Sample", source, documents);
    }
}


