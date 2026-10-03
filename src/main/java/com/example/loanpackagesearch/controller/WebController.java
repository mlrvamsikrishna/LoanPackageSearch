package com.example.loanpackagesearch.controller;

import com.example.loanpackagesearch.model.LoanPackage;
import com.example.loanpackagesearch.service.LoanPackageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Web controller for serving the UI pages.
 *
 * Handles:
 * 1. Home/main page
 * 2. Passing data to templates
 * 3. Navigation between views
 */
@Controller
@RequestMapping("/")
public class WebController {

    @Autowired
    private LoanPackageService loanPackageService;

    /**
     * Serves the main search page.
     *
     * @param model the model to pass data to template
     * @return the template name
     */
    @GetMapping("/")
    public String index(Model model) {
        LoanPackage pkg = loanPackageService.getCurrentPackage();
        if (pkg != null) {
            model.addAttribute("packageLoaded", true);
            model.addAttribute("packageName", pkg.getPackageName());
            model.addAttribute("documentCount", pkg.getDocumentCount());
            model.addAttribute("pageCount", pkg.getTotalPageCount());
        }
        return "index";
    }
}

