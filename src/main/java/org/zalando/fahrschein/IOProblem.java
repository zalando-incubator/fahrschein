package org.zalando.fahrschein;

import org.springframework.http.HttpStatus;
import org.zalando.problem.Problem;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import javax.annotation.Nullable;
import javax.ws.rs.core.Response;

@SuppressWarnings("serial")
public class IOProblem extends IOException implements Problem {
    private final URI type;
    private final String title;
    private final Response.StatusType status;
    @Nullable
    private final String detail;
    @Nullable
    private final URI instance;
    public IOProblem(final URI type, final String title, final Response.StatusType status, @Nullable final String detail, @Nullable final URI instance) {
        super(formatMessage(type, title, status.getStatusCode(), detail));
        this.type = type;
        this.title = title;
        this.status = status;
        this.detail = detail;
        this.instance = instance;
    }

    public IOProblem(final URI type, final String title, final Response.StatusType status, @Nullable final String detail) {
        this(type, title, status, detail, null);
    }

    public IOProblem(final URI type, final String title, final Response.StatusType status) {
        this(type, title, status, null, null);
    }

    private static String formatMessage(final URI type, final String title, final int status, @Nullable final String detail) {
        return String.format("Problem [%s] with status [%d]: [%s] [%s]", type, status, title, detail == null ? "" : detail);
    }

    @Override
    public URI getType() {
        return type;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public Response.StatusType getStatus() {
        return status;
    }

    @Override
    public Optional<String> getDetail() {
        return Optional.ofNullable(detail);
    }

    @Override
    public Optional<URI> getInstance() {
        return Optional.ofNullable(instance);
    }

    static final class Status implements Response.StatusType {
        private final HttpStatus httpStatus;
        private final String reasonPhrase;

        Status(final HttpStatus httpStatus, final String reasonPhrase) {
            this.httpStatus = httpStatus;
            this.reasonPhrase = reasonPhrase;
        }

        @Override
        public int getStatusCode() {
            return httpStatus.value();
        }

        @Override
        public Response.Status.Family getFamily() {
            return Response.Status.Family.familyOf(httpStatus.value());
        }

        @Override
        public String getReasonPhrase() {
            return reasonPhrase;
        }
    }

}
