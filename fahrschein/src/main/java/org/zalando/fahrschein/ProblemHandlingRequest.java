package org.zalando.fahrschein;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.zalando.fahrschein.domain.BatchItemResponse;
import org.zalando.fahrschein.http.api.ContentType;
import org.zalando.fahrschein.http.api.Headers;
import org.zalando.fahrschein.http.api.Request;
import org.zalando.fahrschein.http.api.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.zalando.fahrschein.http.api.ContentType.APPLICATION_JSON;
import static org.zalando.fahrschein.http.api.ContentType.APPLICATION_PROBLEM_JSON;


class ProblemHandlingRequest implements Request {

    private static final URI DEFAULT_PROBLEM_TYPE = URI.create("about:blank");

    private final Request request;
    private final ObjectMapper objectMapper;

    ProblemHandlingRequest(Request request) {
        this.request = request;
        this.objectMapper = DefaultObjectMapper.INSTANCE;
    }

    @Override
    public Response execute() throws IOException {
        final Response response = request.execute();

        try {
            final int statusCode = response.getStatusCode();
            if (statusCode == 207 || statusCode >= 400) {
                final String statusText = response.getStatusText();
                final Headers headers = response.getHeaders();
                final ContentType contentType = headers.getContentType();

                if (contentType == null || mightBeProblematic(contentType)) {

                    final JsonNode json = objectMapper.readTree(response.getBody());

                    if (isBatchItemResponse(json)) {
                        handleBatchItemResponse(json);
                    } else if (isAuthError(json)) {
                        handleAuthError(json, statusCode);
                    } else if (isProblem(json)) {
                        handleProblem(json, statusCode);
                    } else {
                        throw new IOProblem(DEFAULT_PROBLEM_TYPE, statusText, statusCode);
                    }
                } else {
                    throw new IOProblem(DEFAULT_PROBLEM_TYPE, statusText, statusCode);
                }
            }
        } catch (Throwable throwable) {
            try {
                response.close();
            } catch (Throwable suppressed) {
                throwable.addSuppressed(suppressed);
            }
            throw throwable;
        }

        return response;
    }

    private static boolean mightBeProblematic(final ContentType contentType) {
        final String type = contentType.getType();
        final String subtype = contentType.getSubtype();

        return APPLICATION_JSON.getType().equals(type) && APPLICATION_JSON.getSubtype().equals(subtype)
                || APPLICATION_PROBLEM_JSON.getType().equals(type) && APPLICATION_PROBLEM_JSON.getSubtype().equals(subtype);
    }

    private static boolean isProblem(final JsonNode json) {
        return json.has("title");
    }

    private static boolean isAuthError(final JsonNode json) {
        return json.has("error") && json.has("error_description");
    }

    private static boolean isBatchItemResponse(final JsonNode json) {
        return json.isArray() && json.size() > 0 && json.get(0).has("publishing_status");
    }

    private void handleProblem(final JsonNode rootNode, final int statusCode) throws IOException {
        final JsonNode typeNode = rootNode.get("type");
        final String type = typeNode == null ? "about:blank" : typeNode.asText();

        final String title = rootNode.get("title").asText();

        final JsonNode detailNode = rootNode.get("detail");
        final String detail = detailNode == null ? null : detailNode.asText(null);

        final JsonNode instanceNode = rootNode.get("instance");
        final String instance = instanceNode == null ? null : instanceNode.asText(null);

        throw new IOProblem(URI.create(type), title, statusCode, detail, instance == null ? null : URI.create(instance));
    }

    private void handleAuthError(final JsonNode rootNode, final int statusCode) throws IOProblem {
        final String error = rootNode.get("error").asText();
        final String description = rootNode.get("error_description").asText();

        throw new IOProblem(DEFAULT_PROBLEM_TYPE, error, statusCode, description);
    }

    private void handleBatchItemResponse(JsonNode rootNode) throws IOException {
        final BatchItemResponse[] responses = objectMapper.treeToValue(rootNode, BatchItemResponse[].class);
        final List<BatchItemResponse> failed = new ArrayList<>(responses.length);
        for (BatchItemResponse batchItemResponse : responses) {
            if (batchItemResponse.getPublishingStatus() != BatchItemResponse.PublishingStatus.SUBMITTED) {
                failed.add(batchItemResponse);
            }
        }
        if (!failed.isEmpty()) {
            // TODO: attach corresponding events?
            throw new EventPublishingException(failed.toArray(new BatchItemResponse[failed.size()]));
        }
    }

    @Override
    public String getMethod() {
        return request.getMethod();
    }

    @Override
    public URI getURI() {
        return request.getURI();
    }

    @Override
    public Headers getHeaders() {
        return request.getHeaders();
    }

    @Override
    public OutputStream getBody() throws IOException {
        return request.getBody();
    }

}
