package com.example.loanpackagesearch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "app.data-root=./nonexistent-test-data")
class LoanPackageSearchApplicationTests
{

    @Test
    void contextLoads() {
    }

}
