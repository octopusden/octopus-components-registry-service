package org.octopusden.octopus.components.registry.api.build.tools;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.octopusden.octopus.components.registry.api.beans.PTCProductToolBean;
import org.octopusden.octopus.components.registry.api.beans.PTDDbProductToolBean;
import org.octopusden.octopus.components.registry.api.beans.PTDProductToolBean;
import org.octopusden.octopus.components.registry.api.beans.PTKProductToolBean;
import org.octopusden.octopus.components.registry.api.beans.OdbcToolBean;
import org.octopusden.octopus.components.registry.api.beans.OracleDatabaseToolBean;
import org.octopusden.octopus.components.registry.api.enums.BuildToolTypes;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY;
import static com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME;

@JsonTypeInfo(use = NAME, include = PROPERTY)
@JsonSubTypes({
        @JsonSubTypes.Type(value = OracleDatabaseToolBean.class, name = "oracleDatabase"),
        @JsonSubTypes.Type(value = PTCProductToolBean.class, name = "cProduct"),
        @JsonSubTypes.Type(value = PTKProductToolBean.class, name = "kProduct"),
        @JsonSubTypes.Type(value = PTDProductToolBean.class, name = "dProduct"),
        @JsonSubTypes.Type(value = PTDDbProductToolBean.class, name = "dDbProduct"),
        @JsonSubTypes.Type(value = OdbcToolBean.class, name = "odbc")
})
public interface BuildTool {
    @JsonIgnore
    BuildToolTypes getBuildToolType();
}
