package com.example.loanpackagesearch.service;

import com.example.loanpackagesearch.model.*;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Main orchestration service for loan package management.
 *
 * This service:
 * 1. Manages the lifecycle of loan packages
 * 2. Coordinates loading and indexing
 * 3. Handles file change detection
 * 4. Tracks performance metrics
 * 5. Manages concurrent access safely
 *
 * Design:
 * - Singleton service coordinating other services
 * - Thread-safe with synchronized methods
 * - Caches loaded package to avoid reloading
 * - Provides performance metrics for reporting
 */
@Service
public class LoanPackageService {
    private static final Logger logger = Logger.getLogger(LoanPackageService.class.getName());

    private static final long MAX_PACKAGE_BYTES = 500 * 1024 * 1024;  // 500 MB

    @Value("${app.data-root:./data}")
    private String dataRoot;

    @Value("${app.seed-file:loan-package-seed.txt}")
    private String seedFileName;

    @Autowired
    private LoanPackageLoader packageLoader;

    @Autowired
    private SearchIndexService searchIndexService;

    // Cached package and metrics
    private LoanPackage currentPackage;
    private PerformanceMetrics lastMetrics;
    private SearchRequestMetrics lastSearchMetrics;
    private boolean isCurrentPackageDefault;  // Track if using default vs override

    public record SearchRequestMetrics(
            long changeDetectionMs,
            long reloadMs,
            boolean reloaded,
            long packageLoadTimeMs,
            long packageIndexTimeMs,
            long queryPreparationMs,
            long luceneSearchMs,
            long snippetGenerationMs,
            long resultSortingMs,
            long totalRequestMs,
            String queryMode,
            String normalizedQuery,
            int totalHits) {
    }

    public record SearchRequestResult(List<SearchResult> results, SearchRequestMetrics metrics) {
    }

    private record RefreshOutcome(long changeDetectionMs, long reloadMs, boolean reloaded) {
    }

    @PostConstruct
    public void initializeDefaultPackage() {
        try {
            loadDefaultPackageIfAvailable();
        } catch (IOException e) {
            logger.warning("Default seed package could not be loaded: " + e.getMessage());
        }
    }

    /**
     * Searches the current package.
     *
     * This method:
     * 1. Checks if index is built
     * 2. Detects file changes and rebuilds if needed
     * 3. Executes the search
     * 4. Sorts results by relevance
     *
     * @param queryString the search query
     * @return list of SearchResult objects sorted by relevance
     * @throws IOException if search fails
     */
    public synchronized List<SearchResult> search(String queryString) throws IOException {
        return searchWithMetrics(queryString).results();
    }

    /**
     * Searches the current package and returns detailed per-stage timings.
     */
    public synchronized SearchRequestResult searchWithMetrics(String queryString) throws IOException {
        long requestStart = System.nanoTime();
        RefreshOutcome refreshOutcome = refreshPackageIfModifiedInternal();

        if (currentPackage == null) {
            logger.warning("No package loaded");
            lastSearchMetrics = new SearchRequestMetrics(
                    refreshOutcome.changeDetectionMs,
                    refreshOutcome.reloadMs,
                    refreshOutcome.reloaded,
                    refreshOutcome.reloaded && lastMetrics != null ? lastMetrics.loadTimeMs : 0,
                    refreshOutcome.reloaded && lastMetrics != null ? lastMetrics.indexTimeMs : 0,
                    0,
                    0,
                    0,
                    0,
                    nanosToMillis(System.nanoTime() - requestStart),
                    "empty",
                    queryString == null ? "" : queryString.trim(),
                    0);
            return new SearchRequestResult(Collections.emptyList(), lastSearchMetrics);
        }

        SearchIndexService.SearchExecution execution = searchIndexService.searchWithMetrics(queryString);
        List<SearchResult> results = new ArrayList<>(execution.results());

        long sortStart = System.nanoTime();
        Collections.sort(results);
        long resultSortingMs = nanosToMillis(System.nanoTime() - sortStart);

        SearchIndexService.SearchStageMetrics searchMetrics = execution.metrics();
        lastSearchMetrics = new SearchRequestMetrics(
                refreshOutcome.changeDetectionMs,
                refreshOutcome.reloadMs,
                refreshOutcome.reloaded,
                refreshOutcome.reloaded && lastMetrics != null ? lastMetrics.loadTimeMs : 0,
                refreshOutcome.reloaded && lastMetrics != null ? lastMetrics.indexTimeMs : 0,
                searchMetrics.queryPreparationMs(),
                searchMetrics.luceneSearchMs(),
                searchMetrics.snippetGenerationMs(),
                resultSortingMs,
                nanosToMillis(System.nanoTime() - requestStart),
                searchMetrics.queryMode(),
                searchMetrics.normalizedQuery(),
                searchMetrics.totalHits());

        return new SearchRequestResult(results, lastSearchMetrics);
    }

