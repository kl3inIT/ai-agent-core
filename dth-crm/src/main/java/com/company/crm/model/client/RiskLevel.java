package com.company.crm.model.client;

import com.company.crm.app.util.enums.EnumUtils;
import com.company.crm.model.base.DefaultStringEnumClass;
import org.springframework.lang.Nullable;

public enum RiskLevel implements DefaultStringEnumClass<RiskLevel> {

    HIGH,
    MEDIUM,
    LOW;

    @Nullable
    public static RiskLevel fromId(String id) {
        return EnumUtils.fromId(RiskLevel.class, id);
    }
}