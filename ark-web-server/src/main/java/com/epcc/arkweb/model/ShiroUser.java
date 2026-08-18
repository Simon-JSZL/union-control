package com.epcc.arkweb.model;

import java.io.Serializable;

/**
 * Local source-compatible subset of production ShiroUser.
 * Production keeps its existing richer model.
 */
public class ShiroUser implements Serializable {
    private static final long serialVersionUID = 1L;
    private String loginName;
    private String orgCode;
    private String roleId;

    public String getLoginName() { return loginName; }
    public void setLoginName(String loginName) { this.loginName = loginName; }
    public String getOrgCode() { return orgCode; }
    public void setOrgCode(String orgCode) { this.orgCode = orgCode; }
    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }
}
