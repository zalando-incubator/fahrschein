package org.zalando.fahrschein;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.cfg.PackageVersion;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.zalando.fahrschein.datatype.SimpleZonedDateTimeModule;

class DefaultObjectMapper {
    static final ObjectMapper INSTANCE;
    private static final Version databindVersionCompatibleToJavaTimeModule = new Version(2, 6, 0, null,"com.fasterxml.jackson.core","jackson-databind");
    private static final Version databindVersionRuntime = PackageVersion.VERSION;

    static {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(snake_case());
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.registerModules(new ParameterNamesModule());
        if(databindVersionRuntime.compareTo(databindVersionCompatibleToJavaTimeModule) >= 0) {
            objectMapper.registerModules(new Jdk8Module(), new JavaTimeModule());
        } else {
            objectMapper.registerModules(new SimpleZonedDateTimeModule());
        }
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        INSTANCE = objectMapper;
    }

    @SuppressWarnings("deprecation")
    private static PropertyNamingStrategy snake_case() {
        // Use the deprecated constant instead of SNAKE_CASE to remain compatible with jackson versions < 2.7
        return PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES;
    }

    private DefaultObjectMapper() {

    }
}
