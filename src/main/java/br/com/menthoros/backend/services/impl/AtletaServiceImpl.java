package br.com.menthoros.backend.services.impl;

import br.com.menthoros.backend.dto.input.AtletaInputDto;
import br.com.menthoros.backend.dto.output.AtletaOutputDto;
import br.com.menthoros.backend.entity.Assessoria;
import br.com.menthoros.backend.entity.Atleta;
import br.com.menthoros.backend.enums.AtletaStatus;
import br.com.menthoros.backend.enums.NivelExperiencia;
import br.com.menthoros.backend.exception.DomainNotFoundException;
import br.com.menthoros.backend.exception.DomainRuleViolationException;
import br.com.menthoros.backend.exception.ResourceNotFoundException;
import br.com.menthoros.backend.mapper.AtletaMapper;
import br.com.menthoros.backend.multitenancy.TenantContext;
import br.com.menthoros.backend.repository.AssessoriaRepository;
import br.com.menthoros.backend.repository.AtletaRepository;
import br.com.menthoros.backend.repository.PlanoMetadadosRepository;
import br.com.menthoros.backend.repository.specification.AtletaSpecification;
import br.com.menthoros.backend.services.AtletaService;
import br.com.menthoros.backend.services.AthleteInviteService;
import br.com.menthoros.backend.services.TsbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AtletaServiceImpl implements AtletaService {

    private final AtletaRepository atletaRepository;
    private final AssessoriaRepository assessoriaRepository;
    private final AtletaMapper atletaMapper;
    private final PlanoMetadadosRepository planoMetaDadosRepository;
    private final TsbService tsbService;
    private final AthleteInviteService athleteInviteService;

    private static final String HAS_TENANT =
            "T(br.com.menthoros.backend.multitenancy.TenantContext).hasTenant()";
    private static final String TENANT_KEY =
            "T(br.com.menthoros.backend.multitenancy.TenantContext).getTenantId()";

    /**
     * Resolve o atleta vinculado a um usuário dentro do tenant da requisição atual.
     *
     * Idempotent: YES — Read-only, sem mutação de estado.
     * Side Effects: NONE
     * Tenant-aware: YES — usa TenantContext.getRequiredTenantId() e query tenant-scoped.
     *
     * @param usuarioId UUID do usuário (Usuario.id)
     * @return o Atleta vinculado, ou vazio se não houver vínculo no tenant
     * @throws IllegalArgumentException se usuarioId for null
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Atleta> findVinculadoAoUsuario(UUID usuarioId) {
        if (usuarioId == null) {
            throw new IllegalArgumentException("usuarioId cannot be null");
        }
        UUID tenantId = TenantContext.getRequiredTenantId();
        return atletaRepository.findByUsuario_IdAndAssessoria_Id(usuarioId, tenantId);
    }

    /**
     * Cria um novo atleta vinculado ao tenant da requisição atual.
     *
     * Idempotent: NO — Cria nova entidade a cada chamada.
     * Side Effects: Database insert (novo Atleta criado)
     * Tenant-aware: YES — usa TenantContext.getRequiredTenantId()
     *
     * @param atletaInputDto dados do atleta a ser criado
     * @return Atleta entidade persistida
     * @throws IllegalStateException se o tenant não estiver configurado (ausência de JWT)
     * @throws ResourceNotFoundException se a assessoria do tenant não for encontrada
     */
    @Override
    @CacheEvict(value = "atletas-list", key = TENANT_KEY, condition = HAS_TENANT)
    public Atleta createAtleta(AtletaInputDto atletaInputDto) {
        UUID tenantId = TenantContext.getRequiredTenantId();

        Assessoria assessoria = assessoriaRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assessoria não encontrada para tenant: " + tenantId));

        Atleta entity = atletaMapper.toEntity(atletaInputDto);
        entity.setAtivo(AtletaStatus.ATIVO);
        entity.setAssessoria(assessoria);
        return atletaRepository.save(entity);
    }

    /**
     * Atualiza um atleta existente do tenant da requisição atual.
     *
     * Idempotent: YES — Atualizar com os mesmos dados produz o mesmo resultado.
     * Side Effects: Database update (Atleta atualizado)
     * Tenant-aware: YES — usa TenantContext.getRequiredTenantId()
     *
     * @param id UUID do atleta a ser atualizado
     * @param atletaInputDto novos dados do atleta
     * @return AtletaOutputDto com os dados atualizados
     * @throws IllegalStateException se o tenant não estiver configurado (ausência de JWT)
     * @throws ResourceNotFoundException se o atleta não for encontrado no tenant
     */
    @Transactional
    @Override
    @Caching(evict = {
            @CacheEvict(value = "atletas", key = "#id + '_' + " + TENANT_KEY, condition = HAS_TENANT),
            @CacheEvict(value = "atletas-list", key = TENANT_KEY, condition = HAS_TENANT),
            @CacheEvict(value = "metadados-atleta", key = "#id + '_' + " + TENANT_KEY, condition = HAS_TENANT)
    })
    public AtletaOutputDto updateAtleta(UUID id, AtletaInputDto atletaInputDto) {
        UUID tenantId = TenantContext.getRequiredTenantId();

        Atleta atleta = atletaRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Atleta não encontrado: " + id));

        atletaMapper.updateEntity(atletaInputDto, atleta);
        Atleta saved = atletaRepository.save(atleta);
        return atletaMapper.toOutputDto(saved);
    }

    /**
     * Remove (soft delete) um atleta do tenant da requisição atual.
     *
     * Idempotent: YES — Deletar duas vezes é seguro (já está inativo).
     * Side Effects: Database update (Atleta marcado como INATIVO)
     * Tenant-aware: YES — usa TenantContext.getRequiredTenantId()
     *
     * @param id UUID do atleta a ser removido
     * @throws IllegalStateException se o tenant não estiver configurado (ausência de JWT)
     * @throws ResourceNotFoundException se o atleta não for encontrado no tenant
     */
    @Override
    @Caching(evict = {
            @CacheEvict(value = "atletas", key = "#id + '_' + " + TENANT_KEY, condition = HAS_TENANT),
            @CacheEvict(value = "atletas-list", key = TENANT_KEY, condition = HAS_TENANT),
            @CacheEvict(value = "metadados-atleta", key = "#id + '_' + " + TENANT_KEY, condition = HAS_TENANT)
    })
    public void deleteAtleta(UUID id) {
        UUID tenantId = TenantContext.getRequiredTenantId();

        Atleta atleta = atletaRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Atleta não encontrado: " + id));

        atleta.setAtivo(AtletaStatus.INATIVO);
        atletaRepository.save(atleta);
    }

    /**
     * Busca um atleta por ID dentro do tenant da requisição atual.
     *
     * Idempotent: YES — Operação de leitura, sem alteração de estado.
     * Side Effects: NONE
     * Tenant-aware: YES — usa TenantContext.getRequiredTenantId()
     *
     * @param id UUID do atleta a ser buscado
     * @return AtletaOutputDto com os dados do atleta
     * @throws IllegalStateException se o tenant não estiver configurado (ausência de JWT)
     * @throws ResourceNotFoundException se o atleta não for encontrado no tenant
     */
    @Override
    @Cacheable(value = "atletas", key = "#id + '_' + " + TENANT_KEY, condition = HAS_TENANT)
    @Transactional(readOnly = true)
    public AtletaOutputDto getAtletaById(UUID id) {
        UUID tenantId = TenantContext.getRequiredTenantId();

        Atleta atleta = atletaRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Atleta não encontrado: " + id));

        Hibernate.initialize(atleta.getProvas());
        Hibernate.initialize(atleta.getDiasDisponiveis());
        return atletaMapper.toOutputDto(atleta);
    }

    /**
     * Lista todos os atletas ativos do tenant da requisição atual, com filtros opcionais.
     *
     * Idempotent: YES — Operação de leitura, sem alteração de estado.
     * Side Effects: NONE
     * Tenant-aware: YES — usa TenantContext.getRequiredTenantId()
     *
     * @param nome filtro opcional por nome (busca parcial)
     * @param nivelExperiencia filtro opcional por nível de experiência
     * @param temLesao filtro opcional por presença de lesão
     * @return lista de AtletaOutputDto com atletas ativos do tenant
     * @throws IllegalStateException se o tenant não estiver configurado (ausência de JWT)
     */
    @Override
    @Cacheable(
        value = "atletas-list",
        key = TENANT_KEY,
        condition = HAS_TENANT + " and #nome == null and #nivelExperiencia == null and #temLesao == null"
    )
    @Transactional(readOnly = true)
    public List<AtletaOutputDto> getAllAtletas(String nome, NivelExperiencia nivelExperiencia, Boolean temLesao) {
        UUID tenantId = TenantContext.getRequiredTenantId();

        Specification<Atleta> spec = Specification
                .where(AtletaSpecification.byTenant(tenantId))
                .and(AtletaSpecification.active());

        if (nome != null && !nome.isBlank()) {
            spec = spec.and(AtletaSpecification.withNameContaining(nome));
        }
        if (nivelExperiencia != null) {
            spec = spec.and(AtletaSpecification.withNivelExperiencia(nivelExperiencia));
        }
        if (temLesao != null) {
            spec = spec.and(AtletaSpecification.withTemLesao(temLesao));
        }

        return atletaRepository.findAll(spec).stream().map(atleta -> {
            Hibernate.initialize(atleta.getDiasDisponiveis());
            Hibernate.initialize(atleta.getProvas());
            return atletaMapper.toOutputDto(atleta);
        }).toList();
    }

    /**
     * Dispara o recálculo completo do histórico de métricas TSB do atleta.
     *
     * Idempotent: NO — Atualiza métricas a cada chamada; dados mudam com o tempo.
     * Side Effects: Database update (múltiplos registros de TSB atualizados)
     * Tenant-aware: NO — operação interna acionada via ID; tenant já validado no momento da criação do atleta.
     *
     * @param id UUID do atleta cujas métricas serão recalculadas
     */
    @Override
    public void recalcularMetricasAtleta(UUID id) {
        tsbService.recalcularHistoricoCompleto(id);
    }

    /**
     * Gera ou reenvia o convite de acesso para o atleta — por token do backend, não mais pelo
     * invite-user do Keycloak Organizations (change add-athlete-invite-token-link).
     *
     * Idempotent: NO — cada chamada (re)envia o convite (efeito externo observável); não duplica Atleta/Usuario.
     * Side Effects: Database insert/update + External API (SMTP) — via AthleteInviteService.
     * Tenant-aware: YES
     *
     * Deliberadamente SEM @Transactional: o serviço delegado envia e-mail fora de transação e
     * depende de a UNIQUE parcial decidir corrida de emissão no próprio insert.
     *
     * @param atletaId UUID do atleta a ser convidado
     * @throws DomainNotFoundException se o atleta não pertencer ao tenant atual
     * @throws DomainRuleViolationException se o atleta não possuir email
     */
    @Override
    public void gerarConvite(UUID atletaId) {
        athleteInviteService.invite(atletaId);
    }

}
