package com.union.control.sensitive.demo;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class SensitiveDemoRecord {
    private Long id;
    private String userId;
    private String content;
    private String createdAt;

    public SensitiveDemoRecord() {}

    public SensitiveDemoRecord(String userId, String content) {
        this.userId = userId;
        this.content = content;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @JsonIgnore
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
