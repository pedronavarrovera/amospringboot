package com.example.amospringboot.matrix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class PaymentRequest {
    @NotBlank
    private String blob_name;

    @NotBlank
    private String node_a;

    @NotBlank
    private String node_b;

    @NotNull
    private BigDecimal amount;

    // optional
    private String out_base;
    private String container;

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
