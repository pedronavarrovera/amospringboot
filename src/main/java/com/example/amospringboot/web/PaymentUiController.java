package com.example.amospringboot.web;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@Controller
@RequestMapping("/payment")
public class PaymentUiController {

    private final MatrixApiClient client;

    public PaymentUiController(MatrixApiClient client) {
        this.client = client;
    }

    /** GET /payment – render empty form (or keep your current logic if you had one) */
    @GetMapping
    public String page(Model model) {
        model.addAttribute("form", new Form());
        return "payment";
    }

    /** POST /payment – submit form, call FastAPI, render result */
    @PostMapping
    public String submit(@ModelAttribute("form") Form form, Model model) {
        // simple validation
        if (isBlank(form.getBlob_name()) || isBlank(form.getNode_a()) || isBlank(form.getNode_b()) || form.getAmount() == null) {
            model.addAttribute("error", "blob_name, node_a, node_b and amount are required");
            return "payment";
        }

        PaymentRequest req = new PaymentRequest();
        req.setBlob_name(form.getBlob_name());
        req.setNode_a(form.getNode_a());
        req.setNode_b(form.getNode_b());
        req.setAmount(form.getAmount());
        req.setOut_base(form.getOut_base());
        req.setContainer(form.getContainer());

        Map<String, Object> result = client.payment(req);
        model.addAttribute("result", result);
        return "payment";
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Form backing bean for server-rendered page */
    public static class Form {
        @NotBlank private String blob_name;
        @NotBlank private String node_a;
        @NotBlank private String node_b;
        @NotNull  private BigDecimal amount; // ex: 12.34
        private String out_base;             // optional
        private String container;            // optional

        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getNode_a() { return node_a; }
        public void setNode_a(String node_a) { this.node_a = node_a; }
        public String getNode_b() { return node_b; }
        public void setNode_b(String node_b) { this.node_b = node_b; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getOut_base() { return out_base; }
        public void setOut_base(String out_base) { this.out_base = out_base; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
    }
}
