package com.example.loanpackagesearch.service;

import com.example.loanpackagesearch.model.Document;
import com.example.loanpackagesearch.model.LoanPackage;
import com.example.loanpackagesearch.model.Page;
import com.example.loanpackagesearch.model.SearchResult;
import com.example.loanpackagesearch.model.Version;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Full-text search service backed by an in-memory Lucene index.
 *
 * This service is intentionally focused on indexing and searching only.
 * Package loading and file reloading are handled by {@link LoanPackageService}
 * so the search behavior stays stable and easy to reason about.
 */
@Service
public class SearchIndexService {
    private static final Logger logger = Logger.getLogger(SearchIndexService.class.getName());
    private static final String FIELD_PAGE_CONTENT = "pageContent";
    private static final String FIELD_DOCUMENT_ID = "documentId";
    private static final String FIELD_DOCUMENT_NAME = "documentName";
    private static final String FIELD_DOCUMENT_TYPE = "documentType";
    private static final String FIELD_VERSION_ID = "versionId";
    private static final String FIELD_VERSION_NAME = "versionName";
    private static final String FIELD_PAGE_NUMBER = "pageNumber";

    private static final int MAX_RESULTS = 1000;

    private static final int SNIPPET_LENGTH = 200;

    private final Analyzer analyzer = new StandardAnalyzer();
    private Directory index;
    private DirectoryReader reader;
    private LoanPackage currentPackage;
    private long indexBuildTimeMs;

    public record SearchStageMetrics(
            long queryPreparationMs,
            long luceneSearchMs,
            long snippetGenerationMs,
            long totalSearchMs,
            String queryMode,
            String normalizedQuery,
            int totalHits) {

        public static SearchStageMetrics empty(String queryString) {
            String normalized = queryString == null ? "" : queryString.trim();
            return new SearchStageMetrics(0, 0, 0, 0, "empty", normalized, 0);
        }
    }

    public record SearchExecution(List<SearchResult> results, SearchStageMetrics metrics) {}

    private record PreparedQuery(Query query, String normalizedQuery, String queryMode) {}

    /**
     * Builds or rebuilds the in-memory Lucene index for the given package.
     *
     * Design:
     * - Closes old reader and directory to prevent memory leaks
     * - Supports reload scenarios where index is rebuilt multiple times
     * - Memory bound: in-memory ByteBuffersDirectory suitable for packages up to ~500MB
     *
     * @param loanPackage the package to index
     * @throws IOException if Lucene indexing fails
     */
    public synchronized void buildIndex(LoanPackage loanPackage) throws IOException {
        long startTime = System.currentTimeMillis();

        // Close old reader if it exists
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }

        // Close old directory if it exists (important for reload scenarios to prevent memory leak)
        if (index != null) {
            try {
                index.close();
            } catch (IOException ignored) {
            }
        }

