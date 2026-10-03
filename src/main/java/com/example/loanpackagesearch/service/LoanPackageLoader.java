package com.example.loanpackagesearch.service;

import com.example.loanpackagesearch.model.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;

/**
 * Service responsible for loading and parsing loan package files.
 *
 * This service:
 * 1. Reads the seed OCR text file
 * 2. Parses the structure into Document → Version → Page hierarchy
 * 3. Splits pages by form-feed character (\f)
 * 4. Creates the LoanPackage data structure
 *
 * Design:
 * - Stateless service for thread-safety
 * - Tracks memory usage during loading
 * - Handles missing or empty OCR files gracefully
 * - Provides timing information for performance measurement
 */
@Service
public class LoanPackageLoader {
    private static final Logger logger = Logger.getLogger(LoanPackageLoader.class.getName());

    // Form-feed character used as page separator in OCR text
    private static final char PAGE_SEPARATOR = '\f';

    // Memory tracking constants
    private static final long MAX_MEMORY_PER_PACKAGE = 500 * 1024 * 1024;  // 500 MB per package

    /**
     * Loads a loan package from a file using a caller-provided display name.
     *
     * @param file the source file to load
     * @param displayName the name that should appear in the UI/tree
     * @return a fully constructed LoanPackage object
     * @throws IOException if file cannot be read
     */
    public LoadResult loadPackage(File file, String displayName) throws IOException {
        long startTime = System.currentTimeMillis();
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        logger.info("Loading loan package from: " + file.getAbsolutePath());

        try {
            // Read entire file content
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // Parse the content into documents
            Map<String, Document> documents = parseContent(content);

            // Create the loan package
            LoanPackage loanPackage = new LoanPackage(
                    "pkg-" + System.currentTimeMillis(),
                    displayName != null && !displayName.trim().isEmpty() ? displayName.trim() : file.getName(),
                    file,
                    documents
            );

            long endTime = System.currentTimeMillis();
            long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memoryUsed = memoryAfter - memoryBefore;

            // Validate memory usage
            if (memoryUsed > MAX_MEMORY_PER_PACKAGE) {
                logger.warning("Package memory usage (" + (memoryUsed / 1024 / 1024) +
                             " MB) exceeds recommended limit (" + (MAX_MEMORY_PER_PACKAGE / 1024 / 1024) + " MB)");
            }

            logger.info(String.format("Package loaded: %d documents, %d pages, %d characters, " +
                    "Time: %dms, Memory: %d MB",
                    loanPackage.getDocumentCount(),
                    loanPackage.getTotalPageCount(),
                    loanPackage.getTotalCharCount(),
                    endTime - startTime,
                    memoryUsed / 1024 / 1024));

            return new LoadResult(loanPackage, endTime - startTime, memoryUsed);

        } catch (Exception e) {
            logger.severe("Failed to load package: " + e.getMessage());
            throw new IOException("Failed to load loan package: " + e.getMessage(), e);
        }
    }

    /**
     * Parses the raw OCR text content into a hierarchical document structure.
     *
     * The parser uses page resets (Page 1 of N) as document/version boundaries and
     * derives a title from the first page of each group. Repeated groups with the
     * same title are surfaced as multiple versions under the same document type.
     *
     * @param content the raw OCR text content
     * @return map of document ID to Document objects
     */
    private Map<String, Document> parseContent(String content) {
        Map<String, DocumentBuilder> buildersByTitle = new LinkedHashMap<>();

        if (content == null || content.trim().isEmpty()) {
            logger.warning("Content is empty");
            return Collections.emptyMap();
        }

        List<String> rawPages = Arrays.stream(content.split(String.valueOf(PAGE_SEPARATOR), -1))
                .map(String::trim)
                .filter(page -> !page.isEmpty())
                .toList();
        logger.info("Found " + rawPages.size() + " non-empty OCR pages");

        List<PageGroup> groups = splitIntoGroups(rawPages);
        logger.info("Detected " + groups.size() + " logical document/version groups");

        for (PageGroup group : groups) {
            String documentTitle = extractDocumentTitle(group.firstPageText());
            String normalizedTitle = normalizeKey(documentTitle);
            DocumentBuilder builder = buildersByTitle.computeIfAbsent(normalizedTitle,
                    key -> new DocumentBuilder(documentTitle));

            String versionLabel = extractVersionLabel(group.firstPageText(), builder.getVersionCount() + 1);
            builder.addVersion(versionLabel, group.pages());
        }

        Map<String, Document> documents = new LinkedHashMap<>();
        int documentIndex = 1;
        for (DocumentBuilder builder : buildersByTitle.values()) {
            String documentId = String.format("doc-%03d", documentIndex++);
            documents.put(documentId, new Document(
                    documentId,
                    builder.documentName(),
                    builder.documentName(),
                    builder.toVersionsMap()
            ));
        }

        return documents;
    }

