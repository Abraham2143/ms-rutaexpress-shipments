# Despliegue de Shipments en EC2

Aplicación Java 21 / Spring Boot 4.1.1, puerto predeterminado 8081.
El host previsto es Amazon Linux 2023 con Docker Engine y Compose v2
instalados y activos. La imagen runtime usa Linux Temurin JRE; no necesita
Java ni Maven instalados en EC2. No incluye Oracle local ni servicios extra.

## Variables

Mantener `.env`, `compose.yml` y `wallet/` en el directorio de despliegue,
fuera del repositorio clonado. `.env` debe tener permisos `600`. Ejemplo de
estructura (los marcadores se reemplazan manualmente, no son credenciales):

```dotenv
SERVER_PORT=8081
DB_URL=jdbc:oracle:thin:@rutaexpress_medium?TNS_ADMIN=/opt/oracle/wallet
DB_USERNAME=<usuario-oracle>
DB_PASSWORD=<password-oracle>
AZURE_ISSUER_URI=https://login.microsoftonline.com/86277ca6-e8ef-4000-96b5-190477b8912d/v2.0
AZURE_AUDIENCE=af669402-d208-48a2-bed7-56d4a6f3371d
CATALOG_BASE_URL=http://<IP-PRIVADA-CATALOG>:8082
```

Issuer y audience son identificadores públicos suministrados por el proyecto;
no hay Client Secret. Los secretos solo se proporcionan en ejecución.
Si una contraseña contiene caracteres especiales, respeta las reglas de
comillas/interpolación de `env_file` de Compose, especialmente `$` y `#`.

| Variable opcional | Valor predeterminado / significado |
| --- | --- |
| `SERVER_PORT` | `8081`; mantenerlo para el mapeo propuesto. |
| `AZURE_JWK_SET_URI` | Vacío: discovery OpenID a partir de `AZURE_ISSUER_URI`. Puede fijarse al `jwks_uri` oficial del tenant; issuer/audience siguen validándose. |
| `JPA_DDL_AUTO` | `update`, estrategia académica existente. `validate` cuando el esquema esté provisionado. |
| `CATALOG_CAPACITY_PATH` | `/api/catalog/services/{serviceId}/capacity/decrement`. |
| `CATALOG_CONNECT_TIMEOUT` | `3s`. |
| `CATALOG_READ_TIMEOUT` | `10s`. |

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `AZURE_ISSUER_URI`, `AZURE_AUDIENCE`
son necesarios. `CATALOG_BASE_URL` debe fijarse en EC2; el fallback
`http://localhost:8082` se conserva solo para desarrollo fuera de Docker.
En EC2 separadas usar IP privada/DNS privado de Catalog, no `http://catalog`
salvo que la infraestructura ya resuelva ese nombre entre hosts.

## Oracle Wallet

Extraer la Wallet real en `./wallet` fuera del repositorio. No montarla desde
un ZIP. `tnsnames.ora` debe contener el alias `rutaexpress_medium` y usar rutas
válidas en Linux. Si `sqlnet.ora` o `ojdbc.properties` contienen rutas absolutas,
adaptarlas en la copia externa a `/opt/oracle/wallet`.

El contenedor ejecuta como UID/GID `10001:10001`; el bind mount conserva los
permisos del host. En EC2, desde el directorio de despliegue y sobre esa
carpeta concreta:

```sh
sudo chown -R 10001:10001 ./wallet
sudo find ./wallet -type d -exec chmod 700 {} \;
sudo find ./wallet -type f -exec chmod 400 {} \;
chmod 600 .env
```

No usar `chmod 777`. En hosts con SELinux enforcing puede requerirse ajustar
el contexto de ese bind mount según la política del host; no desactivar SELinux.
El montaje es read-only: `./wallet:/opt/oracle/wallet:ro`.

