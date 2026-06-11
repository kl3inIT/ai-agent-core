package com.company.crm.security.role;

import com.company.crm.app.util.constant.CrmConstants;
import io.jmix.security.model.SecurityScope;
import io.jmix.security.role.annotation.ResourceRole;
import io.jmix.securityflowui.role.annotation.ViewPolicy;

@ResourceRole(name = "AnonymousRole", code = AnonymousRole.CODE, scope = SecurityScope.UI)
public interface AnonymousRole {
    String CODE = "anonymous-role";

    @ViewPolicy(viewIds = CrmConstants.ViewIds.USAGE_HELP)
    void screens();
}
