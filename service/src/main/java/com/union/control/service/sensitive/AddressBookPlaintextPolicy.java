package com.union.control.service.sensitive;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Row-data policy for the AddressBook result's role property. */
public final class AddressBookPlaintextPolicy {
    private static final Pattern ROLE = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Set<String> plaintextRoles;

    public AddressBookPlaintextPolicy(String configuredRoles) {
        this.plaintextRoles = parse(configuredRoles);
    }

    public boolean allowPlaintext(Object row) {
        String role = roleOf(row);
        return role != null && plaintextRoles.contains(role.trim());
    }

    private static String roleOf(Object row) {
        if (row == null) return null;
        try {
            if (row instanceof Map<?, ?>) {
                Object value = ((Map<?, ?>) row).get("role");
                return value == null ? null : value.toString();
            }
            MetaObject metaObject = SystemMetaObject.forObject(row);
            if (!metaObject.hasGetter("role")) return null;
            Object value = metaObject.getValue("role");
            return value == null ? null : value.toString();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Set<String> parse(String configuredRoles) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (configuredRoles == null || configuredRoles.trim().isEmpty()) {
            return Collections.unmodifiableSet(roles);
        }
        for (String configuredRole : configuredRoles.split(",", -1)) {
            String role = configuredRole.trim();
            if (!ROLE.matcher(role).matches()) {
                throw new IllegalArgumentException("Invalid AddressBook plaintext role");
            }
            roles.add(role);
        }
        return Collections.unmodifiableSet(roles);
    }
}
