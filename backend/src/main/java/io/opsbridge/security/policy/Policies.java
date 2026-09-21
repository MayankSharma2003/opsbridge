package io.opsbridge.security.policy;

// Role rules from spec 3, centralized here; must stay compile-time constants for @PreAuthorize.
public final class Policies {

    /** Any authenticated role: Viewer, Operator or Admin may read. */
    public static final String AUTHENTICATED = "isAuthenticated()";

    /** Incident mutations: Viewers are read-only. */
    public static final String OPERATOR_OR_ADMIN = "hasAnyRole('OPERATOR','ADMIN')";

    /** Service catalog management: Operators are explicitly excluded. */
    public static final String ADMIN = "hasRole('ADMIN')";

    private Policies() {
    }
}
