package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.AbstractIntegrationTest;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.entity.PlanoMetaDados;
import br.com.menthoros.backend.enums.AtletaStatus;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.enums.PlanoAssessoria;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.PlanoMetadadosRepository;
import br.com.menthoros.backend.services.PlanoMetadadosService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regressão do incidente de 2026-08-15: gerar o primeiro plano de um atleta novo falhava com
 * {@code Metadados não encontrados: <uuid>}, e o erro **nunca se resolvia sozinho**.
 *
 * <p>A causa era estrutural. {@code buscarOuCriarMetadados} era {@code @Cacheable} e, quando não
 * encontrava, <b>criava e salvava</b> dentro da transação do chamador. O Spring popula o cache
 * antes do commit — então, quando a transação de {@code gerarPlanoTreino} revertia (o que é
 * provável num fluxo que chama LLM e leva ~50s), o {@code INSERT} sumia do banco e o objeto
 * permanecia no cache com um ID que nunca existiu. Toda tentativa seguinte lia o ID fantasma,
 * falhava no {@code findByIdAndTenantId} e revertia de novo.
 *
 * <p>Este teste deliberadamente NÃO é {@code @Transactional}: precisa que o rollback aconteça de
 * verdade para reproduzir o cenário.
 */
@DisplayName("Metadados de plano — recuperação após rollback")
class PlanoMetadadosCacheIT extends AbstractIntegrationTest {

    @Autowired private PlanoMetadadosService planoMetadadosService;
    @Autowired private PlanoMetadadosRepository planoMetadadosRepository;
    @Autowired private AtletaRepository atletaRepository;
    @Autowired private AssessoriaRepository assessoriaRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private br.com.menthoros.backend.services.TsbService tsbService;
    @Autowired private br.com.menthoros.backend.services.PlanoService planoService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private br.com.menthoros.backend.services.IaService iaService;

    private Assessoria assessoria;
    private Atleta atleta;

    @BeforeEach
    void preparar() {
        assessoria = new Assessoria();
        assessoria.setNome("Assessoria Metadados");
        assessoria.setDominio("meta-" + UUID.randomUUID());
        assessoria.setPlano(PlanoAssessoria.BASIC);
        assessoria = assessoriaRepository.save(assessoria);

        Atleta novo = new Atleta();
        novo.setNome("Atleta Sem Histórico");
        novo.setObjetivo("Primeiro 5k");
        novo.setNivelExperiencia(NivelExperiencia.INICIANTE);
        novo.setAtivo(AtletaStatus.ATIVO);
        novo.setDiasDisponiveis(java.util.List.of(
                br.com.menthoros.backend.enums.DiaSemana.SEGUNDA, br.com.menthoros.backend.enums.DiaSemana.QUARTA));
        novo.setDiaPreferidoLongo(br.com.menthoros.backend.enums.DiaSemana.SABADO);
        novo.setAssessoria(assessoria);
        atleta = atletaRepository.save(novo);

        TenantContext.setTenantId(assessoria.getId());
    }

    @AfterEach
    void limpar() {
        TenantContext.clear();
    }

    /**
     * O coração da regressão: uma transação que cria metadados e reverte não pode envenenar as
     * chamadas seguintes. O que o wizard produz — atleta zerado, primeira geração de plano — é
     * exatamente o caminho onde isso aparecia.
     */
    @Test
    @DisplayName("após rollback, a chamada seguinte devolve metadados que existem no banco")
    void aposRollbackDevolveMetadadosReais() {
        // Primeira tentativa: cria os metadados e reverte, como uma geração de plano que falha.
        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            planoMetadadosService.buscarOuCriarMetadados(atleta);
            throw new IllegalStateException("falha simulada depois de criar os metadados");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(planoMetadadosRepository.findLatestByAtletaId(atleta.getId()))
                .as("o rollback tem de ter desfeito a criação")
                .isEmpty();

        // Segunda tentativa: precisa devolver algo que EXISTE. Com o cache antigo, viria o ID
        // fantasma da tentativa revertida e o fluxo quebraria para sempre.
        PlanoMetaDados metadados = planoMetadadosService.buscarOuCriarMetadados(atleta);

        assertThat(metadados.getId()).isNotNull();
        assertThat(planoMetadadosRepository.findByIdAndTenantId(metadados.getId(), assessoria.getId()))
                .as("os metadados devolvidos precisam ser encontráveis pela leitura tenant-scoped")
                .isPresent();
    }

