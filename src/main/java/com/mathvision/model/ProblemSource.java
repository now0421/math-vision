package com.mathvision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ProblemSource {

    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("raw_text")
    private String rawText;

    @JsonProperty("assets")
    private List<SourceAsset> assets = new ArrayList<>();

    public ProblemSource() {}

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public List<SourceAsset> getAssets() { return assets; }
    public void setAssets(List<SourceAsset> assets) { this.assets = assets != null ? assets : new ArrayList<>(); }
}
