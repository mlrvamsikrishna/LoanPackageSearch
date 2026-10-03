package com.example.loanpackagesearch.service;

import com.example.loanpackagesearch.model.SearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanPackageServiceConcurrencyTest {

    @Test
    void concurrentSearchAndReloadRemainStableAndSeeUpdatedContent() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "loan-package-concurrency-" + System.nanoTime());
        assertTrue(tempDir.mkdirs(), "Temp data directory should be created");

        File tempFile = new File(tempDir, "loan-package-concurrency.txt");
        tempFile.deleteOnExit();
        writePackage(tempFile, "alphaunique", "Original package content for concurrent test.");

        LoanPackageService service = new LoanPackageService();
        ReflectionTestUtils.setField(service, "dataRoot", tempDir.getAbsolutePath());
        ReflectionTestUtils.setField(service, "seedFileName", tempFile.getName());
        ReflectionTestUtils.setField(service, "packageLoader", new LoanPackageLoader());
        ReflectionTestUtils.setField(service, "searchIndexService", new SearchIndexService());

        assertFalse(service.search("alphaunique").isEmpty(), "Initial content should be searchable");

        CountDownLatch startLatch = new CountDownLatch(1);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicBoolean sawUpdatedContent = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> searchFuture = executor.submit(() -> {
                await(startLatch);
                for (int i = 0; i < 30; i++) {
                    try {
                        List<SearchResult> results = service.search("betaunique");
                        if (!results.isEmpty()) {
                            sawUpdatedContent.set(true);
                        }
                        Thread.sleep(40);
                    } catch (Throwable t) {
                        failures.add(t);
                        return;
                    }
                }
            });

            Future<?> writeFuture = executor.submit(() -> {
                await(startLatch);
                try {
                    Thread.sleep(1100);
                    writePackage(tempFile, "betaunique", "Updated package content for concurrent test.");
                } catch (Throwable t) {
                    failures.add(t);
                }
            });

            startLatch.countDown();
            searchFuture.get(10, TimeUnit.SECONDS);
            writeFuture.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertTrue(failures.isEmpty(), "Concurrent search/reload should not throw exceptions: " + failures);
        assertTrue(sawUpdatedContent.get(), "Searches should eventually observe the updated package after reload");
        assertTrue(service.search("alphaunique").isEmpty(), "Old content should disappear after reload");
        assertFalse(service.search("betaunique").isEmpty(), "Updated content should remain searchable");
    }

    private static void writePackage(File file, String uniqueToken, String body) throws Exception {
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write("Loan Document Header\n");
            writer.write("Page 1 of 1\n");
            writer.write(uniqueToken + "\n");
            writer.write(body + "\n");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

