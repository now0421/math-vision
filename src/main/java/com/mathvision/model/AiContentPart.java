package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class AiContentPart {

    @JsonProperty("type")
    private String type;

    @JsonProperty("text")
    private String text;

    @JsonProperty("mime_type")
    private String mimeType;

    @JsonProperty("data_base64")
    private String dataBase64;

    public AiContentPart() {}

    public static AiContentPart text(String text) {
        AiContentPart part = new AiContentPart();
        part.type = "text";
        part.text = text;
        return part;
    }

    public static AiContentPart image(String mimeType, String dataBase64) {
        AiContentPart part = new AiContentPart();
        part.type = "image";
        part.mimeType = mimeType;
        part.dataBase64 = dataBase64;
        return part;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getDataBase64() { return dataBase64; }
    public void setDataBase64(String dataBase64) { this.dataBase64 = dataBase64; }
}