        index = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(index, config)) {
            for (Document modelDoc : loanPackage.getDocuments()) {
                for (Version version : modelDoc.getVersions()) {
                    for (Page page : version.getPages()) {
                        if (!page.hasContent()) {
                            continue;
                        }
                        org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();
                        luceneDoc.add(new TextField(FIELD_PAGE_CONTENT, page.getOcrText(), Field.Store.YES));
                        luceneDoc.add(new StringField(FIELD_DOCUMENT_ID, modelDoc.getDocumentId(), Field.Store.YES));
                        luceneDoc.add(new StringField(FIELD_DOCUMENT_NAME, modelDoc.getDocumentName(), Field.Store.YES));
                        luceneDoc.add(new StringField(FIELD_DOCUMENT_TYPE, modelDoc.getDocumentType(), Field.Store.YES));
                        luceneDoc.add(new StringField(FIELD_VERSION_ID, version.getVersionId(), Field.Store.YES));
                        luceneDoc.add(new StringField(FIELD_VERSION_NAME, version.getVersionName(), Field.Store.YES));
                        luceneDoc.add(new StringField(FIELD_PAGE_NUMBER, String.valueOf(page.getPageNumber()), Field.Store.YES));
                        writer.addDocument(luceneDoc);
                    }
                }
            }
        }

        reader = DirectoryReader.open(index);
        currentPackage = loanPackage;
        indexBuildTimeMs = System.currentTimeMillis() - startTime;
        logger.info("Index built successfully. Pages indexed: " + reader.numDocs() + ", Build time: " + indexBuildTimeMs + "ms");
    }

    /**
     * Searches the current in-memory index and returns detailed timing metadata.
     */
    public synchronized SearchExecution searchWithMetrics(String queryString) throws IOException {
        if (reader == null || currentPackage == null || queryString == null || queryString.trim().isEmpty()) {
            return new SearchExecution(Collections.emptyList(), SearchStageMetrics.empty(queryString));
        }

        String trimmedQuery = queryString.trim();
        long searchStart = System.nanoTime();

        PreparedQuery preparedQuery = prepareQuery(trimmedQuery);
        long afterPreparation = System.nanoTime();

        IndexSearcher searcher = new IndexSearcher(reader);
        TopDocs topDocs = searcher.search(preparedQuery.query(), MAX_RESULTS);
        long afterLucene = System.nanoTime();

        List<SearchResult> results = new ArrayList<>();
        long snippetNanos = 0L;
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            org.apache.lucene.document.Document luceneDoc = searcher.storedFields().document(scoreDoc.doc);
            long snippetStart = System.nanoTime();
            results.add(createSearchResult(luceneDoc, scoreDoc.score, trimmedQuery));
            snippetNanos += System.nanoTime() - snippetStart;
        }

        SearchStageMetrics metrics = new SearchStageMetrics(
                nanosToMillis(afterPreparation - searchStart),
                nanosToMillis(afterLucene - afterPreparation),
                nanosToMillis(snippetNanos),
                nanosToMillis(System.nanoTime() - searchStart),
                preparedQuery.queryMode(),
                preparedQuery.normalizedQuery(),
                topDocs.scoreDocs.length);

        logger.info("Search completed: " + results.size()
                + " results, mode=" + metrics.queryMode()
                + ", timings={prepare=" + metrics.queryPreparationMs()
                + "ms, lucene=" + metrics.luceneSearchMs()
                + "ms, snippet=" + metrics.snippetGenerationMs()
                + "ms, total=" + metrics.totalSearchMs() + "ms}");
        return new SearchExecution(results, metrics);
    }

    /**
     * Searches the current in-memory index using Lucene for both plain and advanced syntax.
     *
     * @param queryString raw user query
     * @return ranked search results, or an empty list when the index is unavailable
     * @throws IOException if Lucene parsing/search fails
     */
    public synchronized List<SearchResult> search(String queryString) throws IOException {
        return searchWithMetrics(queryString).results();
    }

    /**
     * Converts a raw user query into a Lucene query with predictable semantics.
     */
    private PreparedQuery prepareQuery(String trimmedQuery) throws IOException {
        try {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(new String[]{FIELD_PAGE_CONTENT}, analyzer);
            parser.setAllowLeadingWildcard(true);

            if (isLuceneSyntaxQuery(trimmedQuery)) {
                String normalized = sanitizeLuceneQuery(trimmedQuery);
                return new PreparedQuery(parser.parse(normalized), normalized, "lucene");
            }

            parser.setDefaultOperator(QueryParser.Operator.AND);
            String normalized = sanitizePlainQuery(trimmedQuery);
            return new PreparedQuery(parser.parse(normalized), normalized, "plain-and");
        } catch (ParseException e) {
            throw new IOException("Invalid search query: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a Lucene document into a display-ready search result.
     */
    private SearchResult createSearchResult(org.apache.lucene.document.Document luceneDoc, float score, String queryText) {
        return createSearchResult(
                luceneDoc.get(FIELD_DOCUMENT_ID),
                luceneDoc.get(FIELD_DOCUMENT_NAME),
                luceneDoc.get(FIELD_DOCUMENT_TYPE),
                luceneDoc.get(FIELD_VERSION_ID),
                luceneDoc.get(FIELD_VERSION_NAME),
                Integer.parseInt(luceneDoc.get(FIELD_PAGE_NUMBER)),
                luceneDoc.get(FIELD_PAGE_CONTENT),
                queryText,
                score);
    }

    /**
     * Creates a normalized search result from stored page metadata and content.
     */
    private SearchResult createSearchResult(String documentId, String documentName, String documentType,
                                            String versionId, String versionName, int pageNumber,
                                            String pageContent, String queryText, double score) {
        double normalizedScore = Math.min(1.0, score / 10.0d);
        return new SearchResult(
                documentId,
                documentName,
                documentType,
                versionId,
                versionName,
                pageNumber,
                createSnippet(pageContent, queryText),
                normalizedScore);
    }

    /**
     * Builds a compact display snippet centered on the query text when possible.
     */
    private String createSnippet(String pageContent, String queryText) {
        if (pageContent == null || pageContent.isEmpty()) {
            return "(No content available)";
        }

        String snippet = pageContent;
        if (queryText != null && !queryText.trim().isEmpty()) {
            String match = findBestSnippetMatch(pageContent, queryText.trim());
            if (match != null) {
                int matchIndex = pageContent.toLowerCase(Locale.ROOT).indexOf(match.toLowerCase(Locale.ROOT));
                int start = Math.max(0, matchIndex - 80);
                int end = Math.min(pageContent.length(), matchIndex + match.length() + 120);
                snippet = pageContent.substring(start, end);
                if (start > 0) {
                    snippet = "..." + snippet;
                }
                if (end < pageContent.length()) {
                    snippet = snippet + "...";
                }
            }
        }

        if (snippet.length() > SNIPPET_LENGTH) {
            snippet = snippet.substring(0, SNIPPET_LENGTH);
            int lastSpace = snippet.lastIndexOf(' ');
            if (lastSpace > 0) {
                snippet = snippet.substring(0, lastSpace);
            }
            snippet += "...";
        }

        return snippet.replaceAll("\\s+", " ").trim();
    }

    /**
     * Finds the best snippet anchor term for the provided query.
     */
    private String findBestSnippetMatch(String pageContent, String queryText) {
        List<String> terms = buildDisplayTerms(queryText);
        String lowerContent = pageContent.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lowerContent.contains(term.toLowerCase(Locale.ROOT))) {
                return term;
            }
        }
        return null;
    }

    /**
     * Extracts display-friendly terms from the raw query string.
     */
    private List<String> buildDisplayTerms(String queryText) {
        List<String> terms = new ArrayList<>();
        if (queryText == null || queryText.isBlank()) {
            return terms;
        }
        if (isLuceneSyntaxQuery(queryText)) {
            terms.addAll(extractQueryTerms(queryText));
        } else {
            terms.addAll(extractPlainTerms(queryText));
            if (terms.isEmpty()) {
                terms.add(queryText.trim());
            }
        }
        terms.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return terms;
    }

    /**
     * Splits a plain user query into searchable terms with default AND semantics.
     */
    private List<String> extractPlainTerms(String queryText) {
        List<String> terms = new ArrayList<>();
        if (queryText == null || queryText.isBlank()) {
            return terms;
        }

        for (String token : queryText.trim().split("\\s+")) {
            String cleaned = token.replaceAll("^[^\\p{L}\\p{Nd}]+|[^\\p{L}\\p{Nd}./-]+$", "");
            if (!cleaned.isBlank()) {
                terms.add(cleaned);
            }
        }
        return terms;
    }

    /**
     * Extracts literal terms and quoted phrases from Lucene-style queries.
     */
    private List<String> extractQueryTerms(String queryText) {
        List<String> terms = new ArrayList<>();
        java.util.regex.Matcher quoted = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(queryText);
        while (quoted.find()) {
            String phrase = quoted.group(1).trim();
            if (!phrase.isEmpty()) {
                terms.add(phrase);
            }
        }

        String withoutQuotes = queryText.replaceAll("\"[^\"]+\"", " ");
        for (String token : withoutQuotes.split("\\s+")) {
            String cleaned = token.replaceAll("^[^\\p{L}\\p{Nd}]+|[^\\p{L}\\p{Nd}*]+$", "").replaceAll("\\*+$", "");
            if (!cleaned.isBlank() && !cleaned.equalsIgnoreCase("AND") && !cleaned.equalsIgnoreCase("OR") && !cleaned.equalsIgnoreCase("NOT")) {
                terms.add(cleaned);
            }
        }

        terms.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return terms;
    }

    /**
     * Escapes OCR punctuation and field text so Lucene can safely parse
     * boolean/phrase/wildcard queries without rejecting ordinary content like slashes.
     */
    private String sanitizeLuceneQuery(String queryText) {
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"[^\"]+\"|\\(|\\)|[^\\s()]+")
                .matcher(queryText);

        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                tokens.add('"' + escapeLuceneTerm(token.substring(1, token.length() - 1)) + '"');
            } else if (isBooleanOperator(token) || "(".equals(token) || ")".equals(token)) {
                tokens.add(token);
            } else {
                tokens.add(escapeLuceneTerm(token));
            }
        }

        return String.join(" ", tokens);
    }

    /**
     * Escapes a plain query while preserving whitespace-delimited terms.
     */
    private String sanitizePlainQuery(String queryText) {
        List<String> terms = extractPlainTerms(queryText);
        if (terms.isEmpty()) {
            return escapeLuceneTerm(queryText.trim());
        }
        List<String> escapedTerms = new ArrayList<>(terms.size());
        for (String term : terms) {
            escapedTerms.add(escapeLuceneTerm(term));
        }
        return String.join(" ", escapedTerms);
    }

    /**
     * Escapes Lucene special characters while preserving wildcard search.
     */
    private String escapeLuceneTerm(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '*' || ch == '?') {
                escaped.append(ch);
            } else if (isLuceneSpecialCharacter(ch)) {
                escaped.append('\\').append(ch);
            } else {
                escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private boolean isBooleanOperator(String token) {
        return "AND".equalsIgnoreCase(token) || "OR".equalsIgnoreCase(token) || "NOT".equalsIgnoreCase(token);
    }

    private boolean isLuceneSpecialCharacter(char ch) {
        return ch == '+'
                || ch == '-'
                || ch == '!'
                || ch == '('
                || ch == ')'
                || ch == '{'
                || ch == '}'
                || ch == '['
                || ch == ']'
                || ch == '^'
                || ch == '"'
                || ch == '~'
                || ch == ':'
                || ch == '\\'
                || ch == '/';
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000;
    }

    /**
     * @return the most recent index build duration in milliseconds
     */
    public long getIndexBuildTimeMs() {
        return indexBuildTimeMs;
    }

    /**
     * @return true when both the reader and current package are available
     */
    public boolean isIndexBuilt() {
        return reader != null && currentPackage != null;
    }

    /**
     * @return the package currently indexed, or null if none has been loaded
     */
    public LoanPackage getCurrentPackage() {
        return currentPackage;
    }

    /**
     * Rebuilds the index if the underlying source file has changed.
     *
     * @param loanPackage the package to inspect
     * @return true when a rebuild occurred
     * @throws IOException if rebuilding fails
     */
    public synchronized boolean rebuildIfModified(LoanPackage loanPackage) throws IOException {
        if (loanPackage.isSourceFileModified()) {
            buildIndex(loanPackage);
            return true;
        }
        return false;
    }

    /**
     * Releases Lucene resources during shutdown.
     */
    @PreDestroy
    public synchronized void shutdown() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (index != null) {
                index.close();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Detects whether the query should be parsed as Lucene syntax.
     */
    private boolean isLuceneSyntaxQuery(String query) {
        return query.contains("\"")
                || query.contains("*")
                || query.contains("?")
                || query.contains("+")
                || query.contains(":")
                || query.contains("(")
                || query.contains(")")
                || query.matches("(?i).*(\\bAND\\b|\\bOR\\b|\\bNOT\\b).*");
    }
}


