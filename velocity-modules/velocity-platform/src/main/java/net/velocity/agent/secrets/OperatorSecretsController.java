package net.velocity.agent.secrets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import net.velocity.agent.http.LocalAccountScope;
import net.velocity.agent.http.OperatorDataRequests;

import jakarta.servlet.http.HttpServletRequest;

/** Protected local management only. There is intentionally no plaintext credential read endpoint. */
@RestController
@RequestMapping("/api/operator/secrets")
public final class OperatorSecretsController {

    private static final Set<String> CREATE = Set.of(
            "request_id",
            "service",
            "account_label",
            "credential_origins",
            "post_login_origins",
            "username",
            "password",
            "application_origins",
            "callback_urls",
            "resource_origins");
    private static final Set<String> CREATE_REQUIRED = Set.of(
            "request_id",
            "service",
            "account_label",
            "credential_origins",
            "post_login_origins",
            "username",
            "password");
    private static final Set<String> EDIT = Set.of(
            "request_id",
            "expected_revision",
            "service",
            "account_label",
            "credential_origins",
            "post_login_origins",
            "username",
            "password",
            "application_origins",
            "callback_urls",
            "resource_origins");
    private static final Set<String> EDIT_REQUIRED = Set.of(
            "request_id",
            "expected_revision",
            "service",
            "account_label",
            "credential_origins",
            "post_login_origins");
    private static final Set<String> TRANSITION = Set.of("request_id", "expected_revision");
    private static final Set<String> STATE = Set.of("request_id", "expected_revision", "enabled");
    private final CredentialService service;
    private final LocalAccountScope localAccount;

    public OperatorSecretsController(CredentialService service, LocalAccountScope localAccount) {
        this.service = service;
        this.localAccount = localAccount;
    }

    @GetMapping
    ResponseEntity<Map<String, Object>> list(
            @RequestParam Map<String, String> parameters,
            HttpServletRequest request) {
        var scope = localAccount.scope();
        if (!Set.of("search").containsAll(parameters.keySet())) {
            throw new IllegalArgumentException();
        }
        OperatorSecretsRequests.noBody(request);
        var availability = service.availability();
        var credentials = availability.enabled()
                ? service.list(scope, parameters.get("search")).stream()
                        .map(OperatorSecretsController::metadata).toList()
                : List.of();
        return response(
                200,
                Map.of(
                        "status",
                        availability.available() ? "ok" : availability.enabled() ? "unavailable" : "disabled",
                        "availability",
                        availability,
                        "credentials",
                        credentials));
    }

    @GetMapping("/{id}")
    ResponseEntity<Map<String, Object>> detail(
            @PathVariable String id,
            @RequestParam Map<String, String> parameters,
            HttpServletRequest request) {
        var scope = localAccount.scope();
        OperatorDataRequests.noParameters(parameters);
        OperatorSecretsRequests.noBody(request);
        return response(200, Map.of("status", "ok", "credential", metadata(service.metadata(scope, id))));
    }