    /**
     * O contrato novo (refactor-llm-call-outside-transaction, design.md D1): a fase de leitura
     * commita antes da chamada ao LLM, então o metadado criado ali <b>sobrevive</b> a uma falha do
     * modelo — é idempotente por construção, e foi exatamente o rollback tardio de ~50s que
     * produziu o incidente acima. Uma segunda tentativa reaproveita a mesma linha.
     */
    @Test
    @DisplayName("metadado criado na fase de leitura sobrevive à falha do LLM e é reaproveitado")
    void metadadoSobreviveAFalhaDoLlm() {
        org.mockito.Mockito.when(iaService.geraPlanoSemanalAvancado(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new br.com.menthoros.backend.exception.LLMException("provedor indisponível"));

        assertThatThrownBy(() -> planoService.gerarPlanoTreino(
                atleta.getId(), br.com.menthoros.backend.enums.ModoGeracaoPlano.PROXIMA_SEMANA))
                .isInstanceOf(br.com.menthoros.backend.exception.LLMException.class);

        java.util.Optional<PlanoMetaDados> criado = planoMetadadosRepository.findLatestByAtletaId(atleta.getId());
        assertThat(criado).as("a fase 1 commitou antes do LLM; a falha do modelo não a desfaz").isPresent();

        PlanoMetaDados reaproveitado = planoMetadadosService.buscarOuCriarMetadados(atleta);
        assertThat(reaproveitado.getId()).isEqualTo(criado.get().getId());
        assertThat(planoMetadadosRepository.findAll().stream()
                .filter(m -> m.getAtleta().getId().equals(atleta.getId()))
                .count()).as("uma linha só, mesmo depois da falha").isEqualTo(1);
    }

    /**
     * Metadados sem assessoria seriam invisíveis para {@code findByIdAndTenantId} — o mesmo erro,
     * só que persistido no banco em vez de vivendo no cache.
     */
    @Test
    @DisplayName("metadados nascem sempre com a assessoria do atleta")
    void metadadosNascemComTenant() {
        PlanoMetaDados metadados = planoMetadadosService.buscarOuCriarMetadados(atleta);

        assertThat(metadados.getAssessoria()).isNotNull();
        assertThat(metadados.getAssessoria().getId()).isEqualTo(assessoria.getId());
        assertThat(planoMetadadosRepository.findByIdAndTenantId(metadados.getId(), assessoria.getId()))
                .isPresent();
    }

    /**
     * O segundo defeito do mesmo incidente. Um atleta sem treino algum entra em
     * {@code recalcularHistoricoCompleto} pelo ramo "sem histórico", que zerava metadados — e
     * lançava exceção quando eles ainda não existiam, que é o normal para quem acabou de ser
     * cadastrado. Resultado: a geração do primeiro plano morria, e o coach via "erro ao gerar
     * plano" sem pista nenhuma.
     */
    @Test
    @DisplayName("recalcular histórico de atleta sem treinos não explode por falta de metadados")
    void recalculoDeAtletaZeradoNaoExplode() {
        assertThat(planoMetadadosRepository.findByAtletaId(atleta.getId()))
                .as("o cenário exige um atleta que ainda não tem metadados")
                .isEmpty();

        tsbService.recalcularHistoricoCompleto(atleta.getId());

        // Nada a assertar além de "não lançou": o estado desejado (métricas zeradas) já vale
        // quando não há metadados.
        assertThat(planoMetadadosRepository.findByAtletaId(atleta.getId())).isEmpty();
    }

    @Test
    @DisplayName("chamar duas vezes reaproveita os metadados existentes")
    void segundaChamadaReaproveita() {
        PlanoMetaDados primeiro = planoMetadadosService.buscarOuCriarMetadados(atleta);
        PlanoMetaDados segundo = planoMetadadosService.buscarOuCriarMetadados(atleta);

        assertThat(segundo.getId()).isEqualTo(primeiro.getId());
        assertThat(planoMetadadosRepository.findAll().stream()
                .filter(m -> m.getAtleta().getId().equals(atleta.getId()))
                .count())
                .as("não pode criar um metadado por chamada")
                .isEqualTo(1);
    }
}
