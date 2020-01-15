package org.zalando.fahrschein.datatype;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.time.ZonedDateTime;

public class SimpleZonedDateTimeModule extends SimpleModule {
    private static final long serialVersionUID = 1L;

    public SimpleZonedDateTimeModule() {
        super("SimpleZonedDateTimeModule", new Version(1, 0, 0, null, null, null));
        this.addDeserializer(ZonedDateTime.class, new SimpleZonedDateTimeDeserializer());
        this.addSerializer(new SimpleZonedDateTimeSerializer());
    }
}