    /**
     * Gets the current loan package.
     *
     * @return the current LoanPackage or null if none loaded
     */
    public synchronized LoanPackage getCurrentPackage() {
        return currentPackage;
    }

    /**
     * Gets the last performance metrics.
     *
     * @return the last PerformanceMetrics or null
     */
    public synchronized PerformanceMetrics getLastMetrics() {
        return lastMetrics;
    }

    public synchronized SearchRequestMetrics getLastSearchMetrics() {
        return lastSearchMetrics;
    }

    /**
     * Checks if the currently loaded package is the default seed package.
     *
     * @return true if the current package is the default seed, false if override
     */
    public synchronized boolean isCurrentPackageDefault() {
        return isCurrentPackageDefault;
    }

    /**
     * Checks if a package is currently loaded.
     *
     * @return true if package is loaded and indexed
     */
    public synchronized boolean isPackageLoaded() {
        return currentPackage != null && searchIndexService.isIndexBuilt();
    }

    /**
     * Clears the current package from memory.
     */
    public synchronized void clearPackage() {
        currentPackage = null;
        lastMetrics = null;
        lastSearchMetrics = null;
        isCurrentPackageDefault = false;
        logger.info("Package cleared from memory");
    }

    /**
     * Ensures the package is loaded and refreshed before read-only endpoints respond.
     */
    public synchronized void refreshPackageIfModified() throws IOException {
        refreshPackageIfModifiedInternal();
    }

    private void ensurePackageLoaded() throws IOException {
        if (currentPackage != null) {
            return;
        }
        loadDefaultPackageIfAvailable();
    }

    private RefreshOutcome refreshPackageIfModifiedInternal() throws IOException {
        if (currentPackage == null) {
            long reloadStart = System.nanoTime();
            ensurePackageLoaded();
            long reloadMs = currentPackage == null ? 0 : nanosToMillis(System.nanoTime() - reloadStart);
            return new RefreshOutcome(0, reloadMs, currentPackage != null);
        }

        long changeStart = System.nanoTime();
        boolean modified = currentPackage.isSourceFileModified();
        long changeDetectionMs = nanosToMillis(System.nanoTime() - changeStart);

        if (!modified) {
            return new RefreshOutcome(changeDetectionMs, 0, false);
        }

        logger.info("Source file modified, reloading package from disk: " + currentPackage.getSourceFile().getAbsolutePath());
        long reloadStart = System.nanoTime();
        loadAndIndexPackage(currentPackage.getSourceFile(), currentPackage.getPackageName(), isCurrentPackageDefault);
        return new RefreshOutcome(changeDetectionMs, nanosToMillis(System.nanoTime() - reloadStart), true);
    }

    private void loadDefaultPackageIfAvailable() throws IOException {
        File defaultFile = resolveSeedFile();
        if (defaultFile.exists() && defaultFile.isFile()) {
            // Determine if this is truly the default or an override
            boolean isDefault = isDefaultDataRoot();
            logger.info("Configured dataRoot='" + dataRoot + "', seedFile='" + seedFileName + "', mode=" + (isDefault ? "DEFAULT" : "CUSTOM"));
            logger.info((isDefault ? "Loading default seed package from: " : "Loading custom seed package from: ") + defaultFile.getAbsolutePath());
            String packageName = isDefault ? "Default Seed Package" : "Custom Package";
            loadAndIndexPackage(defaultFile, packageName, isDefault);
        } else {
            logger.info("Default seed package not found at: " + defaultFile.getAbsolutePath());
        }
    }

