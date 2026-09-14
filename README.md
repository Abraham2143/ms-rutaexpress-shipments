# ms-rutaexpress-shipments

## Docker

Construcción multi-stage con Maven 3.9 y Java 21; runtime Eclipse Temurin
21 JRE (sin Maven ni código fuente), ejecutado como UID/GID `10001:10001`.
El build ejecuta los tests; no necesita Oracle ni una Wallet real.

```sh
docker build -t ms-rutaexpress-shipments .
```

Configura las variables en tu entorno antes de ejecutar (ejemplo Bash):

```sh
export DB_URL='jdbc:oracle:thin:@rutaexpress_medium?TNS_ADMIN=/opt/oracle/wallet'
export CATALOG_BASE_URL='http://catalog:8082'
# Define DB_USERNAME, DB_PASSWORD, AZURE_ISSUER_URI y AZURE_JWK_SET_URI
# con los valores reales de tu entorno, sin guardarlos en el repositorio.
export WALLET_PATH='/ruta/absoluta/a/la/wallet'

docker run --rm --name ms-rutaexpress-shipments \
  -p 8081:8081 \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD \
  -e AZURE_ISSUER_URI -e AZURE_JWK_SET_URI \
  -e CATALOG_BASE_URL \
  --mount "type=bind,source=${WALLET_PATH},target=/opt/oracle/wallet,readonly" \
  ms-rutaexpress-shipments
```

En PowerShell define variables con `$env:DB_URL = '...'`, usa
`$env:WALLET_PATH` en el montaje y ejecuta el comando en una línea o usa
el acento grave para continuarlo, en lugar de `\`.

| Variable | Uso |
| --- | --- |
| `DB_URL` | JDBC Oracle con `TNS_ADMIN=/opt/oracle/wallet`. |
| `DB_USERNAME` | Usuario Oracle. |
| `DB_PASSWORD` | Contraseña Oracle. |
| `AZURE_ISSUER_URI` | Issuer exacto del JWT de Entra ID. |
| `CATALOG_BASE_URL` | URL HTTP de Catalog accesible desde el contenedor. |
| `AZURE_JWK_SET_URI` | URL `jwks_uri` publicada en los metadatos OpenID del tenant. Necesaria con la configuración de seguridad actual: si se omite, el decoder intenta consultar localhost:8080. |

La aplicación conserva sus validaciones JWT, roles y endpoints. No se
configuran credenciales durante el build. `JPA_DDL_AUTO` es opcional y
conserva el valor predeterminado `update`; puede usarse `validate` cuando
el esquema ya esté aprovisionado. Mantén `SERVER_PORT` sin definir para usar 8081.

### Oracle Wallet

La Wallet se monta únicamente en runtime, en `/opt/oracle/wallet`, como
solo lectura. Nunca se copia dentro de la imagen. El alias
`rutaexpress_medium` debe existir en el `tnsnames.ora` de la Wallet y sus
referencias a archivos deben ser válidas dentro del contenedor, sin rutas
absolutas de Windows. El usuario UID 10001 necesita permisos de lectura
y acceso al directorio montado. El JAR incluye `oraclepki` para soportar
la Wallet, con versión administrada por Spring Boot.

`.dockerignore` limita el contexto a los archivos de construcción y excluye
Wallets y formatos habituales de secretos incluso dentro de `src`.
`.gitignore` también los excluye; no elimina archivos ya versionados.

### Catalog y conectividad

`CATALOG_BASE_URL` se aplica al cliente HTTP existente. Se conserva
`http://localhost:8082` como fallback para desarrollo fuera de Docker.
Dentro de Docker, localhost apunta al propio contenedor: define siempre
la URL de Catalog. Para usar `http://catalog:8082`, conecta ambos
contenedores a la misma red Docker y asigna a Catalog el nombre/alias
`catalog`; añade `--network NOMBRE_RED` al comando anterior. No se incluye
Compose ni se modifica Catalog. El contenedor necesita conectividad a
Oracle, Catalog y al endpoint HTTPS de claves de Entra ID.

### Validación

```sh
mvn clean test
mvn clean package
docker build -t ms-rutaexpress-shipments .
```

Si Maven no está instalado, usa `./mvnw` o `./mvnw.cmd` en Windows.
El build Docker requiere Docker Engine activo y acceso a los registros
de imágenes y repositorios Maven. La conexión real y autenticación se
validan en runtime con las variables y Wallet del entorno.
