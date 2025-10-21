package com.example.amospringboot.matrix.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public class PaymentRequest {

    @NotBlank(message = "Blob name is required")
    private String blob_name;

    @NotBlank(message = "Node A is required")
    @Pattern(
        regexp = "^[A-Za-z0-9_\\-]{1,64}$",
        message = "Node must be 1–64 chars, letters/digits/_/- only"
    )
    private String node_a;

    @NotBlank(message = "Node B is required")
    @Pattern(
        regexp = "^[A-Za-z0-9_\\-]{1,64}$",
        message = "Node must be 1–64 chars, letters/digits/_/- only"
    )
    private String node_b;

    /** Strictly positive integer amount (no decimals). */
    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be greater than 0")
    @Digits(integer = 12, fraction = 0, message = "Amount must be an integer (no decimals)")
    private BigDecimal amount;

    @NotBlank(message = "Out base is required")
    private String out_base;

    @NotBlank(message = "Container is required")
    private String container;

    // Getters / Setters
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