    private boolean isDefaultDataRoot() {
        String normalizedDataRoot = (dataRoot == null || dataRoot.isBlank()) ? "./data" : dataRoot.trim();
        return normalizedDataRoot.equals("./data");
    }

    private File resolveSeedFile() {
        String root = dataRoot == null || dataRoot.isBlank() ? "./data" : dataRoot.trim();
        String fileName = seedFileName == null || seedFileName.isBlank() ? "loan-package-seed.txt" : seedFileName.trim();

        File rootFile = new File(root);
        if (rootFile.isFile() && rootFile.exists()) {
            logger.info("Found seed file as direct path: " + rootFile.getAbsolutePath());
            return rootFile;
        }

        File seedFile = new File(rootFile, fileName);
        logger.info("Using seed file path: " + seedFile.getAbsolutePath());
        if (seedFile.exists() && seedFile.isFile()) {
            logger.info("Found seed file in data directory: " + seedFile.getAbsolutePath());
            return seedFile;
        }

        logger.warning("Seed file not found at configured path: " + seedFile.getAbsolutePath());
        return seedFile;
    }

    private PerformanceMetrics loadAndIndexPackage(File file, String packageDisplayName, boolean isDefault) throws IOException {
        // Enforce memory limit: check file size before loading
        if (file.length() > MAX_PACKAGE_BYTES) {
            throw new IOException(String.format(
                "Package size (%d MB) exceeds maximum limit (%d MB). " +
                "Split into smaller files or increase MAX_PACKAGE_BYTES constant.",
                file.length() / 1024 / 1024,
                MAX_PACKAGE_BYTES / 1024 / 1024
            ));
        }

        long totalStartTime = System.currentTimeMillis();

        try {
            LoanPackageLoader.LoadResult loadResult = packageLoader.loadPackage(file, packageDisplayName);
            currentPackage = loadResult.loanPackage;
            isCurrentPackageDefault = isDefault;

            long indexStartTime = System.currentTimeMillis();
            searchIndexService.buildIndex(currentPackage);
            long indexEndTime = System.currentTimeMillis();

            long totalEndTime = System.currentTimeMillis();

            lastMetrics = new PerformanceMetrics(
                    loadResult.loadTimeMs,
                    indexEndTime - indexStartTime,
                    totalEndTime - totalStartTime,
                    loadResult.memoryUsedBytes,
                    currentPackage.getTotalPageCount(),
                    currentPackage.getTotalCharCount()
            );

            logger.info("Package loaded and indexed successfully: " + lastMetrics);
            return lastMetrics;

        } catch (IOException e) {
            logger.severe("Failed to load package: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Performance metrics for a load operation.
     *
     * This record tracks all important timing and memory information
     * from loading and indexing a package.
     */
    public static class PerformanceMetrics {
        public final long loadTimeMs;          // Time to read and parse file
        public final long indexTimeMs;         // Time to build search index
        public final long totalTimeMs;         // Total time (load + index)
        public final long memoryUsedBytes;     // Memory used by loading
        public final int totalPages;           // Total pages in package
        public final int totalCharacters;      // Total characters in package

        public PerformanceMetrics(long loadTimeMs, long indexTimeMs, long totalTimeMs,
                                 long memoryUsedBytes, int totalPages, int totalCharacters) {
            this.loadTimeMs = loadTimeMs;
            this.indexTimeMs = indexTimeMs;
            this.totalTimeMs = totalTimeMs;
            this.memoryUsedBytes = memoryUsedBytes;
            this.totalPages = totalPages;
            this.totalCharacters = totalCharacters;
        }

        @Override
        public String toString() {
            return String.format("Metrics{load=%dms, index=%dms, total=%dms, memory=%dMB, pages=%d, chars=%d}",
                    loadTimeMs, indexTimeMs, totalTimeMs, memoryUsedBytes / 1024 / 1024, totalPages, totalCharacters);
        }
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000;
    }
}
