# Instalação

## Requisitos

- JDK 17+
- Maven 3.9+
- Keycloak **26.7.1**
- Redis ou Valkey acessível pelos nós Keycloak

## Build

```bash
mvn clean package
```

Para pular testes:

```bash
mvn clean package -DskipTests
```

Artefato instalável:

```
target/keycloak-cache-redis-1.0-SNAPSHOT-withdeps.jar
```

O JAR `-withdeps` inclui Lettuce e Reactor (Netty vem do Keycloak).

## Deploy no Keycloak

1. Copie o JAR para o diretório `providers/` da instalação Keycloak.
2. Configure as variáveis mínimas (veja [Configuração](configuration.md)):

   ```bash
   KC_CACHE=local
   KC_COMMUNITY_REDIS_CACHE_ENABLED=true
   KC_SPI_REDIS_CONNECTION_DEFAULT_MODE=standalone
   KC_SPI_REDIS_CONNECTION_DEFAULT_NODES=redis:6379
   KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX=kc
   ```

3. Reinicie o Keycloak (ou reconstrua a imagem, se usar container).

## Smoke local (Docker Compose)

O repositório inclui um ambiente de desenvolvimento com Keycloak + Valkey:

```bash
mvn clean package -DskipTests
docker compose up
```

| Serviço | URL / porta |
|---------|-------------|
| Keycloak | http://localhost:8080 (`admin` / `admin`) |
| Valkey | `localhost:6379` |

Checklist sugerido:

- Login / logout
- Refresh token
- Sessão offline
- Admin Console
- Persistência após `docker compose restart keycloak`

### Multi-nó (2 Keycloak + Valkey)

```bash
mvn clean package -DskipTests
docker compose -f docker-compose.multinode.yml up
```

- Nó 1: http://localhost:8080
- Nó 2: http://localhost:8081

Sessões ficam no Redis; sticky session **não** é necessária. Detalhes em [Clustering](clustering.md).

### Sentinel (exemplo)

```bash
docker compose -f docker-compose.sentinel.yml up
```

Configure o Keycloak com `MODE=sentinel`, lista de sentinels em `NODES` e `MASTER_NAME=mymaster`. Veja [Conexão Redis](connection.md).

## Testes

```bash
mvn test
```

Opcionalmente, aponte para um Redis já em execução:

```bash
REDIS_TEST_URI=redis://127.0.0.1:6380 mvn test
```

A suíte cobre CAS / `MapEntity` / `RedisKeySpace`, configuração e chaves de authz, e integração Redis de authz (Testcontainers).
