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
    private boolean isCurrentPackageDefault;  // Track if using default vs override

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
        ensurePackageLoaded();

        if (currentPackage == null) {
            logger.warning("No package loaded");
            return Collections.emptyList();
        }

        if (currentPackage.isSourceFileModified()) {
            logger.info("Source file modified, reloading package from disk: " + currentPackage.getSourceFile().getAbsolutePath());
            loadAndIndexPackage(currentPackage.getSourceFile(), currentPackage.getPackageName(), isCurrentPackageDefault);
        }

        // Execute search
        List<SearchResult> results = searchIndexService.search(queryString);

        // Results are already sorted by relevance score via SearchResult.compareTo()
        Collections.sort(results);

        return results;
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
        isCurrentPackageDefault = false;
        logger.info("Package cleared from memory");
    }

    private void ensurePackageLoaded() throws IOException {
        if (currentPackage != null) {
            return;
        }
        loadDefaultPackageIfAvailable();
    }

    private void loadDefaultPackageIfAvailable() throws IOException {
        File defaultFile = resolveSeedFile();
        if (defaultFile.exists() && defaultFile.isFile()) {
            logger.info("Loading default seed package from: " + defaultFile.getAbsolutePath());
            // Determine if this is truly the default or an override
            boolean isDefault = isDefaultDataRoot();
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
}
