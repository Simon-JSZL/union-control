package com.union.control.service;

import java.io.Serializable;

/** Serializable HTTP result carried by non-stream Agent Dubbo methods. */
public final class AgentResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    private int status;
    private String body;

    public AgentResponse() {}

    public AgentResponse(int status, String body) {
        this.status = status;
        this.body = body;
    }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
