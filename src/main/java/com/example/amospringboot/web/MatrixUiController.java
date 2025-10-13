package com.example.amospringboot.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/matrix")
public class MatrixUiController {

    // Landing page: GET /matrix
    @GetMapping
    public String index(Model model) {
        return "matrix/index"; // looks for templates/matrix/index.html
    }

    // If you also want server-rendered pages for Analyze/Find,
    // we can add methods here later. For now, this fixes the /matrix 404.
}
