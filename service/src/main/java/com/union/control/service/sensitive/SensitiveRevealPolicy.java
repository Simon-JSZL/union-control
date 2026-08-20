package com.union.control.service.sensitive;

public final class SensitiveRevealPolicy {
    private final boolean enabled;

    public SensitiveRevealPolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
