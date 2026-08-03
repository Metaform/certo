package org.metaform.certo.management;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Scope checks for the management API, referenced from {@code @PreAuthorize} as {@code
 * @mgmtScopes.can(authentication, '&lt;resource&gt;:&lt;action&gt;')}. Scope strings follow the
 * EDC/IdentityHub convention {@code certo-mgmt-api:<resource>:<action>} (e.g. {@code
 * certo-mgmt-api:consumer:read}, {@code certo-mgmt-api:participant:write}). Broader grants match the
 * other components' logic: a {@code :write} scope includes the corresponding {@code :read}; the
 * action-wide {@code certo-mgmt-api:<action>} encompasses every {@code certo-mgmt-api:*:<action>}
 * scope (so {@code certo-mgmt-api:write} covers all writes — and, by write-includes-read, all
 * reads); and the blanket {@code certo-mgmt-api:admin} supersedes everything. Token scopes arrive
 * as {@code SCOPE_}-prefixed authorities via the default JWT authorities converter ({@code
 * scope}/{@code scp} claim).
 */
@Component("mgmtScopes")
public class ManagementScopeAuthorization {

    public static final String SCOPE_PREFIX = "certo-mgmt-api:";
    public static final String ADMIN_SCOPE = SCOPE_PREFIX + "admin";

    private static final String ADMIN_AUTHORITY = "SCOPE_" + ADMIN_SCOPE;

    public boolean can(Authentication authentication, String scope) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        var accepted = acceptedAuthorities(scope);
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> accepted.contains(granted.getAuthority()));
    }

    /** All authorities satisfying the required {@code <resource>:<action>} scope. */
    private static Set<String> acceptedAuthorities(String scope) {
        var separator = scope.lastIndexOf(':');
        var action = scope.substring(separator + 1);
        var accepted = new HashSet<String>();
        accepted.add(ADMIN_AUTHORITY);
        accepted.add("SCOPE_" + SCOPE_PREFIX + scope);
        accepted.add("SCOPE_" + SCOPE_PREFIX + action);
        if ("read".equals(action)) {
            // write includes read, both per resource and action-wide
            accepted.add("SCOPE_" + SCOPE_PREFIX + scope.substring(0, separator + 1) + "write");
            accepted.add("SCOPE_" + SCOPE_PREFIX + "write");
        }
        return accepted;
    }
}
