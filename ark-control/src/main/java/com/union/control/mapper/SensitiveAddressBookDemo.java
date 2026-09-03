package com.union.control.mapper;

import com.nucc.channel.ark.common.annotation.EnDecryptField;

public class SensitiveAddressBookDemo {
    private Long id;
    private String name;
    private String role;
    @EnDecryptField
    private String email;
    @EnDecryptField
    private String telephone;
    @EnDecryptField
    private String mobileNumber;
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getTelephone() { return telephone; }
    public void setTelephone(String telephone) { this.telephone = telephone; }
    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
