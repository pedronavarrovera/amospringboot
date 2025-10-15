package com.example.amospringboot.web;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.CycleFindRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequestMapping("/matrix")
public class MatrixUiController {

    private final MatrixApiClient client;

    public MatrixUiController(MatrixApiClient client) {
        this.client = client;
    }

    @GetMapping
    public String index() {
        return "matrix/index";
    }

    // -------- Analyze (server-rendered) --------
    @GetMapping("/analyze")
    public String analyzePage(Model model,
                              @RequestParam(value = "blob_name", required = false) String blobName,
                              @RequestParam(value = "container", required = false) String container) {
        model.addAttribute("form", new AnalyzeForm(blobName, container));
        return "matrix/analyze";
    }

    @PostMapping("/analyze")
    public String analyzeSubmit(@ModelAttribute("form") AnalyzeForm form, Model model) {
        if (form.getBlob_name() == null || form.getBlob_name().isBlank()) {
            model.addAttribute("error", "blob_name is required");
            return "matrix/analyze";
        }
        Map<String, Object> result = client.analyze(form.getBlob_name(), form.getContainer());
        model.addAttribute("result", result);
        return "matrix/analyze";
    }

    // -------- Find Cycle (server-rendered) --------
    @GetMapping("/cycle/find")
    public String findCyclePage(Model model) {
        model.addAttribute("form", new CycleFindForm());
        return "matrix/cycle-find";
    }

    @PostMapping("/cycle/find")
    public String findCycleSubmit(@ModelAttribute("form") CycleFindForm form, Model model) {
        if (isBlank(form.getBlob_name()) || isBlank(form.getNode_a()) || isBlank(form.getNode_b())) {
            model.addAttribute("error", "blob_name, node_a, and node_b are required");
            return "matrix/cycle-find";
        }
        var req = new CycleFindRequest();
        req.setBlob_name(form.getBlob_name());
        req.setNode_a(form.getNode_a());
        req.setNode_b(form.getNode_b());
        req.setContainer(form.getContainer());
        req.setApply_settlement(form.getApply_settlement());
        req.setOut_base(form.getOut_base());

        Map<String, Object> result = client.findCycle(req);
        model.addAttribute("result", result);
        return "matrix/cycle-find";
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    // --- form objects ---
    public static class AnalyzeForm {
        @NotBlank(message = "blob_name is required")
        private String blob_name;
        private String container;
        public AnalyzeForm() {}
        public AnalyzeForm(String blob_name, String container) { this.blob_name = blob_name; this.container = container; }
        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
    }

    public static class CycleFindForm {
        @NotBlank private String blob_name;
        @NotBlank private String node_a;
        @NotBlank private String node_b;
        private String container;
        private Boolean apply_settlement;
        private String out_base;

        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getNode_a() { return node_a; }
        public void setNode_a(String node_a) { this.node_a = node_a; }
        public String getNode_b() { return node_b; }
        public void setNode_b(String node_b) { this.node_b = node_b; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
        public Boolean getApply_settlement() { return apply_settlement; }
        public void setApply_settlement(Boolean apply_settlement) { this.apply_settlement = apply_settlement; }
        public String getOut_base() { return out_base; }
        public void setOut_base(String out_base) { this.out_base = out_base; }
    }
}
