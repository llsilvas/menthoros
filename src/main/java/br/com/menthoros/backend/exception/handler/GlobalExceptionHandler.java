package br.com.menthoros.backend.exception.handler;


import br.com.menthoros.backend.exception.AccessDeniedException;
import br.com.menthoros.backend.exception.AsaasIntegrationException;
import br.com.menthoros.backend.exception.ConsentResolutionUnavailableException;
import br.com.menthoros.backend.exception.ConsentVersionStaleException;
import br.com.menthoros.backend.exception.DomainConflictException;
import br.com.menthoros.backend.exception.DomainGoneException;
import br.com.menthoros.backend.exception.LgpdConsentRequiredException;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.DuplicateResourceException;
import br.com.menthoros.backend.exception.EmailDeliveryException;
import br.com.menthoros.backend.exception.FitParseException;
import br.com.menthoros.backend.exception.IntervalsIcuRateLimitException;
import br.com.menthoros.backend.exception.KeycloakIntegrationException;
import br.com.menthoros.backend.exception.SignupRateLimitException;
import br.com.menthoros.backend.exception.LLMException;
import br.com.menthoros.backend.exception.ResourceNotFoundException;
import br.com.menthoros.backend.exception.StravaRateLimitException;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;


@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", 400);
        body.put("error", "Bad Request");
        Map<String, String> fields = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handler para violações de constraint em parâmetros de request (@RequestParam, @PathVariable)
     * anotados com @Min/@Max/@NotNull via @Validated no controller.
     *
     * Mapeamento: 400 Bad Request
     * Gerada por: @Validated + @Min/@Max em parâmetros de controller (ex: dias em listarTreinosRecentes).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", 400);
        body.put("error", "Bad Request");
        Map<String, String> fields = new HashMap<>();
        ex.getConstraintViolations().forEach(cv -> {
            String path = cv.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fields.put(field, cv.getMessage());
        });
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }


    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        Map<String, Object> body = Map.of(
                "status", 404,
                "error", "Not Found",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }


    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(OptimisticLockException ex) {
        // Não expõe ex.getMessage() (contém nome da entidade + PK); usa mensagem genérica.
        log.warn("Conflito de edição concorrente (optimistic lock): {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 409,
                "error", "Conflict",
                "message", "Conflito de edição concorrente. Recarregue e tente novamente."
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        Map<String, Object> body = Map.of(
                "status", 409,
                "error", "Conflict",
                "message", "Conflito de edição concorrente. Recarregue e tente novamente."
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }


    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateResource(DuplicateResourceException ex) {
        Map<String, Object> body = Map.of(
                "status", 409,
                "error", "Conflict",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Coach sem consentimento tentando escrever.
     *
     * <p>Código próprio ({@code LGPD_CONSENT_REQUIRED}) porque a ação do frontend aqui é exibir o
     * modal de consentimento — não a tela de "sem permissão" de um 403 de autorização comum.
     */
    @ExceptionHandler(LgpdConsentRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleLgpdConsentRequired(LgpdConsentRequiredException ex) {
        Map<String, Object> body = Map.of(
                "status", 403,
                "error", "Forbidden",
                "code", "LGPD_CONSENT_REQUIRED",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Falha ao resolver o usuário para avaliar consentimento.
     *
     * <p>503 e não 403 de propósito: "não consegui verificar" não é "não consentiu". Devolver 403
     * aqui mandaria o coach para o modal por causa de uma falha de infraestrutura e esconderia o
     * defeito real.
     */
    @ExceptionHandler(ConsentResolutionUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleConsentResolutionUnavailable(
            ConsentResolutionUnavailableException ex) {
        log.error("Falha ao resolver usuário para verificação de consentimento: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 503,
                "error", "Service Unavailable",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Aceite declarando versão de documento que não é mais a vigente.
     *
     * <p>Código próprio ({@code CONSENT_VERSION_STALE}) para o frontend distinguir isto de um
     * conflito genérico: aqui a ação correta é recarregar e reapresentar o texto atualizado, não
     * exibir erro ao usuário.
     */
    @ExceptionHandler(ConsentVersionStaleException.class)
    public ResponseEntity<Map<String, Object>> handleConsentVersionStale(ConsentVersionStaleException ex) {
        log.warn("Consentimento com versão defasada: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 409,
                "error", "Conflict",
                "code", "CONSENT_VERSION_STALE",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Violação de integridade de dados: {}", ex.getMostSpecificCause().getMessage());
        Map<String, Object> body = Map.of(
                "status", 409,
                "error", "Conflict",
                "message", "Operação não permitida: conflito de dados. Verifique os dados enviados e tente novamente."
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }


    @ExceptionHandler(LLMException.class)
    public ResponseEntity<Map<String, Object>> handleLLMException(LLMException ex) {
        log.error("Erro no serviço de LLM: {}", ex.getMessage(), ex);
        Map<String, Object> body = Map.of(
                "status", 503,
                "error", "Service Unavailable",
                "message", "Serviço de IA temporariamente indisponível. Tente novamente em alguns instantes.",
                "details", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Handler para falhas na integração com o Keycloak Admin REST API.
     *
     * Mapeamento: 502 Bad Gateway
     * Gerada por: KeycloakOrganizationGatewayImpl (token, criar Organization, convite).
     *
     * O Keycloak é um serviço externo; uma falha sua não é erro interno (500),
     * e sim falha de gateway a montante.
     */
    @ExceptionHandler(KeycloakIntegrationException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloakIntegration(KeycloakIntegrationException ex) {
        log.error("Falha na integração com o Keycloak: {}", ex.getMessage(), ex);
        Map<String, Object> body = Map.of(
                "status", 502,
                "error", "Bad Gateway",
                "message", "Falha na integração com o servidor de identidade. Tente novamente em alguns instantes."
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * Handler para falhas no envio de e-mail transacional do backend.
     *
     * Mapeamento: 502 Bad Gateway
     * Gerada por: SmtpEmailSender / FileEmailSender (convite de fundadora).
     *
     * O provedor de e-mail é externo; a mensagem nunca carrega o corpo do e-mail.
     */
    @ExceptionHandler(EmailDeliveryException.class)
    public ResponseEntity<Map<String, Object>> handleEmailDelivery(EmailDeliveryException ex) {
        log.error("Falha no envio de e-mail: {}", ex.getMessage(), ex);
        Map<String, Object> body = Map.of(
                "status", 502,
                "error", "Bad Gateway",
                "message", "Falha ao enviar o e-mail. Tente novamente em alguns instantes."
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * Handler para falhas na integração com a API do Asaas (customer/subscription).
     *
     * Mapeamento: 502 Bad Gateway
     * Gerada por: AsaasGatewayImpl (criar cliente/assinatura, atualizar valor, cancelar).
     *
     * O Asaas é um serviço externo; uma falha sua não é erro interno (500), e sim
     * falha de gateway a montante.
     */
    @ExceptionHandler(AsaasIntegrationException.class)
    public ResponseEntity<Map<String, Object>> handleAsaasIntegration(AsaasIntegrationException ex) {
        log.error("Falha na integração com o Asaas: {}", ex.getMessage(), ex);
        Map<String, Object> body = Map.of(
                "status", 502,
                "error", "Bad Gateway",
                "message", "Falha na integração com o provedor de pagamento. Tente novamente em alguns instantes."
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        if (ex.getMessage() != null && ex.getMessage().contains("OpenAI")) {
            log.error("Erro na comunicação com OpenAI: {}", ex.getMessage(), ex);
            Map<String, Object> body = Map.of(
                    "status", 502,
                    "error", "Bad Gateway",
                    "message", "Erro na comunicação com serviços externos. Tente novamente."
            );
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        }
        return handleGeneric(ex);
    }

    /**
     * Handler para UnsupportedOperationException.
     *
     * Mapeamento: 501 Not Implemented
     * Gerada por: operação ainda não implementada / não suportada.
     *
     * Retorna 501 para sinalizar claramente que a operação existe no contrato
     * mas ainda não foi implementada, em vez de mascarar como 500 genérico.
     */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedOperation(UnsupportedOperationException ex) {
        log.warn("Operação não implementada: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 501,
                "error", "Not Implemented",
                "message", ex.getMessage() != null
                        ? ex.getMessage()
                        : "Operação ainda não implementada."
        );
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Argumento inválido: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 400,
                "error", "Bad Request",
                "message", ex.getMessage()
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handler para IllegalStateException gerada por TenantContext.getRequiredTenantId()
     * quando o tenant não está configurado na thread (ausência de JWT válido).
     *
     * Mapeamento: 403 Forbidden
     * Por quê 403 e não 401?
     * - 401 = não autenticado (sem credenciais)
     * - 403 = autenticado mas sem permissão / contexto inválido
     * - Neste caso o JWT pode ter sido apresentado mas o filtro de tenant não configurou o contexto.
     *
     * SEGURANÇA: A mensagem de resposta é genérica e não expõe detalhes internos
     * (como "Tenant não configurado para a requisição atual").
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        log.warn("SECURITY: Contexto de tenant ausente ou inválido: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Acesso não autorizado: contexto de tenant ausente"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        log.warn("Header obrigatório ausente: {}", ex.getHeaderName());
        Map<String, Object> body = Map.of(
                "status", 400,
                "error", "Bad Request",
                "message", "Header obrigatório ausente: " + ex.getHeaderName()
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("Recurso não encontrado: {}", ex.getResourcePath());
        Map<String, Object> body = Map.of(
                "status", 404,
                "error", "Not Found",
                "message", "Recurso não encontrado: " + ex.getResourcePath()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(DomainNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDomainNotFound(DomainNotFoundException ex) {
        log.warn("Domínio não encontrado: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 404,
                "error", "Not Found",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(DomainGoneException.class)
    public ResponseEntity<Map<String, Object>> handleDomainGone(DomainGoneException ex) {
        log.warn("Recurso não mais disponível: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 410,
                "error", "Gone",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    @ExceptionHandler(DomainRuleViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDomainRuleViolation(DomainRuleViolationException ex) {
        log.warn("Violação de regra de domínio: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 422,
                "error", "Unprocessable Entity",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(DomainConflictException.class)
    public ResponseEntity<Map<String, Object>> handleDomainConflict(DomainConflictException ex) {
        log.warn("Conflito de domínio: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 409,
                "error", "Conflict",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(FitParseException.class)
    public ResponseEntity<Map<String, Object>> handleFitParseException(FitParseException ex) {
        log.warn("Falha ao parsear arquivo .fit: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 422,
                "error", "Unprocessable Entity",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("Upload excedeu o tamanho máximo permitido: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 413,
                "error", "Payload Too Large",
                "message", "Arquivo excede o tamanho máximo permitido."
        );
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /**
     * Limite de auto-cadastro. A mensagem é deliberadamente vaga: dizer qual dimensão estourou
     * (IP, e-mail ou teto global) entrega ao atacante o mapa da defesa.
     */
    @ExceptionHandler(SignupRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleSignupRateLimit(SignupRateLimitException ex) {
        log.warn("Limite de auto-cadastro atingido: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 429,
                "error", "Too Many Requests",
                "message", "Muitas solicitações. Tente novamente mais tarde."
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(StravaRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleStravaRateLimit(StravaRateLimitException ex) {
        log.warn("Limite de taxa da API Strava excedido: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 429,
                "error", "Too Many Requests",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(IntervalsIcuRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleIntervalsIcuRateLimit(IntervalsIcuRateLimitException ex) {
        log.warn("Rate limit ou instabilidade do intervals.icu: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 429,
                "error", "Too Many Requests",
                "message", ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    /**
     * Handler para AccessDeniedException.
     *
     * Mapeamento: 403 Forbidden
     * Gerada por: TenantValidationAspect quando acesso cross-tenant é detectado
     *
     * Porquê logs WARN?
     * - Tentativa de acesso cross-tenant é suspeita
     * - Pode indicar ataque ou bug no frontend
     * - Precisa ser auditado
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("SECURITY: Acesso negado - {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 403,
                "error", "Forbidden",
                "message", "Acesso negado. Você não possui permissão para acessar este recurso."
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Handler para AuthorizationDeniedException do Spring Security 6+.
     * Lançada por @PreAuthorize quando o usuário não tem a role necessária.
     * Mapeamento: 403 Forbidden (não 500 — é uma negação de acesso esperada).
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAuthorizationDenied(AuthorizationDeniedException ex) {
        log.warn("Acesso negado por @PreAuthorize: {}", ex.getMessage());
        Map<String, Object> body = Map.of(
                "status", 403,
                "error", "Forbidden",
                "message", "Acesso negado. Você não possui permissão para realizar esta operação."
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Erro interno não tratado: {}", ex.getMessage(), ex);
        Map<String, Object> body = Map.of(
                "status", 500,
                "error", "Internal Server Error",
                "message", "Erro interno do servidor. Contate o suporte se o problema persistir."
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