    @GetMapping("/operations/{requestId}")
    ResponseEntity<Map<String, Object>> operation(
            @PathVariable String requestId,
            @RequestParam Map<String, String> parameters,
            HttpServletRequest request) {
        var scope = localAccount.scope();
        OperatorDataRequests.noParameters(parameters);
        OperatorSecretsRequests.noBody(request);
        var receipt = service.operation(scope, requestId);
        if (receipt == null) {
            return response(200, Map.of("status", "not_found", "request_id", requestId));
        }
        return response(
                200,
                Map.of(
                        "status",
                        "committed",
                        "request_id",
                        requestId,
                        "credential_id",
                        receipt.credentialId(),
                        "revision",
                        receipt.revision(),
                        "outcome",
                        receipt.outcome(),
                        "database_committed",
                        true));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> create(
            HttpServletRequest request,
            @RequestParam Map<String, String> parameters) {
        var scope = localAccount.scope();
        try (var input = OperatorSecretsRequests.read(request, parameters, CREATE, CREATE_REQUIRED)) {
            return mutation(
                    service.create(
                            scope,
                            input.string("request_id"),
                            input.string("service"),
                            input.string("account_label"),
                            input.origins("credential_origins"),
                            input.origins("post_login_origins"),
                            emptyIfMissing(input.optionalOrigins("application_origins")),
                            emptyIfMissing(input.optionalOrigins("callback_urls")),
                            emptyIfMissing(input.optionalOrigins("resource_origins")),
                            input.string("username"),
                            input.string("password")));
        }
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> update(
            @PathVariable String id,
            HttpServletRequest request,
            @RequestParam Map<String, String> parameters) {
        var scope = localAccount.scope();
        try (var input = OperatorSecretsRequests.read(request, parameters, EDIT, EDIT_REQUIRED)) {
            return mutation(
                    service.update(
                            scope,
                            id,
                            input.revision(),
                            input.string("request_id"),
                            input.string("service"),
                            input.string("account_label"),
                            input.origins("credential_origins"),
                            input.origins("post_login_origins"),
                            input.optionalOrigins("application_origins"),
                            input.optionalOrigins("callback_urls"),
                            input.optionalOrigins("resource_origins"),
                            input.string("username"),
                            input.string("password")));
        }
    }

    @PostMapping(value = "/{id}/state", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> state(
            @PathVariable String id,
            HttpServletRequest request,
            @RequestParam Map<String, String> parameters) {
        var scope = localAccount.scope();
        try (var input = OperatorSecretsRequests.read(request, parameters, STATE, STATE)) {
            return mutation(
                    service.state(scope, id, input.revision(), input.string("request_id"), input.enabled()));
        }
    }

    @DeleteMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> delete(
            @PathVariable String id,
            HttpServletRequest request,
            @RequestParam Map<String, String> parameters) {
        var scope = localAccount.scope();
        try (var input = OperatorSecretsRequests.read(request, parameters, TRANSITION, TRANSITION)) {
            return mutation(service.delete(scope, id, input.revision(), input.string("request_id")));
        }
    }

    private static Map<String, Object> metadata(CredentialService.Metadata value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("credential_id", value.credentialId());
        result.put("revision", value.revision());
        result.put("service", value.service());
        result.put("account_label", value.accountLabel());
        result.put("credential_origins", value.credentialOrigins());
        result.put("post_login_origins", value.postLoginOrigins());
        result.put("application_origins", value.applicationOrigins());
        result.put("callback_urls", value.callbackUrls());
        result.put("resource_origins", value.resourceOrigins());
        result.put("enabled", value.enabled());
        result.put("deleted", value.deleted());
        result.put("created_at", value.createdAt().toString());
        result.put("updated_at", value.updatedAt().toString());
        return result;
    }

    private static List<String> emptyIfMissing(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static ResponseEntity<Map<String, Object>> mutation(CredentialService.Mutation result) {
        var output = new LinkedHashMap<String, Object>();
        output.put("status", result.status());
        output.put("request_id", result.requestId());
        output.put("database_committed", result.databaseCommitted());
        if (result.credential() != null) {
            output.put("credential", metadata(result.credential()));
        }
        return response(200, output);
    }

    @ExceptionHandler(CredentialException.class)
    ResponseEntity<Map<String, Object>> rejected(CredentialException failure) {
        int status = switch (failure.status()) {
            case "invalid_request", "invalid_origin", "invalid_callback" -> 400;
            case "revision_conflict", "capacity_exceeded", "receipt_capacity", "deleted_credential",
                    "stale_credential" ->
                409;
            case "not_found" -> 404;
            default -> 503;
        };
        if (status == 503) {
            return response(
                    status,
                    Map.of(
                            "status",
                            failure.status(),
                            "message",
                            "Secrets are unavailable. Reconcile the request before any further save."));
        }
        return response(
                status,
                Map.of(
                        "status",
                        failure.status(),
                        "database_committed",
                        false,
                        "message",
                        "The credential request was not saved. Check its fields and revision."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException failure) {
        return response(
                400,
                Map.of(
                        "status",
                        "invalid_request",
                        "database_committed",
                        false,
                        "message",
                        "Invalid secrets request; check the fields and limits."));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, Object>> unavailable(RuntimeException failure) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                Map.of(
                        "status",
                        "unavailable",
                        "message",
                        "Secrets are unavailable. A save may have committed; reconcile its request before any further save."));
    }

    private static ResponseEntity<Map<String, Object>> response(int status, Map<String, Object> body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").header("Pragma", "no-cache")
                .body(body);
    }
}
