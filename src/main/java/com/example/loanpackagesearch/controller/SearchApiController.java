package com.example.loanpackagesearch.controller;

import com.example.loanpackagesearch.model.Document;
import com.example.loanpackagesearch.model.LoanPackage;
import com.example.loanpackagesearch.model.Page;
import com.example.loanpackagesearch.model.SearchResult;
import com.example.loanpackagesearch.model.Version;
import com.example.loanpackagesearch.service.LoanPackageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * REST API controller for loan package search.
 *
 * Provides endpoints for:
 * 1. Loading packages
 * 2. Searching documents
 * 3. Getting package structure
 * 4. Retrieving performance metrics
 *
 * Design:
 * - RESTful endpoints following HTTP conventions
 * - JSON responses for easy client integration
 * - Proper HTTP status codes
 * - Error handling with descriptive messages
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SearchApiController {
    private static final Logger logger = Logger.getLogger(SearchApiController.class.getName());

    @Autowired
    private LoanPackageService loanPackageService;


    /**
     * Searches the loaded package for the supplied query.
     *
     * Supports pagination via limit and offset parameters.
     * Default: limit=50, offset=0 (first page of 50 results)
     *
     * @param query the Lucene query string, such as "income" or "cash to close"
     * @param limit number of results per page (default: 50, max: 1000)
     * @param offset number of results to skip (default: 0)
     * @return paginated search results ranked by relevance
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "limit", defaultValue = "50", required = false) int limit,
            @RequestParam(name = "offset", defaultValue = "0", required = false) int offset) {
        try {
            if (query == null || query.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Query parameter required"));
            }

            // Validate pagination parameters
            limit = Math.max(1, Math.min(limit, 1000));  // Clamp between 1 and 1000
            offset = Math.max(0, offset);                 // Ensure offset is non-negative

            LoanPackageService.SearchRequestResult searchResult = loanPackageService.searchWithMetrics(query);
            List<SearchResult> allResults = searchResult.results();
            LoanPackageService.SearchRequestMetrics timings = searchResult.metrics();

            // Calculate pagination
            int totalResults = allResults.size();
            int totalPages = totalResults == 0 ? 0 : (totalResults + limit - 1) / limit;
            int normalizedOffset = offset;
            if (totalPages > 0) {
                int lastPageOffset = (totalPages - 1) * limit;
                normalizedOffset = Math.min(offset, lastPageOffset);
            } else {
                normalizedOffset = 0;
            }
            int currentPage = totalPages == 0 ? 0 : (normalizedOffset / limit) + 1;

            // Slice results for this page
            int endIndex = Math.min(normalizedOffset + limit, totalResults);
            List<SearchResult> pageResults = allResults.subList(normalizedOffset, endIndex);

            List<Map<String, Object>> resultsList = pageResults.stream()
                    .map(this::searchResultToMap)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "query", query,
                    "totalResults", totalResults,
                    "resultCount", pageResults.size(),
                    "searchTimeMs", timings.totalRequestMs(),
                    "timings", buildTimingMap(timings),
                    "pagination", Map.of(
                            "currentPage", currentPage,
                            "pageSize", limit,
                            "totalPages", totalPages,
                            "offset", normalizedOffset,
                            "hasNextPage", endIndex < totalResults,
                            "hasPreviousPage", normalizedOffset > 0
                    ),
                    "results", resultsList
            ));

        } catch (Exception e) {
            logger.severe("Error searching: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Search failed: " + e.getMessage()));
        }
    }

    /**
     * Returns the currently loaded package structure as a hierarchical document tree.
     *
     * @return document, version, and page structure for the current package
     */
    @GetMapping("/package/structure")
    public ResponseEntity<?> getPackageStructure() {
        try {
            loanPackageService.refreshPackageIfModified();
            LoanPackage pkg = loanPackageService.getCurrentPackage();
            if (pkg == null) {
                return ResponseEntity.ok(Map.of("error", "No package loaded"));
            }

            List<Map<String, Object>> documents = pkg.getDocuments().stream()
                    .map(this::documentToMap)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "packageId", pkg.getPackageId(),
                    "packageName", pkg.getPackageName(),
                    "documentCount", pkg.getDocumentCount(),
                    "totalPageCount", pkg.getTotalPageCount(),
                    "totalCharacters", pkg.getTotalCharCount(),
                    "documents", documents
            ));

        } catch (Exception e) {
            logger.severe("Error getting structure: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get structure: " + e.getMessage()));
        }
    }

    /**
     * Returns the currently loaded package as a nested tree grouped by document type,
     * with versions and page nodes included for browser rendering.
     *
     * @return package tree structure for the current package
     */
    @GetMapping("/package/tree")
    public ResponseEntity<?> getPackageTree() {
        try {
            loanPackageService.refreshPackageIfModified();
            LoanPackage pkg = loanPackageService.getCurrentPackage();
            if (pkg == null) {
                return ResponseEntity.ok(Map.of("error", "No package loaded"));
            }

            boolean isDefault = loanPackageService.isCurrentPackageDefault();

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "packageId", pkg.getPackageId(),
                    "packageName", pkg.getPackageName(),
                    "isDefaultPackage", isDefault,
                    "documentCount", pkg.getDocumentCount(),
                    "totalPageCount", pkg.getTotalPageCount(),
                    "totalCharacters", pkg.getTotalCharCount(),
                    "tree", buildPackageTree(pkg)
            ));

        } catch (Exception e) {
            logger.severe("Error getting package tree: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get package tree: " + e.getMessage()));
        }
    }

    /**
     * Retrieves the full OCR contents of a specific page.
     *
     * @param docId document identifier
     * @param versionId version identifier
     * @param pageNum one-based page number
     * @return page content and metadata
     */
    @GetMapping("/page")
    public ResponseEntity<?> getPage(@RequestParam String docId,
                                     @RequestParam String versionId,
                                     @RequestParam int pageNum) {
        try {
            loanPackageService.refreshPackageIfModified();
            LoanPackage pkg = loanPackageService.getCurrentPackage();
            if (pkg == null) {
                return ResponseEntity.ok(Map.of("error", "No package loaded"));
            }

            Document doc = pkg.getDocument(docId);
            if (doc == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Document not found: " + docId));
            }

            Version version = doc.getVersion(versionId);
            if (version == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Version not found: " + versionId));
            }

            Page page = version.getPageByNumber(pageNum);
            if (page == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Page not found: " + pageNum));
            }

            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "documentName", doc.getDocumentName(),
                    "versionName", version.getVersionName(),
                    "pageNumber", page.getPageNumber(),
                    "content", page.getOcrText()
            ));

        } catch (Exception e) {
            logger.severe("Error getting page: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get page: " + e.getMessage()));
        }
    }

    /**
     * Retrieves the last load and index performance metrics for the current package.
     *
     * @return performance metrics or an error response if none are available
     */
    @GetMapping("/metrics")
    public ResponseEntity<?> getMetrics() {
        try {
            loanPackageService.refreshPackageIfModified();
            LoanPackageService.PerformanceMetrics metrics = loanPackageService.getLastMetrics();
            if (metrics == null) {
                return ResponseEntity.ok(Map.of("error", "No metrics available"));
            }

            LoanPackageService.SearchRequestMetrics searchMetrics = loanPackageService.getLastSearchMetrics();

            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("status", "success");
            response.put("loadTimeMs", metrics.loadTimeMs);
            response.put("indexTimeMs", metrics.indexTimeMs);
            response.put("totalTimeMs", metrics.totalTimeMs);
            response.put("memoryUsedMB", metrics.memoryUsedBytes / 1024 / 1024);
            response.put("totalPages", metrics.totalPages);
            response.put("totalCharacters", metrics.totalCharacters);

            if (searchMetrics != null) {
                response.put("lastSearchTimings", buildTimingMap(searchMetrics));
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.severe("Error getting metrics: " + e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get metrics: " + e.getMessage()));
        }
    }

    /**
     * Converts a search result model into a JSON-safe map for the REST response.
     *
     * @param result the model result
     * @return response map with metadata and snippet
     */
    private Map<String, Object> searchResultToMap(SearchResult result) {
        return Map.of(
                "documentName", result.getDocumentName(),
                "documentType", result.getDocumentType(),
                "versionName", result.getVersionName(),
                "pageNumber", result.getPageNumber(),
                "snippet", result.getPageSnippet(),
                "relevanceScore", String.format("%.2f", result.getRelevanceScore()),
                "locationPath", result.getLocationPath(),
                "documentId", result.getDocumentId(),
                "versionId", result.getVersionId()
        );
    }

    private Map<String, Object> buildTimingMap(LoanPackageService.SearchRequestMetrics timings) {
        Map<String, Object> timingMap = new java.util.LinkedHashMap<>();
        timingMap.put("changeDetectionMs", timings.changeDetectionMs());
        timingMap.put("reloadMs", timings.reloadMs());
        timingMap.put("reloaded", timings.reloaded());
        timingMap.put("packageLoadTimeMs", timings.packageLoadTimeMs());
        timingMap.put("packageIndexTimeMs", timings.packageIndexTimeMs());
        timingMap.put("queryPreparationMs", timings.queryPreparationMs());
        timingMap.put("luceneSearchMs", timings.luceneSearchMs());
        timingMap.put("snippetGenerationMs", timings.snippetGenerationMs());
        timingMap.put("resultSortingMs", timings.resultSortingMs());
        timingMap.put("totalRequestMs", timings.totalRequestMs());
        timingMap.put("queryMode", timings.queryMode());
        timingMap.put("normalizedQuery", timings.normalizedQuery());
        timingMap.put("totalHits", timings.totalHits());
        return timingMap;
    }

    /**
     * Converts a document model into a JSON-safe map for the package structure API.
     *
     * @param doc the document to convert
     * @return detailed document representation for the client
     */
    private Map<String, Object> documentToMap(Document doc) {
        List<Map<String, Object>> versions = doc.getVersions().stream()
                .map(this::versionToMap)
                .collect(Collectors.toList());

        return Map.of(
                "id", doc.getDocumentId(),
                "name", doc.getDocumentName(),
                "type", doc.getDocumentType(),
                "versionCount", doc.getVersionCount(),
                "pageCount", doc.getTotalPageCount(),
                "versions", versions
        );
    }

    /**
     * Converts a version model into a JSON-safe map for the package structure API.
     *
     * @param version the version to convert
     * @return version metadata and page summary
     */
    private Map<String, Object> versionToMap(Version version) {
        List<Map<String, Object>> pages = version.getPages().stream()
                .map(p -> {
                    Map<String, Object> pageMap = new java.util.LinkedHashMap<>();
                    pageMap.put("pageNumber", p.getPageNumber());
                    pageMap.put("hasContent", p.hasContent());
                    pageMap.put("charCount", p.getCharCount());
                    return pageMap;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", version.getVersionId());
        result.put("name", version.getVersionName());
        result.put("pageCount", version.getPageCount());
        result.put("pages", pages);
        return result;
    }

    /**
     * Builds a nested tree representation grouped by document type, then document,
     * then version, then page nodes.
     */
    private List<Map<String, Object>> buildPackageTree(LoanPackage pkg) {
        Map<String, List<Document>> documentsByType = pkg.getDocuments().stream()
                .collect(Collectors.groupingBy(Document::getDocumentType, java.util.LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> tree = new java.util.ArrayList<>();
        for (Map.Entry<String, List<Document>> typeEntry : documentsByType.entrySet()) {
            List<Map<String, Object>> documentNodes = typeEntry.getValue().stream()
                    .map(this::buildDocumentNode)
                    .collect(Collectors.toList());

            Map<String, Object> typeNode = new java.util.LinkedHashMap<>();
            typeNode.put("label", typeEntry.getKey());
            typeNode.put("kind", "documentType");
            typeNode.put("count", typeEntry.getValue().size());
            typeNode.put("children", documentNodes);
            tree.add(typeNode);
        }

        return tree;
    }

    /**
     * Builds a document node with nested version and page nodes.
     */
    private Map<String, Object> buildDocumentNode(Document doc) {
        List<Map<String, Object>> versionNodes = doc.getVersions().stream()
                .map(this::buildVersionNode)
                .collect(Collectors.toList());

        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("label", doc.getDocumentName());
        node.put("kind", "document");
        node.put("documentId", doc.getDocumentId());
        node.put("pageCount", doc.getTotalPageCount());
        node.put("versionCount", doc.getVersionCount());
        node.put("children", versionNodes);
        return node;
    }

    /**
     * Builds a version node with nested page nodes.
     */
    private Map<String, Object> buildVersionNode(Version version) {
        List<Map<String, Object>> pageNodes = version.getPages().stream()
                .map(page -> {
                    Map<String, Object> pageNode = new java.util.LinkedHashMap<>();
                    pageNode.put("label", "Page " + page.getPageNumber());
                    pageNode.put("kind", "page");
                    pageNode.put("pageNumber", page.getPageNumber());
                    pageNode.put("charCount", page.getCharCount());
                    pageNode.put("hasContent", page.hasContent());
                    return pageNode;
                })
                .collect(Collectors.toList());

        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("label", version.getVersionName());
        node.put("kind", "version");
        node.put("versionId", version.getVersionId());
        node.put("pageCount", version.getPageCount());
        node.put("children", pageNodes);
        return node;
    }
}
