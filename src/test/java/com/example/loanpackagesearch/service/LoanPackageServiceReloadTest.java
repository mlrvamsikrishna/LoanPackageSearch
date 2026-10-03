package com.example.loanpackagesearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoanPackageServiceReloadTest {

    @Test
    void searchReloadsEditedFileWithoutRestart() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "loan-package-reload-" + System.nanoTime());
        assertTrue(tempDir.mkdirs(), "Temp data directory should be created");

        File tempFile = new File(tempDir, "loan-package-reload.txt");
        tempFile.deleteOnExit();

        LoanPackageService service = new LoanPackageService();
        ReflectionTestUtils.setField(service, "dataRoot", tempDir.getAbsolutePath());
        ReflectionTestUtils.setField(service, "seedFileName", tempFile.getName());
        ReflectionTestUtils.setField(service, "packageLoader", new LoanPackageLoader());
        ReflectionTestUtils.setField(service, "searchIndexService", new SearchIndexService());

        try (FileWriter writer = new FileWriter(tempFile, false)) {
            writer.write("Form MTG-6480 · Rev. 1.0 · Effective 02/28/2023\n");
            writer.write("Page 1 of 1\n");
            writer.write("Initial version of the document.\n");
        }

        List<?> initialResults = service.search("Rev. 1.0");
        assertFalse(initialResults.isEmpty(), "Initial version should be searchable after startup load");

        Thread.sleep(1000);
        try (FileWriter writer = new FileWriter(tempFile, false)) {
            writer.write("Form MTG-6480 · Rev. 2.0 · Effective 02/28/2023\n");
            writer.write("Page 1 of 1\n");
            writer.write("Updated version of the document.\n");
        }

        List<?> updatedResults = service.search("Rev. 2.0");
        assertFalse(updatedResults.isEmpty(), "Edited version should be found after automatic reload");
        assertTrue(service.search("Rev. 1.0").isEmpty(), "Old version should no longer be returned after reload");
    }
}
