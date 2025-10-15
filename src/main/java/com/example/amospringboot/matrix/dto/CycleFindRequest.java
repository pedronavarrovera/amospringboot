package com.example.amospringboot.matrix.dto;

import jakarta.validation.constraints.NotBlank;

public class CycleFindRequest {
    @NotBlank
    private String blob_name;
    @NotBlank
    private String node_a;
    @NotBlank
    private String node_b;

    private String container;
    private Boolean apply_settlement; // optional
    private String out_base;          // optional

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
