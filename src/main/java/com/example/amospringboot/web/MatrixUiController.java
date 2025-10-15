package com.example.amospringboot.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/matrix")
public class MatrixUiController {

    /** GET /matrix (landing) */
    @GetMapping
    public String index() {
        return "matrix/index";
    }

    /** GET /matrix/analyze (server-rendered page, client-side fetch) */
    @GetMapping("/analyze")
    public String analyzePage() {
        return "matrix/analyze";
    }

    /** GET /matrix/cycle/find (server-rendered page, client-side fetch) */
    @GetMapping("/cycle/find")
    public String findCyclePage() {
        return "matrix/cycle-find";
    }
}
