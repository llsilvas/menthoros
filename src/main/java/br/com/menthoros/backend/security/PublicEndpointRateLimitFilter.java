package br.com.menthoros.backend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate-limit <b>por IP</b> nos endpoints públicos que criam recurso sem autenticação.
 *
 * <p>Generalização do antigo {@code WaitlistRateLimitFilter}: a política é uma só, aplicada a mais de
 * uma rota com limites próprios. Criar um segundo filtro para o auto-cadastro produziria <b>duas
 * políticas divergentes</b> para o mesmo tipo de rota — e a que ficasse desatualizada seria descoberta
 * por um incidente, não por leitura.</p>
 *
 * <p><b>Esta é apenas a dimensão de IP.</b> Os limites por e-mail e o teto diário global vivem no
 * serviço de cadastro, e não por preferência: um filtro não enxerga o e-mail sem consumir o corpo da
 * requisição. A divisão é por <em>o que cada camada consegue ver</em>, não por política.</p>
 *
 * <p><b>IP de origem:</b> {@code request.getRemoteAddr()}, não o {@code X-Forwarded-For} cru. Com
 * {@code server.forward-headers-strategy=framework}, o {@code ForwardedHeaderFilter} ajusta o
 * {@code remoteAddr} para o cliente real — assim o atacante não escapa do contador rotacionando o
 * header.</p>
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 1)
@Slf4j
public class PublicEndpointRateLimitFilter extends OncePerRequestFilter {

    static final String PATH_WAITLIST = "/api/v1/waitlist";
    static final String PATH_COACH_SIGNUP = "/api/public/coach-signups";
    /** Prefixo: o token vai no path. Só GET. */
    static final String PATH_FOUNDING_INVITE_LOOKUP = "/api/public/founding-invites/";
    /** Prefixo do lookup do convite de atleta (GET, token no path). */
    static final String PATH_ATHLETE_INVITE_LOOKUP = "/api/public/athlete-invites/";
    /** Aceite do convite de atleta: cria conta — mesma cautela do coach signup. */
    static final String PATH_ATHLETE_INVITE_ACCEPT = "/api/public/athlete-invites/aceitar";

    /** Uma política por rota: limite, janela, e o contador que a materializa. */
    private record Politica(int limite, Duration janela, Cache<String, AtomicInteger> contador) {

        static Politica de(int limite, Duration janela) {
            return new Politica(limite, janela, Caffeine.newBuilder()
                    .expireAfterWrite(janela)
                    .maximumSize(10_000)
                    .build());
        }
    }

    /** Políticas dos POSTs públicos, por URI exata. */
    private final Map<String, Politica> politicas;
    /** Política do GET de consulta do convite, por prefixo. */
    private final Politica consultaConvite;

    public PublicEndpointRateLimitFilter(
            @Value("${app.waitlist.rate-limit.per-minute:5}") int waitlistPorMinuto,
            @Value("${app.coach-signup.rate-limit.per-hour:3}") int signupPorHora,
            @Value("${app.founding-invite.rate-limit.per-minute:10}") int consultaConvitePorMinuto) {

        this.politicas = new LinkedHashMap<>();
        // Propriedade preservada com o nome antigo: renomeá-la faria os ambientes que já a
        // configuram voltarem silenciosamente ao default.
        politicas.put(PATH_WAITLIST, Politica.de(waitlistPorMinuto, Duration.ofMinutes(1)));
        // Janela de hora, e não de minuto: o cadastro é uma ação rara e cara. Um humano não faz
        // três num minuto, e quem faz não é humano.
        politicas.put(PATH_COACH_SIGNUP, Politica.de(signupPorHora, Duration.ofHours(1)));
        // Aceite do convite de atleta cria conta no Keycloak — janela de hora, como o coach
        // signup: ação rara e cara; três num minuto não é humano.
        politicas.put(PATH_ATHLETE_INVITE_ACCEPT, Politica.de(signupPorHora, Duration.ofHours(1)));
        // Um GET que devolve nome e e-mail para quem tiver um token: barato de servir, mas é PII e
        // não pode ficar sem teto. Minuto, não hora: a página consulta uma vez ao abrir.
        this.consultaConvite = Politica.de(consultaConvitePorMinuto, Duration.ofMinutes(1));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return politicaDe(request) == null;
    }

    /**
     * Method-aware: os POSTs públicos casam por URI exata; o único GET protegido casa por prefixo,
     * porque o token faz parte do path. Qualquer outra combinação passa direto.
     */
    private Politica politicaDe(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (HttpMethod.POST.matches(request.getMethod())) {
            return politicas.get(uri);
        }
        if (HttpMethod.GET.matches(request.getMethod())
                && (uri.startsWith(PATH_FOUNDING_INVITE_LOOKUP) || uri.startsWith(PATH_ATHLETE_INVITE_LOOKUP))) {
            return consultaConvite;
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Politica politica = politicaDe(request);
        if (politica == null) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);
        AtomicInteger contador = politica.contador().get(ip, k -> new AtomicInteger(0));
        if (contador.incrementAndGet() > politica.limite()) {
            log.warn("Rate-limit excedido: rota={}, ip={}", request.getRequestURI(), mascararIp(ip));
            responder429(response, politica.janela().toSeconds());
            return;
        }
        chain.doFilter(request, response);
    }

    private void responder429(HttpServletResponse response, long retryAfterSegundos) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSegundos));
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\","
                + "\"message\":\"Muitas solicitações. Tente novamente em instantes.\"}");
    }

    private String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return (ip != null && !ip.isBlank()) ? ip : "desconhecido";
    }

    /** Mascara o último octeto (IPv4) ou os últimos grupos (IPv6) — IP é dado pessoal (LGPD). */
    private String mascararIp(String ip) {
        if (ip == null) {
            return "desconhecido";
        }
        if (ip.contains(".")) {
            return ip.replaceAll("\\.\\d+$", ".xxx");
        }
        if (ip.contains(":")) {
            return ip.replaceAll("(:[0-9a-fA-F]+){1,4}$", ":xxxx");
        }
        return "xxx";
    }
}
