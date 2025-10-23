package com.example.amospringboot.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MatrixHomeController {
    @GetMapping(value = "/matrix")
    public String matrixHome(Model model) {
        model.addAttribute("pageTitle", "Matrix");
        return "matrix/index";
    }
}
