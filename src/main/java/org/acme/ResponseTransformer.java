package org.acme;

/**
 * Response transformer — transforms responses from backend services.
 *
 * <p>Functional interface for transforming API gateway responses.
 */
@FunctionalInterface
public interface ResponseTransformer {

    /**
     * Transform a response.
     *
     * @param response the original response
     * @param request the original request (for context)
     * @return the transformed response
     */
    ApiGateway.Response transform(ApiGateway.Response response, ApiGateway.Request request);
}
