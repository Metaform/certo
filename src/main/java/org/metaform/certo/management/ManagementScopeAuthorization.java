package org.metaform.certo.management;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Scope checks for the management API, referenced from {@code @PreAuthorize} as {@code
 * @mgmtScopes.can(authentication, '&lt;resource&gt;:&lt;action&gt;')}. Scope strings follow the
 * EDC/IdentityHub convention {@code certo-mgmt-api:<resource>:<action>} (e.g. {@code
 * certo-mgmt-api:consumer:read}, {@code certo-mgmt-api:participant:write}). Two broader grants exist,
 * as in the other components: the action-wide {@code certo-mgmt-api:<action>} encompasses every
 * {@code certo-mgmt-api:*:<action>} scope (so {@code certo-mgmt-api:read} covers all reads), and the
 * blanket {@code certo-mgmt-api:admin} supersedes everything. Token scopes arrive as {@code
 * SCOPE_}-prefixed authorities via the default JWT authorities converter ({@code scope}/{@code scp}
 * claim).
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
        var required = "SCOPE_" + SCOPE_PREFIX + scope;
        var action = scope.substring(scope.lastIndexOf(':') + 1);
        var actionWide = "SCOPE_" + SCOPE_PREFIX + action;
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> {
                    var authority = granted.getAuthority();
                    return ADMIN_AUTHORITY.equals(authority) || required.equals(authority) || actionWide.equals(authority);
                });
    }
}
