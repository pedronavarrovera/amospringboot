package com.example.amospringboot;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

/**
 * HomeController handles web requests and returns view names.
 * 
 * The views (home.html, payment.html) are rendered by Thymeleaf templates
 * under src/main/resources/templates/.
 */
@Controller
public class HomeController {

    /**
     * Handles requests to the root URL ("/").
     * If the user is authenticated, their username is added to the model.
     * Otherwise, "Anonymous" is set as the username.
     * 
     * @param model     Holds attributes for rendering in the Thymeleaf view
     * @param principal Represents the currently logged-in user (null if not logged in)
     * @return The name of the view template ("home.html")
     */
    @GetMapping("/")
    public String index(Model model, Principal principal) {
        if (principal != null) {
            model.addAttribute("username", principal.getName()); // Logged-in user
        } else {
            model.addAttribute("username", "Anonymous"); // Guest user
        }
        return "home"; // Renders home.html
    }

    /**
     * Handles requests to "/home".
     * Works the same as "/" but provides an explicit mapping.
     */
    @GetMapping("/home")
    public String home(Model model, Principal principal) {
        if (principal != null) {
            model.addAttribute("username", principal.getName());
        } else {
            model.addAttribute("username", "Anonymous");
        }
        return "home";
    }

    /**
     * Handles requests to "/payment".
     * Shows the payment page with the current username.
     * 
     * ⚠️ Access control should be configured in Spring Security
     * to restrict this page to authenticated/authorized users only.
     */
    @GetMapping("/payment")
    public String paymentPage(Model model, Principal principal) {
        if (principal != null) {
            model.addAttribute("username", principal.getName());
        } else {
            model.addAttribute("username", "Anonymous");
        }
        return "payment"; // Renders payment.html
    }
}
