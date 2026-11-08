# Red Surco — versión Web (local)

Versión **web** del sistema: se abre en el **navegador** en `http://localhost:8080`,
con **login** y varios usuarios a la vez. Reutiliza la misma base **SQLite** (`redsurco.db`,
ya incluida con los 386 inscritos). Hecha con el **servidor web que trae Java** + Maven,
sin frameworks externos.

---

## Requisitos

- **JDK 17 o superior** (un JDK completo; incluye el servidor web `jdk.httpserver`).
- **Maven** (o NetBeans, que lo trae).
- Internet solo la primera vez (Maven descarga el conector de SQLite).

---

## Cómo ejecutar

Desde la carpeta `red-surco-web/`:

```bash
mvn clean package
java -jar target/red-surco-web.jar
```

o, más rápido para probar:

```bash
mvn clean compile exec:java
```

En NetBeans: **File ▸ Open Project** sobre la carpeta `red-surco-web`, y **Run** (F6).

Luego abre el navegador en:

```
http://localhost:8080
```

**Login inicial:** usuario `admin`, contraseña `admin123`.

La base `redsurco.db` ya viene con los datos cargados. Debe estar en la carpeta desde
donde ejecutas (la raíz del proyecto en NetBeans, o junto al `.jar`).

---

## Qué tiene

- **Login** con sesión (contraseñas guardadas con hash PBKDF2, no en texto plano).
- **Roles**: **Administrador** y **Usuario**.
  - El **Administrador** ve y usa todo, incluida la pestaña **Usuarios** para crear,
    editar, eliminar cuentas y restablecer contraseñas.
  - El **Usuario** hace el registro (personas, cursos, grupos, resumen, exportar) pero
    **no** ve la pestaña Usuarios.
- **Personas**: listar, **buscar**, crear, editar y eliminar; con grupo, tipo de documento
  (DNI = 8 números, Carné de extranjería = 9 números, u **Otro documento** libre), **Reset (encuentro)** sí/no y cursos por casillas.
  - El **grupo se escribe** (con sugerencias de los existentes): si no existe, se crea solo.
  - **Filtros rápidos**: *Sin grupo*, *Sin curso*, *Sin Reset* y *Fueron al Reset*.
  - En **Resumen**, las tarjetas *Sin ningún curso* y *Sin ningún Reset* son **clickeables**:
    llevan directo a la lista de esas personas para identificarlas al instante.
- **Cursos** y **Grupos** (con líder): crear, editar, eliminar.
- **Seguimiento de conexión**: cada persona tiene **Conectado** (sí/no) y **Observaciones**.
  Hay **7 días** desde el registro para conectarla; la lista muestra los días restantes (o *Vencido*).
  Pasado el plazo, la casilla **se bloquea** para usuarios normales y **solo un administrador** puede
  cambiarla; **cada cambio queda registrado** (quién, cuándo y qué). Filtro *Por conectar*.
- **Resumen**: indicadores (Personas, Grupos, Sin ningún curso, **Sin ningún Reset**,
  **Nuevos esta semana**, **Ingresados el fin de semana**), inscritos por curso, cursos por
  persona y personas por grupo. *(Los contadores de "semana" y "fin de semana" usan la fecha
  de registro; empiezan a contar desde que agregas o importas gente nueva.)*
- **Exportar Excel** e **Importar Excel/CSV** (ver abajo).
- Interfaz en **morado**, con tu **logo** en el login.

### Importar (actualizar la base con un archivo)

En la pestaña **Importar** (solo administrador) subes un **Excel (.xlsx)** o **CSV** con una
fila de encabezados. Columnas reconocidas: **Grupo, Líder, Tipo, Documento, Nombres completos,
Celular, Correo, Cursos estudiados, Reset** (cursos separados por coma). Obligatorias:
**Documento** y **Nombres completos**.

- Las personas se identifican por su **documento**: si ya existen, se **actualizan**; si no, se **agregan**.
- Los **grupos** y **cursos** que no existan se **crean** automáticamente. El emparejamiento
  es **tolerante a errores de tipeo**: ignora espacios de más, mayúsculas/minúsculas y acentos,
  así "DISCIPULADO 1" y "DISCIPULADO  1" se toman como el mismo (no se duplica).
- Es **aditivo**: agrega los cursos del archivo a cada persona y **no borra** nada de lo anterior.

Así, para cargar otro mes, exportas la lista a Excel/CSV con esas columnas y la subes: tu base
se actualiza. *(El PDF no se importa automáticamente; conviértelo a Excel/CSV primero.)*

## Varios usuarios a la vez

El servidor escucha en todas las interfaces, así que otras personas en la **misma red**
pueden entrar desde `http://LA-IP-DE-TU-PC:8080` (p. ej. `http://192.168.1.50:8080`).
Puede que debas permitirlo en el **firewall** de Windows la primera vez.

SQLite en modo WAL soporta bien lecturas simultáneas y escrituras ocasionales, que es
lo típico de un registro como este.

---

## Seguridad (importante)

Esta versión está pensada para uso **local o en red interna**. Si la vas a poner en
**internet**, antes conviene:

- Poner **HTTPS** (certificado) delante del servidor.
- Entrar como `admin` → pestaña **Usuarios**, crear las cuentas reales (Administrador o
  Usuario) y **cambiar la contraseña de `admin`** (o crear otro administrador y borrar el inicial).
- Pasar la base a **PostgreSQL** si habrá muchas escrituras al mismo tiempo.

> Al primer arranque se crea el usuario **admin / admin123** (Administrador). Desde la
> pestaña **Usuarios** administras todas las cuentas. El sistema no deja quedarse sin
> ningún administrador ni que te elimines a ti mismo.

---

## Estructura

```
red-surco-web/
├── pom.xml
├── redsurco.db                 (datos — la misma base del escritorio)
└── src/main/java/com/redsurco/web/
    ├── Server.java             Servidor HTTP + todas las rutas y páginas
    ├── Db.java                 Conexión SQLite (una por petición) + esquema
    ├── Auth.java               Login: sesiones + hash de contraseñas
    ├── Html.java               Plantillas y estilos (morado)
    ├── model/                  Persona, Curso, Grupo
    ├── dao/                    PersonaDao, CursoDao, GrupoDao, ResumenDao
    └── export/XlsxWriter.java  Generador de Excel (sin librerías)
```

---

## Del local a internet (siguiente paso)

Cuando quieras publicarlo para acceso desde cualquier lado, el camino natural es
subir este jar a un servidor (un VPS, o un hosting que corra Java), ponerle HTTPS, y
—si crece el uso— migrar a PostgreSQL. El modelo de datos y las pantallas se mantienen.
