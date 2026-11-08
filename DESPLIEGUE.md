# Desplegar Red Surco en la nube (GitHub + Neon + Render)

El sistema funciona con **dos bases de datos**:

- **Local:** SQLite (archivo `redsurco.db`). No necesitas hacer nada, corre así por defecto.
- **Nube:** **PostgreSQL** cuando existe la variable de entorno `DATABASE_URL`. Es la que se usa en Render con **Neon**, para que los datos **no se borren** al reiniciarse el servidor.

El mismo código sirve para las dos: si hay `DATABASE_URL`, usa PostgreSQL; si no, usa SQLite.

---

## Paso 1 — Subir el código a GitHub

Ya tienes el repositorio. Cada vez que cambie algo:

```
git add .
git commit -m "cambios"
git push
```

> La base `redsurco.db` **no se sube** (está en `.gitignore`), porque tiene datos personales.

---

## Paso 2 — Crear la base en Neon

1. Entra a **https://neon.tech** y crea un proyecto (ej. `red-surco`).
2. Neon te da una **cadena de conexión** parecida a:
   ```
   postgresql://usuario:clave@ep-xxxx.sa-east-1.aws.neon.tech/neondb?sslmode=require
   ```
3. **Cópiala.** La vas a pegar en Render en el Paso 3.

No tienes que crear tablas a mano: el sistema las crea solo al arrancar.

---

## Paso 3 — Crear el servicio en Render

1. Entra a **https://render.com** → **New** → **Web Service**.
2. Conecta tu repositorio de GitHub (`red-surco-web`).
3. Render detecta el **Dockerfile** automáticamente (Environment = Docker).
4. En **Environment / Variables**, agrega una variable:
   - **Key:** `DATABASE_URL`
   - **Value:** la cadena de conexión de Neon (la del Paso 2).
   - (El puerto lo pone Render solo con la variable `PORT`; no toques eso.)
5. **Create Web Service** y espera a que termine el *deploy*.

Al terminar, Render te da una URL tipo `https://red-surco-web.onrender.com`. Ábrela.

- **Primer login:** usuario `admin`, contraseña `admin123`.
- **Cambia esa contraseña de inmediato** en la pestaña **Usuarios**.

---

## Paso 4 — Cargar tus datos en Neon (una sola vez)

Al primer arranque el sistema crea las tablas **vacías**. Para pasar tus 375 personas,
grupos, cursos y metas:

1. Entra a tu proyecto en **Neon** → pestaña **SQL Editor**.
2. Abre el archivo **`datos-neon.sql`** (te lo entrego **aparte**, NO va en el repositorio
   porque tiene datos personales), copia todo su contenido y pégalo en el editor.
3. Dale **Run**. Listo: tus datos quedan cargados en la nube.

> Ese archivo se generó desde tu `redsurco.db` actual. **No lo subas a GitHub** (el `.gitignore`
> ya lo bloquea). Si luego agregas más gente en local y quieres volver a exportar, avísame y te
> regenero el archivo.

---

## Resumen de quién hace qué

- **GitHub** = guarda el código.
- **Render** = corre el programa (lee `DATABASE_URL` y `PORT`).
- **Neon** = guarda los datos (siempre, con respaldos automáticos).

## Notas

- En la nube, el botón **"Descargar respaldo"** avisa que los respaldos los hace **Neon**
  automáticamente. Para una copia legible, usa **Exportar Excel**.
- En el plan **gratis** de Render el servicio se **duerme** tras ~15 min sin uso; la primera
  visita después tarda ~30–60 s en despertar. Los datos nunca se pierden (están en Neon).
- Para correr en tu PC como antes, simplemente **no** definas `DATABASE_URL`: usará el
  `redsurco.db` local.
