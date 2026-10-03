package com.example.loanpackagesearch.controller;

import com.example.loanpackagesearch.model.SearchResult;
import com.example.loanpackagesearch.service.LoanPackageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
        when(loanPackageService.search("income")).thenReturn(sampleResults(6));

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
    }

    @Test
    void searchNormalizesOffsetBeyondLastPage() throws IOException {
        when(loanPackageService.search("income")).thenReturn(sampleResults(5));

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
        when(loanPackageService.search("income")).thenReturn(List.of());

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
}


