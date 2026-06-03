package com.mathvision.model;

import java.util.ArrayList;
import java.util.List;

public class AiMessage {

    private String role;
    private List<AiContentPart> parts;

    public AiMessage() {
        this.parts = new ArrayList<>();
    }

    public AiMessage(String role, List<AiContentPart> parts) {
        this.role = role;
        this.parts = parts != null ? parts : new ArrayList<>();
    }

    public static AiMessage user(List<AiContentPart> parts) {
        return new AiMessage("user", parts);
    }

    public static AiMessage system(String text) {
        return new AiMessage("system", List.of(AiContentPart.text(text)));
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public List<AiContentPart> getParts() { return parts; }
    public void setParts(List<AiContentPart> parts) { this.parts = parts != null ? parts : new ArrayList<>(); }
}
