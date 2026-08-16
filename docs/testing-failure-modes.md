# Testes de modos de falha

Cobertura automatizada de cenários de falha e concorrência, e o que ainda exige
infraestrutura adicional.

## Como rodar

```bash
# Tudo (inclui testes de integração; requer Docker ou REDIS_TEST_URI)
mvn test

# Só os testes de chaos (disconnect/invalidation perdida)
mvn test -Dgroups=chaos

# Excluir chaos do build principal (se necessário em runners lentos)
mvn test -DexcludedGroups=chaos
```

Os testes de chaos usam um container Redis **dedicado** por classe
(`RedisDisconnectIntegrationTest`) e pause/unpause/stop via Docker client — não
interferem no container compartilhado dos demais testes de integração.

## Matriz cenário → teste

| Cenário | Teste | Mecanismo |
|---|---|---|
| Race condition (update concorrente) | `RedisSessionRaceIntegrationTest#concurrentUpdatesRebaseWithoutLostUpdates` | Dois nós carregam a mesma versão; commit sequencial força conflito CAS → rebase + retry; assert de nenhum update perdido e `CAS_RETRY` > 0 |
| Race condition (create concorrente) | `RedisSessionRaceIntegrationTest#concurrentCreatesWithSameIdConverge` | 2 threads criam o mesmo id; perdedor rebasa e converge em update (version 2) |
| Race condition (stress) | `RedisSessionRaceIntegrationTest#parallelUpdatesOnDistinctFieldsAllSurvive` | 6 nós em paralelo com retry nível-aplicação; estado final consistente |
| Stale session (read-through) | `RedisStaleSessionIntegrationTest#committedUpdateIsVisibleToNewReadersImmediately` | Snapshot por transação vs. leitura nova sempre fresca |
| Stale session (índice L1) | `RedisStaleSessionIntegrationTest#entityIndexInvalidationClearsOtherNodeLocalCacheViaPubSub` | Invalidação via PUBSUB limpa L1 do outro nó |
| Invalidation perdida | `RedisLostInvalidationIntegrationTest` (3 testes) | Subscriber derrubado antes do publish; ver TTL L2 e L1 sem bound |
| TTL incorreto | `RedisTtlMatrixIntegrationTest` (8 testes) | `PTTL` no Redis vs. política do realm (online, remember-me, offline ± max-lifespan, refresh, auth-session, login-failure, single-use) |
| Serialization incompatível (hash) | `RedisSerializationCompatibilityTest` (4 testes) | Campos ausentes/extras/corrompidos + hash sem `version` |
| Serialization incompatível (eventos) | `ClusterEventSerializerTest#unknownExtraFields*` + `RedisSerializationCompatibilityTest#malformedClusterEventDoesNotTakeSubscriberDown` | Campos novos no JSON e evento malformado no canal |
| Redis disconnect | `RedisDisconnectIntegrationTest` (3 testes) | pause/unpause (freeze) e crash via SIGKILL + start; fail-fast, reconnect Lettuce, integridade |

## Achados caracterizados (comportamento atual documentado pelos testes)

1. **L1 do entity index cache não tem TTL próprio.** Invalidation perdida deixa o L1
   stale por tempo indeterminado até `clearLocal()`/restart; o TTL do L2 só protege
   nós com L1 frio. Candidato a melhoria: `clearLocal()` no reconnect do subscriber.
2. **Create concorrente converge em overwrite.** O perdedor do conflito de create
   rebasa sobre o hash existente e seu retry vira update — ambos os commits têm
   sucesso (não há erro nem duplicação).
3. **Campo `version` corrompido quebra a leitura** da sessão com
   `NumberFormatException` (não degrada para miss).
4. **Hash sem campo `version` é adotado** no primeiro write (vira version 1).
5. **Eventos de cluster com campos desconhecidos são rejeitados por inteiro**
   (`FAILS_ON_UNKNOWN_PROPERTIES` ligado): em cluster multi-versão, mensagens de uma
   versão mais nova são descartadas pelos nós antigos. Candidato a melhoria:
   `FAILS_ON_UNKNOWN_PROPERTIES=false` no mapper.
6. **Restart do Redis sem persistência degrada para miss** (sessões somem, sem erro)
   e o provider volta a escrever imediatamente.

## Notas de infra dos testes de chaos

- **Porta fixa no container dedicado.** Em alguns hosts Docker (ex.: Docker
  Desktop/Windows) o `docker stop` + `docker start` **reatribui a porta mapeada
  dinâmica**, o que mascararia um falso fallo de reconnect. Por isso o
  `RedisDisconnectIntegrationTest` usa `addFixedExposedPort` (porta fixa sobrevive
  ao restart).
- **`stop` vs `kill`.** Um `docker stop` (SIGTERM) dá ao Redis a chance de salvar o
  RDB, o que esconderia a perda de dados. Para modelar crash sem persistência o teste
  usa `killContainerCmd` (SIGKILL) + `startContainerCmd`.
- **Reconnect agressivo só no teste.** O cliente do teste usa
  `reconnectDelay` constante de 500ms e `REJECT_COMMANDS` enquanto desconectado, para
  a janela de outage ficar curta e determinística. Em produção o Lettuce usa backoff
  exponencial padrão (até 30s) — ver `LettuceRedisClientSupport`.

## Fora do escopo atual (Fase 2)

| Cenário | Por quê | O que precisaria |
|---|---|---|
| Restart inconsistente (crash do Keycloak entre HSETEX e índices) | Exige harness E2E com processo Keycloak real | Testcontainers Keycloak + jar `-withdeps` |
| Redis failover (Sentinel) | Topologia Sentinel em Testcontainers ainda não existe no projeto; `docker-compose.sentinel.yml` não tem réplica | master + réplica + 3 sentinels via `GenericContainer`, tag/job dedicado |
| Cluster split | Redis Cluster 6 nós + injeção de partição; mais flaky | Toxiproxy ou network disconnect, tag/job dedicado |
| Keycloak upgrade | Smoke E2E versão N → N+1 | Harness E2E + cache de imagem no CI |
