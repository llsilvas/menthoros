package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.Usuario;
import br.com.menthoros.backend.enums.UserRole;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cobertura da escrita condicional do sync (change harden-backend-db-resilience):
 * requisição sem diff e dentro da janela de throttle não pode gerar UPDATE — foi o
 * amplificador do incidente de pool de 2026-09-04.
 */
@ExtendWith(MockitoExtension.class)
class UsuarioSyncServiceImplThrottleTest {

    @Mock private UsuarioRepository usuarioRepository;
    @Mock private AssessoriaRepository assessoriaRepository;
    @Mock private AtletaRepository atletaRepository;

    @InjectMocks private UsuarioSyncServiceImpl service;

    private static final String EMAIL = "ana@teste.com";
    private static final String NOME = "Ana";
    private static final String SOBRENOME = "Atleta";

    private Jwt jwt(List<String> roles) {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("email", EMAIL)
                .claim("given_name", NOME)
                .claim("family_name", SOBRENOME)
                .claim("email_verified", true)
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    /** Usuário já idêntico ao JWT, com acesso registrado agora (dentro da janela de throttle). */
    private Usuario usuarioEspelhado(UserRole role) {
        Usuario u = new Usuario();
        u.setId(UUID.randomUUID());
        u.setKeycloakId(u.getId().toString());
        u.setEmail(EMAIL);
        u.setNome(NOME);
        u.setSobrenome(SOBRENOME);
        u.setEmailVerificado(true);
        u.setRole(role);
        u.setOwner(false);
        u.registrarAcesso();
        u.registrarSincronizacao();
        return u;
    }

    private void mockExistente(Usuario existente) {
        when(usuarioRepository.findByKeycloakId(any())).thenReturn(Optional.of(existente));
        lenient().when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(atletaRepository.findByEmailAndAssessoria_Id(any(), any())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("caminho no-op (sem diff, dentro da janela)")
    class CaminhoNoOp {

        @Test
        @DisplayName("não persiste nada")
        void naoPersiste() {
            mockExistente(usuarioEspelhado(UserRole.TECNICO));

            service.syncUsuarioFromJwt(jwt(List.of("TECNICO")), UUID.randomUUID());

            verify(usuarioRepository, never()).save(any());
        }

        @Test
        @DisplayName("ainda retorna o usuário resolvido (contrato do LgpdConsentInterceptor)")
        void retornaUsuarioResolvido() {
            Usuario existente = usuarioEspelhado(UserRole.TECNICO);
            mockExistente(existente);

            Usuario resultado = service.syncUsuarioFromJwt(jwt(List.of("TECNICO")), UUID.randomUUID());

            assertThat(resultado).isSameAs(existente);
        }

        @Test
        @DisplayName("ainda vincula atleta órfão mesmo sem escrever o usuário")
        void vinculaAtletaOrfaoMesmoSemEscrita() {
            UUID tenantId = UUID.randomUUID();
            Usuario existente = usuarioEspelhado(UserRole.ATLETA);
            mockExistente(existente);
            Atleta orfao = new Atleta();
            orfao.setId(UUID.randomUUID());
            when(atletaRepository.findByEmailAndAssessoria_Id(EMAIL, tenantId))
                    .thenReturn(Optional.of(orfao));

            service.syncUsuarioFromJwt(jwt(List.of("ATLETA")), tenantId);

            verify(usuarioRepository, never()).save(any());
            verify(atletaRepository).save(orfao);
            assertThat(orfao.getUsuario()).isSameAs(existente);
        }
    }

    @Nested
    @DisplayName("diff em campo espelhado força escrita mesmo dentro da janela")
    class DiffForcaEscrita {

        static Stream<Arguments> mutacoes() {
            return Stream.of(
                    Arguments.of("email", (Consumer<Usuario>) u -> u.setEmail("outro@teste.com")),
                    Arguments.of("nome", (Consumer<Usuario>) u -> u.setNome("Outra")),
                    Arguments.of("sobrenome", (Consumer<Usuario>) u -> u.setSobrenome("Outro")),
                    Arguments.of("emailVerificado", (Consumer<Usuario>) u -> u.setEmailVerificado(false)),
                    Arguments.of("role", (Consumer<Usuario>) u -> u.setRole(UserRole.VISUALIZADOR)),
                    Arguments.of("owner (remoção de PROPRIETARIO)", (Consumer<Usuario>) u -> u.setOwner(true))
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("mutacoes")
        void persisteQuandoCampoDiverge(String campo, Consumer<Usuario> divergir) {
            Usuario existente = usuarioEspelhado(UserRole.TECNICO);
            divergir.accept(existente);
            mockExistente(existente);

            Usuario resultado = service.syncUsuarioFromJwt(jwt(List.of("TECNICO")), UUID.randomUUID());

            verify(usuarioRepository).save(existente);
            // pós-condição: o resultado reflete o JWT, não o valor divergente
            assertThat(resultado.getEmail()).isEqualTo(EMAIL);
            assertThat(resultado.getRole()).isEqualTo(UserRole.TECNICO);
            assertThat(resultado.isOwner()).isFalse();
        }
    }

    @Nested
    @DisplayName("usuário novo")
    class UsuarioNovo {

        @Test
        @DisplayName("sempre persiste, com os dados do JWT aplicados")
        void usuarioNovoSemprePersiste() {
            UUID tenantId = UUID.randomUUID();
            br.com.menthoros.backend.entity.Assessoria assessoria =
                    new br.com.menthoros.backend.entity.Assessoria();
            assessoria.setId(tenantId);
            when(usuarioRepository.findByKeycloakId(any())).thenReturn(Optional.empty());
            when(assessoriaRepository.findById(tenantId)).thenReturn(Optional.of(assessoria));
            when(usuarioRepository.save(any(Usuario.class))).thenAnswer(inv -> inv.getArgument(0));

            Usuario resultado = service.syncUsuarioFromJwt(jwt(List.of("TECNICO")), tenantId);

            verify(usuarioRepository).save(any(Usuario.class));
            assertThat(resultado.getEmail()).isEqualTo(EMAIL);
            assertThat(resultado.getNome()).isEqualTo(NOME);
            assertThat(resultado.getRole()).isEqualTo(UserRole.TECNICO);
            assertThat(resultado.getUltimoAcesso()).isNotNull();
        }
    }

    @Nested
    @DisplayName("throttle de último acesso")
    class ThrottleDeAcesso {

        @Test
        @DisplayName("acesso mais velho que a janela registra e persiste uma vez")
        void acessoVencidoPersiste() {
            Usuario existente = usuarioEspelhado(UserRole.TECNICO);
            existente.setUltimoAcesso(LocalDateTime.now().minusMinutes(10));
            mockExistente(existente);

            service.syncUsuarioFromJwt(jwt(List.of("TECNICO")), UUID.randomUUID());

            verify(usuarioRepository).save(existente);
            assertThat(existente.getUltimoAcesso())
                    .isAfter(LocalDateTime.now().minusMinutes(1));
        }

        @Test
        @DisplayName("acesso nulo (usuário pré-existente sem registro) persiste")
        void acessoNuloPersiste() {
            Usuario existente = usuarioEspelhado(UserRole.TECNICO);
            existente.setUltimoAcesso(null);
            mockExistente(existente);

            service.syncUsuarioFromJwt(jwt(List.of("TECNICO")), UUID.randomUUID());

            verify(usuarioRepository).save(existente);
        }

        @Test
        @DisplayName("PT0S desliga o throttle: toda chamada volta a persistir (rollback sem deploy)")
        void throttleZeradoPersisteSempre() {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "accessThrottle", java.time.Duration.ZERO);
            Usuario existente = usuarioEspelhado(UserRole.TECNICO);
            mockExistente(existente);

            service.syncUsuarioFromJwt(jwt(List.of("TECNICO")), UUID.randomUUID());

            verify(usuarioRepository).save(existente);
        }
    }
}
