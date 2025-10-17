package com.example.amospringboot.web;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.CycleFindRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
@RequestMapping("/matrix")
public class MatrixUiController {

    private static final String CONTAINER = "matrices";
    private static final String FALLBACK  = "initial-matrix.b64";

    private final MatrixApiClient client;

    public MatrixUiController(MatrixApiClient client) {
        this.client = client;
    }

    @GetMapping
    public String index() {
        return "matrix/index";
    }

    // ===================== ANALYZE (authoritative fields) =====================

    /** Analyze form: prevent binding of authoritative fields to avoid tampering. */
    @InitBinder("form")
    public void disallowAnalyzeAuthoritative(WebDataBinder binder) {
        binder.setDisallowedFields("blob_name", "container");
    }

    /** GET /matrix/analyze – pre-populate with latest blob + 'matrices' (authoritative). */
    @GetMapping("/analyze")
    public String analyzePage(Model model) {
        String latest = safeLatest();

        AnalyzeForm form = new AnalyzeForm();
        form.setBlob_name(latest);
        form.setContainer(CONTAINER);

        model.addAttribute("form", form);
        return "matrix/analyze";
    }

    /** POST /matrix/analyze – reapply authoritative values and call API. */
    @PostMapping("/analyze")
    public String analyzeSubmit(@ModelAttribute("form") AnalyzeForm form, Model model) {
        // Re-enforce authoritative values regardless of client input
        String latest = safeLatest();
        form.setBlob_name(latest);
        form.setContainer(CONTAINER);

        Map<String, Object> result = client.analyze(form.getBlob_name(), form.getContainer());
        model.addAttribute("result", result);

        // Keep showing enforced values after submit
        model.addAttribute("form", form);
        return "matrix/analyze";
    }

    // ===================== CYCLE FIND (authoritative fields) =====================

    /** Only for cycle-find: prevent binding of authoritative fields (ignore tampering). */
    @InitBinder("cycleForm")
    public void disallowCycleAuthoritative(WebDataBinder binder) {
        // These must come from server: blob_name, out_base, container, node_a
        binder.setDisallowedFields("blob_name", "out_base", "container", "node_a");
    }

    // -------- Find Cycle (server-rendered) --------
    @GetMapping("/cycle/find")
    public String findCyclePage(Model model,
                                @AuthenticationPrincipal OidcUser oidcUser,
                                @AuthenticationPrincipal OAuth2User oauth2User) {
        String latest = safeLatest();
        String upn    = resolveUpn(oidcUser, oauth2User);
        String nodeA  = localPart(upn);

        CycleFindForm form = new CycleFindForm();
        form.setBlob_name(latest);      // authoritative
        form.setOut_base(latest);       // authoritative
        form.setContainer(CONTAINER);   // authoritative
        form.setNode_a(nodeA);          // authoritative (UPN local-part)

        model.addAttribute("cycleForm", form);
        return "matrix/cycle-find";
    }

    @PostMapping("/cycle/find")
    public String findCycleSubmit(@ModelAttribute("cycleForm") CycleFindForm form,
                                  Model model,
                                  @AuthenticationPrincipal OidcUser oidcUser,
                                  @AuthenticationPrincipal OAuth2User oauth2User) {
        // Re-enforce authoritative values regardless of client input
        String latest = safeLatest();
        String upn    = resolveUpn(oidcUser, oauth2User);
        String nodeA  = localPart(upn);

        form.setBlob_name(latest);
        form.setOut_base(latest);
        form.setContainer(CONTAINER);
        form.setNode_a(nodeA);

        // Validate required fields (node_b is user-entered)
        if (isBlank(form.getBlob_name()) || isBlank(form.getNode_a()) || isBlank(form.getNode_b())) {
            model.addAttribute("error", "blob_name, node_a, and node_b are required");
            return "matrix/cycle-find";
        }

        // Map to DTO and call API
        CycleFindRequest req = new CycleFindRequest();
        req.setBlob_name(form.getBlob_name());
        req.setNode_a(form.getNode_a());         // enforced (UPN local-part)
        req.setNode_b(form.getNode_b());         // user input
        req.setContainer(form.getContainer());   // enforced "matrices"
        req.setApply_settlement(form.getApply_settlement());
        req.setOut_base(form.getOut_base());     // enforced latest

        Map<String, Object> result = client.findCycle(req);
        model.addAttribute("result", result);

        // Keep showing the enforced values after submit
        model.addAttribute("cycleForm", form);
        return "matrix/cycle-find";
    }

    // ===================== helpers =====================

    private String safeLatest() {
        try {
            return client.latestBlob(CONTAINER, FALLBACK);
        } catch (Exception e) {
            return FALLBACK;
        }
    }

    private static String resolveUpn(OidcUser oidc, OAuth2User oauth2) {
        if (oidc != null) {
            String v = firstNonBlank(
                    oidc.getClaimAsString("upn"),
                    oidc.getClaimAsString("preferred_username"),
                    oidc.getEmail(),
                    oidc.getName()
            );
            if (v != null) return v;
        }
        if (oauth2 != null) {
            String v = firstNonBlank(
                    (String) oauth2.getAttributes().get("upn"),
                    (String) oauth2.getAttributes().get("preferred_username"),
                    (String) oauth2.getAttributes().get("email"),
                    oauth2.getName()
            );
            if (v != null) return v;
        }
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "unknown";
    }

    private static String localPart(String s) {
        if (s == null) return "unknown";
        int at = s.indexOf('@');
        return at > 0 ? s.substring(0, at) : s;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    // --- form objects ---
    public static class AnalyzeForm {
        @NotBlank(message = "blob_name is required")
        private String blob_name;   // authoritative
        private String container;   // authoritative
        public AnalyzeForm() {}
        public AnalyzeForm(String blob_name, String container) { this.blob_name = blob_name; this.container = container; }
        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
    }

    public static class CycleFindForm {
        @NotBlank private String blob_name;         // authoritative
        @NotBlank private String node_a;            // authoritative (UPN local-part)
        @NotBlank private String node_b;            // user-entered
        private String container;                   // authoritative
        private Boolean apply_settlement;           // user option
        private String out_base;                    // authoritative

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

