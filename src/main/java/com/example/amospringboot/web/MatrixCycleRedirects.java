// src/main/java/com/example/amospringboot/web/MatrixCycleRedirects.java
package com.example.amospringboot.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MatrixCycleRedirects {
    @GetMapping("/matrix/cycle/find")
    public String redirectFindToUi() {
        return "redirect:/matrix/cycle/find/ui";
    }
}

