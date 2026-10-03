package com.example.loanpackagesearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import java.util.logging.Logger;

/**
 * Main Spring Boot application class for Loan Package Search.
 *
 * This application provides a full-text search interface for mortgage loan packages.
 * It enables reviewers to quickly search and locate information across entire
 * loan application documents using advanced search capabilities.
 *
 * Startup:
 * 1. Initializes Spring Boot context
 * 2. Registers all components (controllers, services)
 * 3. Starts embedded Tomcat web server on port 8080
 * 4. Application ready at http://localhost:8080
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.example.loanpackagesearch")
public class LoanPackageSearchApplication {

    private static final Logger logger = Logger.getLogger(LoanPackageSearchApplication.class.getName());

    public static void main(String[] args) {
        logger.info("Starting Loan Package Search Application...");
        SpringApplication.run(LoanPackageSearchApplication.class, args);
        logger.info("Application started successfully!");
        logger.info("Access at: http://localhost:8080");
    }
}


