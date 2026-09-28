# Documentação — keycloak-cache-redis

Extensão para Keycloak **26.7.1** que substitui caches distribuídos de sessão (Infinispan) por **Redis/Valkey**, e adiciona cache-aside para Authorization Services.

## Índice

| Documento | Conteúdo |
|-----------|----------|
| [Visão geral](overview.md) | Propósito, arquitetura e o que está implementado |
| [Instalação](installation.md) | Build, deploy e smoke local com Docker |
| [Configuração](configuration.md) | Variáveis de ambiente e propriedades |
| [Conexão Redis](connection.md) | SPI de conexão, modos standalone / sentinel / cluster |
| [Sessões](sessions.md) | User sessions, auth sessions, login failures, single-use |
| [Authorization](authorization.md) | Cache-aside das Authorization Services |
| [Clustering](clustering.md) | Coordenação multi-nó via PUBSUB |
| [Matriz de eventos](cluster-event-matrix.md) | eventKey → emissor/listener → cache local (Fase 0) |
| [Baseline multinó](cluster-baseline.md) | Roteiro de medição + SLO do cluster bus (Fase 0) |
| [Métricas](metrics.md) | Contadores e latência Lettuce |
| [Limitações](limitations.md) | Restrições conhecidas e comportamento esperado |
| [Release](release.md) | Pipeline Woodpecker → GitHub Releases |
| [Entity cache](entity-cache.md) | MVP de índices user/realm/client no Redis |

Para um resumo operacional rápido, veja também o [README](../README.md) na raiz do repositório.