`ojdbc17` y `oraclepki` usan la misma versión administrada por Spring Boot
(23.26.3.0.0 en el build verificado). Oracle documenta que `osdt_core` y
`osdt_cert` no se requieren con 23ai; no se mezclan librerías de generaciones
anteriores. [Guía oficial Oracle](https://www.oracle.com/database/technologies/maven-central-guide.html).
H2 tiene exclusivamente scope `test`.

## Build y ejecución

Desde el repositorio:

```sh
./mvnw clean test
./mvnw clean package
docker build -t rutaexpress-shipments .
```

El Dockerfile ejecuta `mvn clean package` con tests en la etapa Maven/Java 21.
La etapa final contiene únicamente el JAR de la aplicación sobre Java 21 JRE;
expone 8081 y no copia Wallet, `.env`, fuentes ni Maven. `.dockerignore`
permite solo las entradas del build y excluye archivos sensibles incluso
anidados en `src`, ZIP, `target`, `.git` e `.idea`.

Alternativa directa, ejecutada desde el directorio externo que contiene la Wallet:

```sh
# Exportar antes las variables reales en el shell.
docker run --rm --name rutaexpress-shipments -p 8081:8081 \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD \
  -e AZURE_ISSUER_URI -e AZURE_AUDIENCE -e CATALOG_BASE_URL \
  --mount "type=bind,source=$(pwd)/wallet,target=/opt/oracle/wallet,readonly" \
  rutaexpress-shipments
```

Compose equivalente, guardado **fuera del repo** al lado de `.env` y `wallet/`:

```yaml
services:
  shipments:
    build:
      context: ./ms-rutaexpress-shipments
    image: rutaexpress-shipments
    container_name: rutaexpress-shipments
    ports:
      - "8081:8081"
    env_file:
      - .env
    volumes:
      - ./wallet:/opt/oracle/wallet:ro
    restart: unless-stopped
```

```sh
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 shipments
curl -i http://localhost:8081/actuator/health
curl -i http://localhost:8081/api/shipments
```

Resultado esperado: health `200 {"status":"UP"}` con Oracle accesible;
API sin JWT `401`. Si Oracle está caído, health puede devolver `503` sin
detalles sensibles; no se oculta el fallo para simular disponibilidad.

## Red y comprobaciones antes de habilitar tráfico

1. Permitir entrada TCP 8081 a la EC2 de Shipments desde el Security Group
   del BFF (y desde el componente que efectúe el health check, si corresponde).
   No abrir 8081 a Internet. BFF usará `http://<IP-PRIVADA-SHIPMENTS>:8081`.
2. Permitir acceso de Shipments al puerto 8082 privado de Catalog y a los
   endpoints/puertos de Oracle indicados en `tnsnames.ora`, con las ACL/redes
   autorizadas en Autonomous Database.
3. Permitir DNS y salida HTTPS para discovery/JWKS de Entra. En una subred
   privada, preparar la salida de red que corresponda. Build también necesita
   acceso a Maven Central y al registro de imágenes.
4. Configurar el health check como `GET /actuator/health`, puerto 8081,
   éxito HTTP 200. Es el único endpoint público; no muestra componentes.
5. Confirmar en Entra tokens de acceso con `iss` y `aud` configurados y roles
   `ADMIN`, `DISPATCHER` o `CLIENT`. No usar ID tokens ni Client Secret para
   llamadas internas. Firmas RS256, issuer, vigencia y audience se validan.
6. Confirmar el [contrato Catalog](CATALOG-CONTRACT.md), especialmente el
   descuento idempotente, antes de aceptar envíos. Probar la llamada a través
   del BFF con un token real sin imprimirlo en logs.

No se crea infraestructura AWS ni se modifica BFF/Catalog. No se requiere AWS SDK.

## Seguridad y límites de verificación

Revisión del árbol versionado y todo el historial disponible localmente:
5 commits y 39 blobs, refs/reflogs sin objetos inalcanzables reportados por
`git fsck`. No se encontraron Wallets, `.env`, contraseñas reales, claves AWS,
JWT literales ni claves privadas. Coincidencias revisadas: placeholders de
entorno, wrapper Maven y contraseña vacía de H2 en tests. Las claves RSA de
prueba se generan en memoria; no se versionan claves ni tokens.

Esto no inspecciona copias remotas borradas, otros repositorios ni secretos
fuera de Git. Si apareciera una credencial comprometida en otra copia, hay
que revocarla/rotarla; borrar un archivo actual no sanea el historial.

El entorno de revisión tiene JDK 26 (compilación `release 21`) y Maven wrapper,
pero no Docker. El build de la imagen con Java 21 y las conexiones reales a
Oracle/Entra/Catalog quedan por verificar en EC2 o en un host con Docker.

Resultados de esta revisión (2026-09-14):

- `./mvnw.cmd clean test`: BUILD SUCCESS, 66 tests, 0 fallos, 0 errores.
- `./mvnw.cmd clean package`: BUILD SUCCESS, 66 tests, JAR ejecutable generado.
- Docker CLI no disponible; no se pudo validar `docker build`.
- `mvnw` marcado como ejecutable en Git para Linux (cambio de modo 100644 a
  100755, sin cambiar contenido; preparado en el índice Git).
- Pendientes: build Docker/Java 21, Oracle/Wallet real, JWT Entra real y
  confirmación del contrato idempotente de Catalog. No se certifica todavía
  el despliegue integrado.
