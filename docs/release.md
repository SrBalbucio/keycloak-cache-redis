# Release (Woodpecker + GitHub)

Pipeline de deploy de release via [Woodpecker CI](https://woodpecker-ci.org/), publicando o JAR em **GitHub Releases**.

## Arquivos

| Pipeline | Quando | Função |
|----------|--------|--------|
| [`.woodpecker/ci.yml`](../.woodpecker/ci.yml) | push/PR em `master`/`main` | `./mvnw verify` com Redis de serviço |
| [`.woodpecker/release.yml`](../.woodpecker/release.yml) | tag `v*` | versiona o POM, testa, publica Release |

Há também CI em GitHub Actions ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)).

## Segredo necessário

No repositório Woodpecker (ou org), crie o secret:

| Nome | Valor |
|------|--------|
| `github_token` | PAT ou fine-grained token GitHub com permissão **Contents: Read and write** (criar releases e upload de assets) |

O forge do Woodpecker deve ser o GitHub deste repositório (`CI_FORGE_URL` / `CI_REPO` apontando para o repo correto).

## Como publicar

1. Atualize [`CHANGELOG.md`](../CHANGELOG.md) (mova itens de Unreleased para a versão).
2. No `master` atualizado:

```bash
git tag -a v1.0.0 -m "v1.0.0"
git push origin v1.0.0
```

3. O pipeline:
   - define `project.version` a partir da tag (`v1.0.0` → `1.0.0`);
   - roda `./mvnw verify` contra Redis;
   - empacota `keycloak-cache-redis-<version>-withdeps.jar`;
   - cria a GitHub Release com notas + checksum SHA-256.

Tags com hífen após o número (ex.: `v1.0.0-rc.1`) são publicadas como **pre-release**.

## Artefato

Use no Keycloak:

```
providers/keycloak-cache-redis-<version>-withdeps.jar
```
