package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/**
 * API Gateway — unified entry point with routing, rate limiting, and authentication.
 *
 * <p>The API Gateway pattern provides a single entry point for all client requests,
 * handling cross-cutting concerns like authentication, rate limiting, and request routing.
 * This is a core pattern in microservices architectures.
 *
 * <p>Martin Fowler: "An API gateway is an entry point for all the microservices.
 * It provides a unified interface to a set of microservices, acting as a proxy
 * to route requests to the appropriate service."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Request routing</b> — Pattern-based routing to backend handlers</li>
 *   <li><b>Authentication</b> — Pluggable authentication with role-based access</li>
 *   <li><b>Rate limiting</b> — Integrated rate limiting to protect backend services</li>
 *   <li><b>Request/Response types</b> — Immutable request and response records</li>
 *   <li><b>Metrics</b> — Request counting for monitoring</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * ApiGateway gateway = ApiGateway.builder()
 *     .route("/api/users/{id}", Method.GET, this::getUser)
 *     .route("/api/orders", Method.POST, this::createOrder)
 *     .authenticator(this::authenticate)
 *     .rateLimiter(RateLimiter.perSecond(100))
 *     .build();
 *
 * Response response = gateway.handle(Request.get("/api/users/123")).get();
 * }</pre>
 *
 * @see RateLimiter
 * @see LoadBalancer
 * @see ServiceRouter
 */
public final class ApiGateway {
    public enum Method { GET, POST, PUT, DELETE }
    public record Request(String id, Method method, String path, Map<String,String> headers, byte[] body, Instant timestamp) {
        public static Request get(String path) { return new Request(UUID.randomUUID().toString(), Method.GET, path, Map.of(), new byte[0], Instant.now()); }
        public static Request post(String path, byte[] body) { return new Request(UUID.randomUUID().toString(), Method.POST, path, Map.of(), body, Instant.now()); }
    }
    public record Response(int status, Map<String,String> headers, byte[] body, Instant timestamp, Duration duration) {
        public static Response ok(byte[] body) { return new Response(200, Map.of(), body, Instant.now(), Duration.ZERO); }
        public static Response ok(String body) { return ok(body.getBytes()); }
        public static Response created(String location) { return new Response(201, Map.of("Location", location), new byte[0], Instant.now(), Duration.ZERO); }
        public static Response notFound(String msg) { return new Response(404, Map.of(), msg.getBytes(), Instant.now(), Duration.ZERO); }
        public static Response tooManyRequests() { return new Response(429, Map.of(), "Rate limit exceeded".getBytes(), Instant.now(), Duration.ZERO); }
    }

    public sealed interface AuthResult permits AuthResult.Authenticated, AuthResult.Unauthenticated {
        record Authenticated(String principal, Set<String> roles) implements AuthResult {}
        record Unauthenticated(String reason) implements AuthResult {}
    }

    private final List<Route> routes;
    private final Function<Request, AuthResult> authenticator;
    private final RateLimiter rateLimiter;
    private final LongAdder requestsTotal = new LongAdder();

    private ApiGateway(List<Route> routes, Function<Request, AuthResult> authenticator, RateLimiter rateLimiter) {
        this.routes = routes; this.authenticator = authenticator; this.rateLimiter = rateLimiter;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<Route> routes = new ArrayList<>();
        private Function<Request, AuthResult> authenticator = r -> new AuthResult.Authenticated("anonymous", Set.of());
        private RateLimiter rateLimiter;

        public Builder route(String pattern, Method method, Function<Request, CompletableFuture<Response>> handler) {
            routes.add(new Route(pattern, method, handler)); return this;
        }
        public Builder authenticator(Function<Request, AuthResult> a) { this.authenticator = a; return this; }
        public Builder rateLimiter(RateLimiter r) { this.rateLimiter = r; return this; }
        public ApiGateway build() { return new ApiGateway(List.copyOf(routes), authenticator, rateLimiter); }
    }

    public CompletableFuture<Response> handle(Request request) {
        requestsTotal.increment();

        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            return CompletableFuture.completedFuture(Response.tooManyRequests());
        }

        if (authenticator.apply(request) instanceof AuthResult.Unauthenticated(var reason)) {
            return CompletableFuture.completedFuture(new Response(401, Map.of(), reason.getBytes(), Instant.now(), Duration.ZERO));
        }

        for (Route route : routes) {
            if (matches(route, request)) {
                Instant start = Instant.now();
                return route.handler().apply(request).thenApply(r ->
                        new Response(r.status(), r.headers(), r.body(), r.timestamp(), Duration.between(start, Instant.now())));
            }
        }
        return CompletableFuture.completedFuture(Response.notFound("No route for: " + request.path()));
    }

    private boolean matches(Route route, Request req) {
        if (route.method() != req.method()) return false;
        if (route.pattern().endsWith("/*")) return req.path().startsWith(route.pattern().substring(0, route.pattern().length() - 2));
        return route.pattern().equals(req.path());
    }

    private record Route(String pattern, Method method, Function<Request, CompletableFuture<Response>> handler) {}

    public record Stats(long requestsTotal) {}

    public Stats stats() {
        return new Stats(requestsTotal.sum());
    }
}
