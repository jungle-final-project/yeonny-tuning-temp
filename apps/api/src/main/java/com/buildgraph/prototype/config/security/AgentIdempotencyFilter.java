package com.buildgraph.prototype.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

class AgentIdempotencyFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AgentIdempotencyKeyExtractor keyExtractor;
    private final AgentIdempotencyService idempotencyService;
    private final SecurityErrorResponseWriter errorResponseWriter;

    AgentIdempotencyFilter(
            AgentIdempotencyKeyExtractor keyExtractor,
            AgentIdempotencyService idempotencyService,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        this.keyExtractor = keyExtractor;
        this.idempotencyService = idempotencyService;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/api/agent/")
                || !MUTATION_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        AgentPrincipal principal = agentPrincipal();
        if (principal == null) {
            filterChain.doFilter(request, response);
            return;
        }

        AgentIdempotencyKeyExtractor.AgentIdempotencyKey key = keyExtractor.extract(request);
        if (key.status() == AgentIdempotencyKeyExtractor.AgentIdempotencyKey.Status.MISSING) {
            writeBadRequest(response, "Idempotency-Key header is required.");
            return;
        }
        if (key.status() == AgentIdempotencyKeyExtractor.AgentIdempotencyKey.Status.INVALID) {
            writeBadRequest(response, "Idempotency-Key header is invalid.");
            return;
        }

        boolean multipart = isMultipart(request);
        HttpServletRequest downstreamRequest = request;
        String requestHash;
        if (multipart) {
            requestHash = multipartRequestHash(request);
        } else {
            CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
            downstreamRequest = cachedRequest;
            requestHash = sha256Hex(cachedRequest.body());
        }
        AgentIdempotencyDecision decision = idempotencyService.reserve(
                principal,
                request.getMethod(),
                path(request),
                key.value(),
                requestHash
        );

        if (decision.status() == AgentIdempotencyDecision.Status.CONFLICT) {
            writeConflict(response, "Idempotency-Key was already used with a different request.");
            return;
        }
        if (decision.status() == AgentIdempotencyDecision.Status.IN_PROGRESS) {
            writeConflict(response, "Idempotent request is already processing.");
            return;
        }
        if (decision.status() == AgentIdempotencyDecision.Status.REPLAY) {
            replay(response, decision);
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(downstreamRequest, responseWrapper);
        String responseBody = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        idempotencyService.complete(
                decision.recordId(),
                responseWrapper.getStatus(),
                responseBody,
                responseWrapper.getContentType()
        );
        responseWrapper.copyBodyToResponse();
    }

    private AgentPrincipal agentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AgentPrincipal principal)) {
            return null;
        }
        return principal;
    }

    private void replay(HttpServletResponse response, AgentIdempotencyDecision decision) throws IOException {
        response.setStatus(decision.responseStatus() == null ? HttpStatus.OK.value() : decision.responseStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(decision.responseContentType() == null
                ? MediaType.APPLICATION_JSON_VALUE
                : decision.responseContentType());
        if (decision.responseBody() != null) {
            response.getWriter().write(decision.responseBody());
        }
    }

    private void writeBadRequest(HttpServletResponse response, String message) throws IOException {
        errorResponseWriter.write(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private void writeConflict(HttpServletResponse response, String message) throws IOException {
        errorResponseWriter.write(response, HttpStatus.CONFLICT, "CONFLICT_STATE", message);
    }

    private static String path(HttpServletRequest request) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        return path == null ? request.getRequestURI() : path;
    }

    private static boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    private static String multipartRequestHash(HttpServletRequest request) throws IOException, ServletException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, request.getMethod());
            update(digest, path(request));
            update(digest, request.getContentType());
            request.getParts().stream()
                    .sorted(Comparator
                            .comparing(Part::getName)
                            .thenComparing(part -> part.getSubmittedFileName() == null ? "" : part.getSubmittedFileName()))
                    .forEach(part -> {
                        update(digest, part.getName());
                        update(digest, part.getSubmittedFileName());
                        update(digest, part.getContentType());
                        update(digest, Long.toString(part.getSize()));
                    });
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String hex(byte[] digest) {
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
