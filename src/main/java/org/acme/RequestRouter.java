package org.acme;

import java.util.concurrent.CompletableFuture;

/**
 * Request router — routes requests to backend services.
 *
 * <p>Functional interface for routing API gateway requests to backend services.
 */
@FunctionalInterface
public interface RequestRouter {

    /**
     * Route a request to a target service.
     *
     * @param request the incoming request
     * @param targetService the target service name
     * @return future completing with the response
     */
    CompletableFuture<ApiGateway.Response> route(ApiGateway.Request request, String targetService);
}
