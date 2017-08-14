package org.zalando.fahrschein.http.api;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class HeadersImplTest {
    @Test
    public void shouldBeCaseInsensitive() {
        final HeadersImpl headers = new HeadersImpl();
        headers.put("Content-Type", "text/plain");

        assertEquals(singletonList("text/plain"), headers.get("content-type"));

        assertEquals("text/plain", headers.getFirst("CONTENT-TYPE"));
        assertEquals("text/plain", headers.getFirst("Content-Type"));
        assertEquals("text/plain", headers.getFirst("content-type"));
    }

    @Test
    public void shouldReturnHeaderNames() {
        final HeadersImpl headers = new HeadersImpl();
        headers.put("Content-Type", "text/plain");
        headers.put("Content-Length", "123");
        assertEquals(new HashSet<>(asList("Content-Type", "Content-Length")), headers.headerNames());
    }

    @Test
    public void shouldReturnEmptySetOfHeadernames() {
        final HeadersImpl headers = new HeadersImpl();
        assertEquals(emptySet(), headers.headerNames());
    }

    @Test
    public void shouldReturnEmptyListForUnknownHeaders() {
        final HeadersImpl headers = new HeadersImpl();
        assertEquals(emptyList(), headers.get("Content-Type"));
    }

    @Test
    public void shouldCopyHeaders() {
        final HeadersImpl headers = new HeadersImpl();
        headers.put("Content-Type", "text/plain");
        headers.put("Content-Length", "123");

        final HeadersImpl copy = new HeadersImpl(headers, false);

        assertEquals("text/plain", copy.getFirst("Content-Type"));
        assertEquals(123L, copy.getContentLength());
        assertEquals(new HashSet<>(asList("Content-Type", "Content-Length")), copy.headerNames());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void readOnlyViewShouldNotSupportAdd() {

        final HeadersImpl readOnlyHeaders = new HeadersImpl(new HeadersImpl(), true);

        readOnlyHeaders.add("Content-Type", "application/json");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void readOnlyViewShouldNotSupportPut() {
        final HeadersImpl headers = new HeadersImpl();
        headers.put("Content-Type", "text/plain");
        headers.put("Content-Length", "123");

        final HeadersImpl readOnlyHeaders = new HeadersImpl(headers, true);

        assertEquals("text/plain", readOnlyHeaders.getFirst("Content-Type"));
        readOnlyHeaders.put("Content-Type", "application/json");
    }

    @Test
    public void originalHeadersShouldNotAffectReadonlyCopy() {
        final HeadersImpl headers = new HeadersImpl();
        headers.put("Accept", "text/plain");

        final HeadersImpl readOnlyHeaders = new HeadersImpl(headers, true);

        headers.add("Accept", "application/json");

        assertEquals(singletonList("text/plain"), readOnlyHeaders.get("Accept"));
    }


    @Test
    public void shouldGetContentType() {
        final HeadersImpl headers = new HeadersImpl();
        headers.put("Content-Type", "text/plain");

        final ContentType contentType = headers.getContentType();
        assertNotNull(contentType);
        assertEquals(ContentType.TEXT_PLAIN_VALUE, contentType.getValue());
        assertEquals(ContentType.TEXT_PLAIN, contentType);
    }

    @Test
    public void shouldSetContentType() {
        final HeadersImpl headers = new HeadersImpl();
        headers.setContentType(ContentType.APPLICATION_JSON);

        assertEquals("application/json", headers.getFirst("Content-Type"));
    }

    @Test
    public void shouldGetContentLength() {
        final HeadersImpl headers = new HeadersImpl();
        headers.put("Content-Length", "1000");

        assertEquals(1000L, headers.getContentLength());
    }

    @Test
    public void shouldSetContentLength() {
        final HeadersImpl headers = new HeadersImpl();
        headers.setContentLength(2000L);

        final String value = headers.getFirst("Content-Length");

        assertNotNull(value);
        assertEquals(2000L, Long.parseLong(value));
    }

    @Test
    public void shouldReturnMinusOneForUnknownContentLength() {
        final HeadersImpl headers = new HeadersImpl();

        assertEquals(-1L, headers.getContentLength());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void headerValuesShouldBeUnmodifiable() {
        final HeadersImpl headers = new HeadersImpl();
        headers.put("Accept", "text/plan");

        headers.get("Accept").add("application/json");
    }
}
