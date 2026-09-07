package br.com.menthoros.backend.exception;

/**
 * Recurso que existiu mas não está mais disponível (HTTP 410) — ex.: convite expirado,
 * já consumido ou invalidado por reenvio. Diferente de {@link DomainNotFoundException}:
 * o 410 diz ao usuário "este link não vale mais, peça outro", enquanto o 404 não afirma nada.
 */
public class DomainGoneException extends RuntimeException {

    public DomainGoneException(String message) {
        super(message);
    }
}