    private List<PageGroup> splitIntoGroups(List<String> rawPages) {
        List<PageGroup> groups = new ArrayList<>();
        List<Page> currentPages = new ArrayList<>();
        String currentFirstPageText = null;

        for (String rawPage : rawPages) {
            int markerPageNumber = extractPageMarkerNumber(rawPage);
            if (markerPageNumber == 1 && !currentPages.isEmpty()) {
                groups.add(new PageGroup(currentFirstPageText, List.copyOf(currentPages)));
                currentPages.clear();
                currentFirstPageText = null;
            }

            if (currentFirstPageText == null) {
                currentFirstPageText = rawPage;
            }

            int pageNumber = markerPageNumber > 0 ? markerPageNumber : currentPages.size() + 1;
            currentPages.add(new Page(pageNumber, rawPage));
        }

        if (!currentPages.isEmpty()) {
            groups.add(new PageGroup(currentFirstPageText, List.copyOf(currentPages)));
        }

        return groups;
    }

    private int extractPageMarkerNumber(String pageText) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Page\\s+(\\d+)\\s+of\\s+(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(pageText);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return -1;
    }

    private String extractDocumentTitle(String pageText) {
        List<String> lines = Arrays.stream(pageText.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        String best = null;
        int bestScore = Integer.MIN_VALUE;
        int upperBound = Math.min(lines.size(), 18);

        for (int i = 0; i < upperBound; i++) {
            String candidate = cleanTitleCandidate(lines.get(i));
            if (candidate.isEmpty()) {
                continue;
            }

            int score = scoreTitleCandidate(candidate, i);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null) {
            best = cleanTitleCandidate(lines.isEmpty() ? "Untitled Document" : lines.get(0));
        }

        return best.isEmpty() ? "Untitled Document" : best;
    }

    private String cleanTitleCandidate(String value) {
        String cleaned = value
                .replaceAll("Page\\s+\\d+\\s+of\\s+\\d+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    private int scoreTitleCandidate(String candidate, int lineIndex) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        int score = 0;

        if (candidate.length() >= 8 && candidate.length() <= 120) {
            score += 4;
        }
        if (candidate.matches("[A-Z0-9 '&,./()\\-:;]+")) {
            score += 3;
        }
        if (candidate.equals(candidate.toUpperCase(Locale.ROOT)) && candidate.matches(".*[A-Z].*")) {
            score += 2;
        }
        if (lineIndex <= 2) {
            score += 2;
        }
        if (lower.contains("application") || lower.contains("statement") || lower.contains("notice") ||
                lower.contains("summary") || lower.contains("disclosure") || lower.contains("verification") ||
                lower.contains("policy") || lower.contains("agreement") || lower.contains("report") ||
                lower.contains("terms") || lower.contains("letter") || lower.contains("form") ||
                lower.contains("closing") || lower.contains("insurance") || lower.contains("tax")) {
            score += 5;
        }
        if (lower.contains("section ") || lower.startsWith("section") || lower.startsWith("article ")) {
            score -= 4;
        }
        if (lower.contains("loan #") || lower.contains("loan no") || lower.contains("borrower") ||
                lower.contains("effective date") || lower.contains("application date") ||
                lower.contains("page ") || lower.contains("date ")) {
            score -= 3;
        }
        if (candidate.chars().filter(Character::isLetter).count() < 8) {
            score -= 6;
        }

        return score;
    }

    private String extractVersionLabel(String pageText, int versionIndex) {
        List<java.util.regex.Pattern> patterns = List.of(
                java.util.regex.Pattern.compile("\\bVersion\\s+[A-Za-z0-9./-]+", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bUAD Version\\s+[A-Za-z0-9./-]+", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bRev\\.\\s*[A-Za-z0-9./-]+", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bEdition\\s+[A-Za-z0-9./-]+", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bDoc\\.\\s*[A-Za-z0-9./-]+", java.util.regex.Pattern.CASE_INSENSITIVE)
        );

        for (String line : pageText.split("\\R")) {
            String cleaned = line.trim();
            for (java.util.regex.Pattern pattern : patterns) {
                java.util.regex.Matcher matcher = pattern.matcher(cleaned);
                if (matcher.find()) {
                    return matcher.group().replaceAll("\\s+", " ").trim();
                }
            }
        }

        return "Version " + versionIndex;
    }

    private String normalizeKey(String value) {
        return value == null ? "untitled-document" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private record PageGroup(String firstPageText, List<Page> pages) {}

    private static final class DocumentBuilder {
        private final String documentName;
        private final Map<String, Version> versions = new LinkedHashMap<>();
        private int versionCounter = 0;

        private DocumentBuilder(String documentName) {
            this.documentName = documentName;
        }

        private void addVersion(String versionName, List<Page> pages) {
            versionCounter++;
            String versionId = "v" + versionCounter;
            versions.put(versionId, new Version(versionId, versionName, pages));
        }

        private int getVersionCount() {
            return versions.size();
        }

        private String documentName() {
            return documentName;
        }

        private Map<String, Version> toVersionsMap() {
            return versions;
        }
    }

    /**
     * Inner class to hold load results including timing and memory information.
     */
    public static class LoadResult {
        public final LoanPackage loanPackage;
        public final long loadTimeMs;
        public final long memoryUsedBytes;

        public LoadResult(LoanPackage loanPackage, long loadTimeMs, long memoryUsedBytes) {
            this.loanPackage = loanPackage;
            this.loadTimeMs = loadTimeMs;
            this.memoryUsedBytes = memoryUsedBytes;
        }
    }
}

