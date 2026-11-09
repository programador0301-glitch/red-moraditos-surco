package com.redsurco.web;

import com.redsurco.web.dao.*;
import com.redsurco.web.export.XlsxWriter;
import com.redsurco.web.importar.Importador;
import com.redsurco.web.importar.XlsxReader;
import com.redsurco.web.model.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Servidor web local de Red Surco. Usa el servidor HTTP incluido en el JDK,
 * la base SQLite (redsurco.db) y login por sesión. Arranca en http://localhost:8080
 */
public class Server {

    /** Puerto: usa la variable de entorno PORT (Render/hosting) o 8080 para local. */
    static int puerto() {
        String p = System.getenv("PORT");
        try { return (p != null && !p.isBlank()) ? Integer.parseInt(p.trim()) : 8080; }
        catch (NumberFormatException e) { return 8080; }
    }

    public static void main(String[] args) throws Exception {
        Db.init();
        Db.respaldoDiario();   // copia de seguridad del día (si falta)
        Auth.crearAdminSiHaceFalta();

        int puerto = puerto();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", puerto), 0);
        server.createContext("/", Server::router);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("Red Surco Web en  ->  http://localhost:" + puerto);
        System.out.println("(Ctrl+C para detener)");
    }

    /** Punto único de enrutamiento. */
    static void router(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String metodo = ex.getRequestMethod();

            if (path.equals("/style.css")) { enviar(ex, 200, "text/css; charset=utf-8", Html.css().getBytes(StandardCharsets.UTF_8)); return; }
            if (path.equals("/logo.png"))  { enviar(ex, 200, "image/png", logo()); return; }
            if (path.equals("/login"))  { rutaLogin(ex, metodo); return; }
            if (path.equals("/logout")) { Auth.cerrarSesion(cookie(ex, "SID")); redirigir(ex, "/login"); return; }

            // de aquí para abajo, requiere sesión
            String tok = cookie(ex, "SID");
            String usuario = Auth.usuarioDeSesion(tok);
            if (usuario == null) { redirigir(ex, "/login"); return; }
            String rolSesion = Auth.rolDeSesion(tok);
            boolean admin = com.redsurco.web.model.Usuario.ADMIN.equals(rolSesion);
            boolean soloRegistro = com.redsurco.web.model.Usuario.REGISTRO.equals(rolSesion);

            // El rol "Conectar" solo puede entrar a Personas y a su propio resumen
            if (soloRegistro && !raiz(path).equals("/personas") && !raiz(path).equals("/mi-resumen")) {
                redirigir(ex, "/personas"); return;
            }

            switch (raiz(path)) {
                case "/" -> redirigir(ex, "/personas");
                case "/personas" -> rutaPersonas(ex, metodo, path, usuario, admin, soloRegistro);
                case "/mi-resumen" -> rutaMiResumen(ex, usuario);
                case "/cursos"   -> rutaCursos(ex, metodo, path, usuario, admin);
                case "/grupos"   -> rutaGrupos(ex, metodo, path, usuario, admin);
                case "/resumen"  -> rutaResumen(ex, usuario, admin);
                case "/metas"    -> rutaMetas(ex, metodo, path, usuario, admin);
                case "/papelera" -> rutaPapelera(ex, metodo, path, usuario, admin);
                case "/respaldo" -> rutaRespaldo(ex, admin);
                case "/exportar" -> rutaExportar(ex);
                case "/importar" -> {
                    if (!admin) { redirigir(ex, "/personas"); return; }   // solo administrador
                    rutaImportar(ex, metodo, usuario);
                }
                case "/usuarios" -> {
                    if (!admin) { redirigir(ex, "/personas"); return; }   // solo administrador
                    rutaUsuarios(ex, metodo, path, usuario);
                }
                default -> enviarHtml(ex, 404, Html.page("No encontrado", usuario, admin, "", "<h1>Página no encontrada</h1>"));
            }
        } catch (Exception e) {
            try {
                enviarHtml(ex, 500, "<h1>Error</h1><pre>" + Html.esc(String.valueOf(e.getMessage())) + "</pre>");
            } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    // =================== LOGIN ===================

    static void rutaLogin(HttpExchange ex, String metodo) throws IOException {
        if (metodo.equals("POST")) {
            Map<String, List<String>> f = leerForm(ex);
            String u = val(f, "usuario"), c = val(f, "clave");
            String rol = Auth.autenticar(u, c);
            if (rol != null) {
                String token = Auth.crearSesion(u, rol);
                ex.getResponseHeaders().add("Set-Cookie", "SID=" + token + "; Path=/; HttpOnly; SameSite=Lax");
                redirigir(ex, "/personas");
            } else {
                enviarHtml(ex, 401, Html.loginPage("Usuario o contraseña incorrectos."));
            }
        } else {
            enviarHtml(ex, 200, Html.loginPage(null));
        }
    }

    // =================== PERSONAS ===================

    static void rutaPersonas(HttpExchange ex, String metodo, String path, String usuario, boolean admin) throws Exception {
        rutaPersonas(ex, metodo, path, usuario, admin, false);
    }

    static void rutaPersonas(HttpExchange ex, String metodo, String path, String usuario,
                             boolean admin, boolean soloRegistro) throws Exception {
        Map<String, String> q = query(ex);
        try (Connection con = Db.open()) {
            PersonaDao pdao = new PersonaDao(con);
            CursoDao cdao = new CursoDao(con);
            GrupoDao gdao = new GrupoDao(con);
            String grupoUsuario = Auth.grupoDeUsuario(con, usuario);
            String grupoFijo = (soloRegistro && grupoUsuario != null && !grupoUsuario.isBlank()) ? grupoUsuario : "";

            if (path.equals("/personas/guardar") && metodo.equals("POST")) {
                Map<String, List<String>> f = leerForm(ex);
                String num = val(f, "num"), nombres = val(f, "nombres");
                if (num.isBlank() || nombres.isBlank()) { redirigir(ex, "/personas/form?err=1" + idParam(f)); return; }
                String idS = val(f, "id");
                String idQs = idS.isBlank() ? "" : "&id=" + idS;
                // Tipo de documento y validación de longitud
                String tipoStr = val(f, "tipo");
                String tipo = "CE".equals(tipoStr) ? Persona.CE : "OTRO".equals(tipoStr) ? Persona.OTRO : Persona.DNI;
                if (Persona.DNI.equals(tipo) && !num.matches("\\d{8}")) {
                    redirigir(ex, "/personas/form?err=3" + idQs); return;
                }
                if (Persona.CE.equals(tipo) && !num.matches("\\d{9}")) {
                    redirigir(ex, "/personas/form?err=4" + idQs); return;
                }
                Persona p = new Persona();
                if (!idS.isBlank()) p.id = Integer.parseInt(idS);
                p.tipoDocumento = tipo;
                p.numDocumento = num; p.nombresCompletos = nombres;
                p.celular = val(f, "celular"); p.correo = val(f, "correo");
                p.reset = !val(f, "reset").isBlank();
                p.observaciones = val(f, "observaciones");
                boolean conectadoForm = !val(f, "conectado").isBlank();
                // El grupo se escribe por nombre: si no existe, se crea.
                // Para "Conectar" con código propio, el grupo queda FIJO en su código.
                String g = !grupoFijo.isBlank() ? grupoFijo : val(f, "grupo");
                p.grupoId = g.isBlank() ? null : gdao.findOrCreate(g, "");

                // --- Seguimiento: bloqueo por plazo y registro de cambios ---
                Persona anterior = p.id == 0 ? null : pdao.findById(p.id);
                boolean conectadoAnterior = anterior != null && anterior.conectado;
                // Si venció el plazo y NO es admin, no puede cambiar la casilla: se conserva lo que había.
                boolean bloqueado = anterior != null && anterior.bloqueadoPara(admin);
                p.conectado = bloqueado ? conectadoAnterior : conectadoForm;

                boolean esNuevo = p.id == 0;
                if (esNuevo) p.registradoPor = usuario;   // queda registrado quién lo agregó
                try {
                    if (esNuevo) pdao.insert(p); else pdao.update(p);
                    List<Integer> cursos = new ArrayList<>();
                    for (String s : f.getOrDefault("curso", List.of())) cursos.add(Integer.parseInt(s));
                    pdao.setCursos(p.id, cursos);
                    // Registrar el cambio de estado de conexión
                    if (p.conectado != conectadoAnterior) {
                        boolean fueraDePlazo = anterior != null && anterior.tienePlazo() && anterior.diasRestantes() < 0;
                        String accion = p.conectado ? "Marcado CONECTADO" : "Marcado NO conectado";
                        if (admin && fueraDePlazo) accion += " (reactivado por admin, fuera de plazo)";
                        pdao.addLog(p.id, usuario, accion, p.observaciones);
                    }
                    String msg = (esNuevo ? "Registrado por " : "Actualizado por ") + usuario + ": " + p.nombresCompletos;
                    redirigir(ex, "/personas?ok=" + java.net.URLEncoder.encode(msg, "UTF-8"));
                } catch (RuntimeException e) {
                    redirigir(ex, "/personas/form?err=2" + (p.id > 0 ? "&id=" + p.id : ""));
                }
                return;
            }
            if (path.equals("/personas/eliminar") && metodo.equals("POST")) {
                if (soloRegistro) { redirigir(ex, "/personas"); return; }   // no puede eliminar
                Map<String, List<String>> f = leerForm(ex);
                pdao.eliminarSuave(Integer.parseInt(val(f, "id")), usuario);
                redirigir(ex, "/personas");
                return;
            }
            if (path.equals("/personas/form")) {
                Persona p = q.containsKey("id") ? pdao.findById(Integer.parseInt(q.get("id"))) : new Persona();
                if (p == null) p = new Persona();
                List<String[]> logs = p.id == 0 ? List.of() : pdao.logsDe(p.id);
                enviarHtml(ex, 200, Html.page("Persona", usuario, admin, soloRegistro, grupoFijo, "/personas",
                        formPersona(p, cdao.findAll(), gdao.findAll(), pdao.cursosDe(p.id), q.get("err"), admin, logs, List.of(), grupoFijo)));
                return;
            }
            // listado
            String filtro = q.getOrDefault("q", "");
            String vista = q.getOrDefault("vista", "");
            if (q.containsKey("curso") && !soloRegistro) {
                int cursoId = Integer.parseInt(q.get("curso"));
                List<Persona> lista = pdao.findByCurso(cursoId);
                String nombreCurso = cdao.findById(cursoId) != null ? cdao.findById(cursoId).etiqueta() : "curso";
                enviarHtml(ex, 200, Html.page("Personas", usuario, admin, "/personas",
                        listaPersonasCurso(lista, nombreCurso)));
                return;
            }
            List<Persona> lista = "ganados".equals(vista) ? pdao.findGanados() : pdao.findAll(filtro, vista);
            String saludo = soloRegistro ? saludoConectar(usuario, grupoFijo) : "";
            String flash = q.containsKey("ok")
                    ? "<div class=\"aviso ok\">✓ " + Html.esc(q.get("ok")) + "</div>" : "";
            enviarHtml(ex, 200, Html.page("Personas", usuario, admin, soloRegistro, grupoFijo, "/personas",
                    saludo + flash + listaPersonas(lista, filtro, vista, soloRegistro)));
        }
    }

    /** Saludo para el usuario con rol Conectar: su nombre, código/grupo y rol. */
    static String saludoConectar(String nombre, String grupo) {
        StringBuilder b = new StringBuilder("<div class=\"saludo\">");
        b.append("<div><span class=\"s-k\">Bienvenido</span><span class=\"s-v\">").append(Html.esc(nombre)).append("</span></div>");
        b.append("<div><span class=\"s-k\">Tu código / grupo</span><span class=\"s-v\">")
         .append(grupo == null || grupo.isBlank() ? "— (pídele a tu administrador que te asigne uno)" : Html.esc(grupo)).append("</span></div>");
        b.append("<div><span class=\"s-k\">Rol</span><span class=\"s-v\">Conectar</span></div>");
        b.append("</div>");
        return b.toString();
    }

    static String listaPersonas(List<Persona> lista, String filtro) { return listaPersonas(lista, filtro, "", false); }
    static String listaPersonas(List<Persona> lista, String filtro, String vista) { return listaPersonas(lista, filtro, vista, false); }

    static String listaPersonas(List<Persona> lista, String filtro, String vista, boolean soloRegistro) {
        StringBuilder b = new StringBuilder();
        b.append("<h1>Personas</h1>");
        b.append("<form class=\"bar\" method=\"get\" action=\"/personas\">")
         .append("<input type=\"search\" name=\"q\" placeholder=\"Buscar documento, nombre o correo\" value=\"")
         .append(Html.esc(filtro)).append("\">")
         .append("<button class=\"btn sec\">Buscar</button>")
         .append("<a class=\"btn").append(vista.isEmpty() && filtro.isEmpty() ? "" : " sec").append("\" href=\"/personas\">Todos</a>");
        if (!soloRegistro) {
            b.append("<a class=\"btn").append("singrupo".equals(vista) ? "" : " sec").append("\" href=\"/personas?vista=singrupo\">Sin grupo</a>")
             .append("<a class=\"btn").append("sincurso".equals(vista) ? "" : " sec").append("\" href=\"/personas?vista=sincurso\">Sin curso</a>")
             .append("<a class=\"btn").append("sinreset".equals(vista) ? "" : " sec").append("\" href=\"/personas?vista=sinreset\">Sin Reset</a>")
             .append("<a class=\"btn").append("reset".equals(vista) ? "" : " sec").append("\" href=\"/personas?vista=reset\">Fueron al Reset</a>")
             .append("<a class=\"btn").append("porconectar".equals(vista) ? "" : " sec").append("\" href=\"/personas?vista=porconectar\">Por conectar</a>")
             .append("<a class=\"btn").append("vencidos".equals(vista) ? "" : " sec").append("\" href=\"/personas?vista=vencidos\">Vencidos</a>")
             .append("<a class=\"btn").append("ganados".equals(vista) ? "" : " sec").append("\" href=\"/personas?vista=ganados\">Ganados</a>");
        }
        b.append("<span class=\"sp\"></span>")
         .append("<a class=\"btn\" href=\"/personas/form\">Nueva persona</a>")
         .append("</form>");
        b.append("<div class=\"wrap\"><table><thead><tr>")
         .append("<th>Grupo</th><th>Tipo</th><th>Documento</th><th>Nombres completos</th>")
         .append("<th>Celular</th><th>Correo</th><th>Seguimiento</th><th>Reset</th><th>N° Cursos</th><th>Cursos estudiados</th>")
         .append("<th>Líder</th><th></th>")
         .append("</tr></thead><tbody>");
        for (Persona p : lista) {
            b.append("<tr>")
             .append(td(p.grupoNombre)).append(td(p.tipoLabel()))
             .append(td(p.numDocumento)).append(td(p.nombresCompletos)).append(td(p.celular))
             .append(td(p.correo)).append(badgeSeguimiento(p)).append(badgeReset(p.reset))
             .append(td(String.valueOf(p.numCursos))).append(td(p.cursosTexto))
             .append(td(p.grupoLider))
             .append("<td class=\"acciones\">")
             .append("<a class=\"btn sec\" href=\"/personas/form?id=").append(p.id).append("\">Editar</a> ");
            if (!soloRegistro) {
                b.append("<form class=\"inline\" method=\"post\" action=\"/personas/eliminar\" ")
                 .append("onsubmit=\"return confirm('¿Eliminar a ").append(Html.esc(p.nombresCompletos)).append("?')\">")
                 .append("<input type=\"hidden\" name=\"id\" value=\"").append(p.id).append("\">")
                 .append("<button class=\"btn danger\">Eliminar</button></form>");
            }
            b.append("</td></tr>");
        }
        b.append("</tbody></table></div>");
        String etiqueta = switch (vista) {
            case "singrupo" -> " sin grupo";
            case "sincurso" -> " sin ningún curso";
            case "sinreset" -> " sin ningún Reset";
            case "reset"    -> " que fueron al Reset";
            case "porconectar" -> " por conectar (dentro del plazo)";
            case "vencidos"    -> " vencidos sin conectar";
            case "ganados"     -> " ganados este fin de semana";
            default          -> "";
        };
        b.append("<p class=\"count\">").append(lista.size()).append(" registros").append(etiqueta).append("</p>");
        return b.toString();
    }

    /** Lista de personas inscritas en un curso (desde el Resumen). */
    static String listaPersonasCurso(List<Persona> lista, String nombreCurso) {
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"bar\"><a class=\"btn sec\" href=\"/resumen\">&larr; Resumen</a></div>");
        b.append("<h1>Inscritos en: ").append(Html.esc(nombreCurso)).append("</h1>");
        b.append("<div class=\"wrap\"><table><thead><tr>")
         .append("<th>Grupo</th><th>Documento</th><th>Nombres completos</th><th>Celular</th><th>Correo</th>")
         .append("<th>Líder</th><th></th></tr></thead><tbody>");
        for (Persona p : lista) {
            b.append("<tr>")
             .append(td(p.grupoNombre)).append(td(p.numDocumento)).append(td(p.nombresCompletos))
             .append(td(p.celular)).append(td(p.correo)).append(td(p.grupoLider))
             .append("<td class=\"acciones\"><a class=\"btn sec\" href=\"/personas/form?id=").append(p.id).append("\">Editar</a></td>")
             .append("</tr>");
        }
        b.append("</tbody></table></div>");
        b.append("<p class=\"count\">").append(lista.size()).append(" inscritos</p>");
        return b.toString();
    }

    /** Página de la papelera: personas eliminadas, con restaurar y borrar definitivo (admin). */
    static String paginaPapelera(List<Persona> lista) {
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"bar\"><a class=\"btn sec\" href=\"/resumen\">&larr; Resumen</a></div>");
        b.append("<h1>Papelera</h1>");
        b.append("<p class=\"muted\" style=\"max-width:680px\">Aquí quedan las personas eliminadas. Puedes "
               + "<b>restaurarlas</b> o <b>borrarlas definitivamente</b> (esto último no se puede deshacer).</p>");
        if (lista.isEmpty()) { b.append("<p class=\"count\">La papelera está vacía.</p>"); return b.toString(); }
        b.append("<div class=\"wrap\"><table><thead><tr>")
         .append("<th>Grupo</th><th>Documento</th><th>Nombres completos</th><th>Eliminado</th><th>Por</th><th></th>")
         .append("</tr></thead><tbody>");
        for (Persona p : lista) {
            b.append("<tr>")
             .append(td(p.grupoNombre)).append(td(p.numDocumento)).append(td(p.nombresCompletos))
             .append(td(p.eliminadoEn)).append(td(p.eliminadoPor))
             .append("<td class=\"acciones\">")
             .append("<form class=\"inline\" method=\"post\" action=\"/papelera/restaurar\">")
             .append("<input type=\"hidden\" name=\"id\" value=\"").append(p.id).append("\">")
             .append("<button class=\"btn sec\">Restaurar</button></form> ")
             .append("<form class=\"inline\" method=\"post\" action=\"/papelera/eliminar\" ")
             .append("onsubmit=\"return confirm('Borrar DEFINITIVAMENTE a ").append(Html.esc(p.nombresCompletos))
             .append("? No se puede deshacer.')\">")
             .append("<input type=\"hidden\" name=\"id\" value=\"").append(p.id).append("\">")
             .append("<button class=\"btn danger\">Borrar definitivo</button></form>")
             .append("</td></tr>");
        }
        b.append("</tbody></table></div>");
        b.append("<p class=\"count\">").append(lista.size()).append(" en la papelera</p>");
        return b.toString();
    }

    static void rutaPapelera(HttpExchange ex, String metodo, String path, String usuario, boolean admin) throws Exception {
        if (!admin) { redirigir(ex, "/resumen"); return; }
        try (Connection con = Db.open()) {
            PersonaDao pdao = new PersonaDao(con);
            if (path.equals("/papelera/restaurar") && metodo.equals("POST")) {
                pdao.restaurar(Integer.parseInt(val(leerForm(ex), "id")));
                redirigir(ex, "/papelera"); return;
            }
            if (path.equals("/papelera/eliminar") && metodo.equals("POST")) {
                pdao.eliminarDefinitivo(Integer.parseInt(val(leerForm(ex), "id")));
                redirigir(ex, "/papelera"); return;
            }
            enviarHtml(ex, 200, Html.page("Papelera", usuario, admin, "/resumen", paginaPapelera(pdao.papelera())));
        }
    }

    /** Descarga un respaldo (.db) consistente de la base. */
    static void rutaRespaldo(HttpExchange ex, boolean admin) throws Exception {
        if (!admin) { redirigir(ex, "/resumen"); return; }
        if (Db.PG) {
            // En PostgreSQL (Neon) los respaldos son automáticos del propio servicio.
            String msg = "<div class=\"aviso ok\">En la nube (PostgreSQL/Neon) los respaldos son <b>automáticos</b> "
                       + "del propio servicio de base de datos. Para una copia legible de los datos, usa "
                       + "<a href=\"/exportar\">Exportar Excel</a>.</div>";
            enviarHtml(ex, 200, Html.page("Respaldo", "admin", true, "/resumen", msg));
            return;
        }
        File tmp = File.createTempFile("respaldo-redsurco", ".db");
        tmp.delete(); // VACUUM INTO requiere que el destino no exista
        Db.respaldarA(tmp);
        byte[] datos = Files.readAllBytes(tmp.toPath());
        tmp.delete();
        String nombre = "redsurco-" + java.time.LocalDate.now() + ".db";
        ex.getResponseHeaders().add("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + nombre + "\"");
        ex.sendResponseHeaders(200, datos.length);
        try (var os = ex.getResponseBody()) { os.write(datos); }
    }

    /** Resumen propio del usuario Conectar: sus personas ganadas, por semana y por mes. */
    static void rutaMiResumen(HttpExchange ex, String usuario) throws Exception {
        try (Connection con = Db.open()) {
            PersonaDao pdao = new PersonaDao(con);
            String grupo = Auth.grupoDeUsuario(con, usuario);
            List<Persona> lista = pdao.registradasPor(usuario);

            java.time.LocalDate hoy = java.time.LocalDate.now();
            String mesActual = hoy.getYear() + "-" + String.format("%02d", hoy.getMonthValue());
            java.time.LocalDate lunes = hoy.minusDays(hoy.getDayOfWeek().getValue() - 1);

            int total = lista.size(), estaSemana = 0, esteMes = 0;
            java.util.TreeMap<String, Long> porSemana = new java.util.TreeMap<>();
            java.util.TreeMap<String, Long> porMes = new java.util.TreeMap<>();
            for (Persona p : lista) {
                java.time.LocalDate d = fechaDe(p.creado);
                if (d == null) continue;
                String ym = d.getYear() + "-" + String.format("%02d", d.getMonthValue());
                if (ym.equals(mesActual)) esteMes++;
                if (!d.isBefore(lunes) && !d.isAfter(hoy)) estaSemana++;
                java.time.LocalDate ini = d.minusDays(d.getDayOfWeek().getValue() - 1);
                String semKey = String.format("%02d/%02d", ini.getDayOfMonth(), ini.getMonthValue());
                porSemana.merge(semKey, 1L, Long::sum);
                porMes.merge(ym, 1L, Long::sum);
            }

            StringBuilder b = new StringBuilder();
            b.append(saludoConectar(usuario, grupo));
            b.append("<h1>Mi resumen</h1>");
            b.append("<p class=\"muted\" style=\"margin-top:-6px\">Personas que has ganado (registrado). "
                   + "Los contadores por semana y mes usan la fecha en que las registraste.</p>");
            b.append("<div class=\"cards cards3\">")
             .append(card(total, "Total ganados"))
             .append(card(estaSemana, "Esta semana"))
             .append(card(esteMes, "Este mes"))
             .append("</div>");

            // Gráfica por semana / por mes
            List<Object[]> semData = new ArrayList<>(), mesData = new ArrayList<>();
            for (var e : porSemana.entrySet()) semData.add(new Object[]{e.getKey(), e.getValue()});
            for (var e : porMes.entrySet()) mesData.add(new Object[]{e.getKey(), e.getValue()});
            b.append("<div class=\"chartbox\"><div class=\"chart-head\"><h2>Ganados — comparativo</h2>")
             .append("<div class=\"tabs\">")
             .append("<button type=\"button\" class=\"tab on\" onclick=\"verTab(this,'r-sem')\">Por semana</button>")
             .append("<button type=\"button\" class=\"tab\" onclick=\"verTab(this,'r-mes')\">Por mes</button>")
             .append("</div></div>");
            if (total == 0) {
                b.append("<p class=\"muted\" style=\"padding:8px 2px\">Todavía no has registrado personas. "
                       + "Cuando lo hagas, aquí verás tu avance por semana y por mes.</p>");
            } else {
                b.append("<div id=\"r-sem\" class=\"panel\">").append(svgBarras(semData, "Semana")).append("</div>");
                b.append("<div id=\"r-mes\" class=\"panel oculto\">").append(svgBarras(mesData, "Mes")).append("</div>");
            }
            b.append("</div>");
            b.append("""
                <script>
                function verTab(btn,id){var box=btn.closest('.chartbox');
                  box.querySelectorAll('.tab').forEach(function(t){t.classList.remove('on');});btn.classList.add('on');
                  box.querySelectorAll('.panel').forEach(function(p){p.classList.add('oculto');});
                  var el=box.querySelector('#'+id); if(el) el.classList.remove('oculto');}
                </script>
                """);

            // Lista completa con todos los datos
            b.append("<h2 class=\"meta-h2\">Detalle de las personas que registraste</h2>");
            b.append("<div class=\"wrap\"><table><thead><tr>")
             .append("<th>Fecha</th><th>Grupo</th><th>Tipo</th><th>Documento</th><th>Nombres completos</th>")
             .append("<th>Celular</th><th>Correo</th><th>Seguimiento</th><th>Reset</th><th>N° Cursos</th><th>Cursos estudiados</th>")
             .append("</tr></thead><tbody>");
            if (lista.isEmpty()) {
                b.append("<tr><td colspan=\"11\" class=\"muted\" style=\"text-align:center;padding:16px\">Aún no has registrado personas.</td></tr>");
            } else {
                for (Persona p : lista) {
                    b.append("<tr>")
                     .append(td(fechaCorta(p.creado))).append(td(p.grupoNombre)).append(td(p.tipoLabel()))
                     .append(td(p.numDocumento)).append(td(p.nombresCompletos)).append(td(p.celular)).append(td(p.correo))
                     .append(badgeSeguimiento(p)).append(badgeReset(p.reset))
                     .append(td(String.valueOf(p.numCursos))).append(td(p.cursosTexto))
                     .append("</tr>");
                }
            }
            b.append("</tbody></table></div>");
            b.append("<p class=\"count\">").append(total).append(" personas registradas por ti</p>");

            enviarHtml(ex, 200, Html.page("Mi resumen", usuario, false, true, grupo, "/mi-resumen", b.toString()));
        }
    }

    /** Convierte 'yyyy-MM-dd ...' a LocalDate; null si no se puede. */
    static java.time.LocalDate fechaDe(String creado) {
        if (creado == null || creado.length() < 10) return null;
        try { return java.time.LocalDate.parse(creado.substring(0, 10)); }
        catch (Exception e) { return null; }
    }

    /** Fecha corta 'dd/MM/yyyy' a partir de 'yyyy-MM-dd ...'. */
    static String fechaCorta(String creado) {
        java.time.LocalDate d = fechaDe(creado);
        return d == null ? "—" : String.format("%02d/%02d/%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    static String formPersona(Persona p, List<Curso> cursos, List<Grupo> grupos, List<Integer> marcados, String err) {
        return formPersona(p, cursos, grupos, marcados, err, true, List.of(), List.of(), "");
    }

    static String formPersona(Persona p, List<Curso> cursos, List<Grupo> grupos, List<Integer> marcados,
                              String err, boolean admin, List<String[]> logs) {
        return formPersona(p, cursos, grupos, marcados, err, admin, logs, List.of(), "");
    }

    static String formPersona(Persona p, List<Curso> cursos, List<Grupo> grupos, List<Integer> marcados,
                              String err, boolean admin, List<String[]> logs, List<String> periodos) {
        return formPersona(p, cursos, grupos, marcados, err, admin, logs, periodos, "");
    }

    static String formPersona(Persona p, List<Curso> cursos, List<Grupo> grupos, List<Integer> marcados,
                              String err, boolean admin, List<String[]> logs, List<String> periodos, String grupoFijo) {
        StringBuilder b = new StringBuilder();
        b.append("<h1>").append(p.id == 0 ? "Nueva persona" : "Editar persona").append("</h1>");
        if ("1".equals(err)) b.append("<div class=\"err\">Documento y nombres son obligatorios.</div>");
        if ("2".equals(err)) b.append("<div class=\"err\">No se pudo guardar. ¿El documento ya existe?</div>");
        if ("3".equals(err)) b.append("<div class=\"err\">El DNI debe tener exactamente 8 números.</div>");
        if ("4".equals(err)) b.append("<div class=\"err\">El carné de extranjería debe tener exactamente 9 números.</div>");
        b.append("<form class=\"formbox\" method=\"post\" action=\"/personas/guardar\">");
        b.append("<input type=\"hidden\" name=\"id\" value=\"").append(p.id).append("\">");
        // grupo: fijo (para "Solo registrar" con código propio) o editable con datalist
        String grupoActual = "";
        for (Grupo g : grupos) if (p.grupoId != null && p.grupoId == g.id) grupoActual = g.nombre;
        if (grupoFijo != null && !grupoFijo.isBlank()) {
            b.append("<label>Grupo de conexión</label>");
            b.append("<input type=\"text\" value=\"").append(Html.esc(grupoFijo)).append("\" readonly>");
            b.append("<p class=\"muted\" style=\"margin:4px 0 0\">Tu grupo asignado. Todas las personas que registres quedan en este grupo.</p>");
        } else {
            b.append("<label>Grupo de conexión <span class=\"muted\">(escribe uno nuevo o elige de la lista)</span></label>");
            b.append("<input type=\"text\" name=\"grupo\" list=\"grupos-list\" value=\"").append(Html.esc(grupoActual))
             .append("\" placeholder=\"Ej: C18 (vacío = sin grupo)\">");
            b.append("<datalist id=\"grupos-list\">");
            for (Grupo g : grupos) b.append("<option value=\"").append(Html.esc(g.nombre)).append("\">");
            b.append("</datalist>");
        }
        // documento: tipo (DNI 8, CE 9, Otro libre) + numero juntos
        boolean esCE = "CE".equals(p.tipoDocumento), esOtro = "OTRO".equals(p.tipoDocumento);
        b.append("<label>Documento</label><div class=\"doc\"><select name=\"tipo\" id=\"tipoDoc\" onchange=\"ajustaDoc()\">");
        b.append("<option value=\"DNI\"").append(!esCE && !esOtro ? " selected" : "").append(">DNI</option>");
        b.append("<option value=\"CE\"").append(esCE ? " selected" : "").append(">Carné de extranjería</option>");
        b.append("<option value=\"OTRO\"").append(esOtro ? " selected" : "").append(">Otro documento</option>");
        b.append("</select><input type=\"text\" id=\"numDoc\" name=\"num\" value=\"").append(Html.esc(p.numDocumento))
         .append("\" placeholder=\"Número\"></div>");
        b.append("<div id=\"docHint\" class=\"muted\" style=\"margin-top:4px\"></div>");
        b.append("<label>Nombres completos</label><input type=\"text\" name=\"nombres\" value=\"").append(Html.esc(p.nombresCompletos)).append("\">");
        b.append("<label>Celular</label><input type=\"text\" name=\"celular\" value=\"").append(Html.esc(p.celular)).append("\">");
        b.append("<label>Correo</label><input type=\"text\" name=\"correo\" value=\"").append(Html.esc(p.correo)).append("\">");
        b.append("<label>¿Fue al Reset (encuentro)?</label>")
         .append("<label class=\"toggle\"><input type=\"checkbox\" name=\"reset\" value=\"1\" onchange=\"estadoSiNo(this)\"")
         .append(p.reset ? " checked" : "").append(">")
         .append("<span class=\"sino ").append(p.reset ? "si" : "no").append("\">").append(p.reset ? "Sí" : "No").append("</span></label>");

        // --- Seguimiento (conexión de la persona nueva) ---
        boolean bloqueado = p.bloqueadoPara(admin);
        b.append("<div class=\"segui\">");
        b.append("<div class=\"segui-top\"><strong>Seguimiento</strong>");
        if (p.id != 0 && p.tienePlazo()) {
            String estado = p.seguimientoTexto();
            String cls = p.seguimientoClase();
            b.append(" <span class=\"badge ").append(cls).append("\">").append(Html.esc(estado)).append("</span>");
        }
        b.append("</div>");
        b.append("<label style=\"display:flex;align-items:center;gap:8px;cursor:pointer;margin-top:8px\">")
         .append("<input type=\"checkbox\" name=\"conectado\" value=\"1\"").append(p.conectado ? " checked" : "")
         .append(bloqueado ? " disabled" : "").append("> Conectado (ya fue contactado)</label>");
        if (bloqueado) {
            b.append("<div class=\"candado\">🔒 El plazo de ").append(Persona.DIAS_PLAZO)
             .append(" días venció. Solo un administrador puede cambiar esta casilla.</div>");
            // Mantener el valor al enviar aunque el checkbox esté deshabilitado
            b.append("<input type=\"hidden\" name=\"conectado\" value=\"").append(p.conectado ? "1" : "").append("\">");
        }
        b.append("<label>Observaciones</label><textarea name=\"observaciones\" rows=\"2\" placeholder=\"Notas del seguimiento…\">")
         .append(Html.esc(p.observaciones == null ? "" : p.observaciones)).append("</textarea>");
        // Historial de cambios
        if (logs != null && !logs.isEmpty()) {
            b.append("<details class=\"hist\"><summary>Historial de seguimiento (").append(logs.size()).append(")</summary><ul>");
            for (String[] l : logs) {
                b.append("<li><span class=\"fecha\">").append(Html.esc(l[0])).append("</span> — ")
                 .append(Html.esc(l[2])).append(" · ").append(Html.esc(l[1]));
                if (l[3] != null && !l[3].isBlank()) b.append(" <em>(").append(Html.esc(l[3])).append(")</em>");
                b.append("</li>");
            }
            b.append("</ul></details>");
        }
        b.append("</div>");

        b.append("<label>Cursos estudiados</label>");
        if (cursos.isEmpty()) {
            b.append("<div class=\"cursos\"><span class=\"muted\">No hay cursos aún. Créalos en la pestaña Cursos.</span></div>");
        } else {
            b.append("<div class=\"cursos-grid\">");
            for (Curso c : cursos) {
                boolean ck = marcados.contains(c.id);
                b.append("<label class=\"curso-item").append(ck ? " on" : "").append("\">")
                 .append("<input type=\"checkbox\" name=\"curso\" value=\"").append(c.id).append("\"")
                 .append(ck ? " checked" : "").append(" onchange=\"this.parentNode.classList.toggle('on',this.checked)\">")
                 .append("<span>").append(Html.esc(c.etiqueta())).append("</span></label>");
            }
            b.append("</div>");
        }
        b.append("<div style=\"margin-top:16px\"><button class=\"btn\">Guardar</button> ")
         .append("<a class=\"btn sec\" href=\"/personas\">Cancelar</a></div>");
        b.append("</form>");
        // Validación en el navegador según el tipo de documento
        b.append("""
            <script>
            function estadoSiNo(cb){
              var s=cb.parentNode.querySelector('.sino');
              if(s){ s.textContent=cb.checked?'Sí':'No'; s.className='sino '+(cb.checked?'si':'no'); }
            }
            function ajustaDoc(){
              var t=document.getElementById('tipoDoc').value;
              var n=document.getElementById('numDoc'), h=document.getElementById('docHint');
              if(t==='DNI'){ n.maxLength=8; n.placeholder='8 números'; h.textContent='El DNI debe tener 8 números.'; }
              else if(t==='CE'){ n.maxLength=9; n.placeholder='9 números'; h.textContent='El carné de extranjería debe tener 9 números.'; }
              else { n.maxLength=30; n.placeholder='Número del documento'; h.textContent='Otro documento: escribe el número manualmente.'; }
              if(t==='DNI'||t==='CE'){ n.value=n.value.replace(/\\D/g,''); }
            }
            document.getElementById('numDoc').addEventListener('input',function(){
              var t=document.getElementById('tipoDoc').value;
              if(t==='DNI'||t==='CE'){ this.value=this.value.replace(/\\D/g,''); }
            });
            document.querySelector('form.formbox').addEventListener('submit',function(e){
              var t=document.getElementById('tipoDoc').value, v=document.getElementById('numDoc').value.trim();
              if(t==='DNI' && !/^\\d{8}$/.test(v)){ e.preventDefault(); alert('El DNI debe tener exactamente 8 números.'); }
              else if(t==='CE' && !/^\\d{9}$/.test(v)){ e.preventDefault(); alert('El carné de extranjería debe tener exactamente 9 números.'); }
            });
            ajustaDoc();
            </script>
            """);
        return b.toString();
    }

    // =================== CURSOS ===================

    static void rutaCursos(HttpExchange ex, String metodo, String path, String usuario, boolean admin) throws Exception {
        Map<String, String> q = query(ex);
        try (Connection con = Db.open()) {
            CursoDao dao = new CursoDao(con);
            if (path.equals("/cursos/guardar") && metodo.equals("POST")) {
                Map<String, List<String>> f = leerForm(ex);
                if (val(f, "nombre").isBlank()) { redirigir(ex, "/cursos/form?err=1"); return; }
                Curso c = new Curso();
                String idS = val(f, "id"); if (!idS.isBlank()) c.id = Integer.parseInt(idS);
                c.nombre = val(f, "nombre"); c.modalidad = val(f, "modalidad");
                try { if (c.id == 0) dao.insert(c); else dao.update(c); redirigir(ex, "/cursos"); }
                catch (RuntimeException e) { redirigir(ex, "/cursos/form?err=2" + (c.id > 0 ? "&id=" + c.id : "")); }
                return;
            }
            if (path.equals("/cursos/eliminar") && metodo.equals("POST")) {
                dao.delete(Integer.parseInt(val(leerForm(ex), "id"))); redirigir(ex, "/cursos"); return;
            }
            if (path.equals("/cursos/form")) {
                Curso c = q.containsKey("id") ? dao.findById(Integer.parseInt(q.get("id"))) : new Curso();
                if (c == null) c = new Curso();
                enviarHtml(ex, 200, Html.page("Curso", usuario, admin, "/cursos", formCurso(c, q.get("err"))));
                return;
            }
            StringBuilder b = new StringBuilder("<h1>Cursos</h1>");
            b.append("<div class=\"bar\"><span class=\"sp\"></span><a class=\"btn\" href=\"/cursos/form\">Nuevo curso</a></div>");
            b.append("<div class=\"wrap\"><table><thead><tr><th>Nombre</th><th>Modalidad</th><th></th></tr></thead><tbody>");
            for (Curso c : dao.findAll()) {
                b.append("<tr>").append(td(c.nombre)).append(td(c.modalidad))
                 .append("<td style=\"white-space:nowrap\"><a class=\"btn sec\" href=\"/cursos/form?id=").append(c.id).append("\">Editar</a> ")
                 .append(formEliminar("/cursos/eliminar", c.id, c.nombre)).append("</td></tr>");
            }
            b.append("</tbody></table></div>");
            enviarHtml(ex, 200, Html.page("Cursos", usuario, admin, "/cursos", b.toString()));
        }
    }

    static String formCurso(Curso c, String err) {
        StringBuilder b = new StringBuilder("<h1>").append(c.id == 0 ? "Nuevo curso" : "Editar curso").append("</h1>");
        if ("1".equals(err)) b.append("<div class=\"err\">El nombre es obligatorio.</div>");
        if ("2".equals(err)) b.append("<div class=\"err\">No se pudo guardar. ¿El curso ya existe?</div>");
        b.append("<form class=\"formbox\" method=\"post\" action=\"/cursos/guardar\">")
         .append("<input type=\"hidden\" name=\"id\" value=\"").append(c.id).append("\">")
         .append("<label>Nombre del curso</label><input type=\"text\" name=\"nombre\" value=\"").append(Html.esc(c.nombre)).append("\">")
         .append("<label>Modalidad (opcional)</label><select name=\"modalidad\">")
         .append(opt("", c.modalidad)).append(opt("PRESENCIAL", c.modalidad)).append(opt("VIRTUAL", c.modalidad))
         .append("</select>")
         .append("<div style=\"margin-top:16px\"><button class=\"btn\">Guardar</button> <a class=\"btn sec\" href=\"/cursos\">Cancelar</a></div></form>");
        return b.toString();
    }

    // =================== GRUPOS ===================

    static void rutaGrupos(HttpExchange ex, String metodo, String path, String usuario, boolean admin) throws Exception {
        Map<String, String> q = query(ex);
        try (Connection con = Db.open()) {
            GrupoDao dao = new GrupoDao(con);
            if (path.equals("/grupos/guardar") && metodo.equals("POST")) {
                Map<String, List<String>> f = leerForm(ex);
                if (val(f, "nombre").isBlank()) { redirigir(ex, "/grupos/form?err=1"); return; }
                Grupo g = new Grupo();
                String idS = val(f, "id"); if (!idS.isBlank()) g.id = Integer.parseInt(idS);
                g.nombre = val(f, "nombre"); g.lider = val(f, "lider");
                try { if (g.id == 0) dao.insert(g); else dao.update(g); redirigir(ex, "/grupos"); }
                catch (RuntimeException e) { redirigir(ex, "/grupos/form?err=2" + (g.id > 0 ? "&id=" + g.id : "")); }
                return;
            }
            if (path.equals("/grupos/eliminar") && metodo.equals("POST")) {
                dao.delete(Integer.parseInt(val(leerForm(ex), "id"))); redirigir(ex, "/grupos"); return;
            }
            if (path.equals("/grupos/form")) {
                Grupo g = q.containsKey("id") ? dao.findById(Integer.parseInt(q.get("id"))) : new Grupo();
                if (g == null) g = new Grupo();
                enviarHtml(ex, 200, Html.page("Grupo", usuario, admin, "/grupos", formGrupo(g, q.get("err"))));
                return;
            }
            if (path.equals("/grupos/ver") && q.containsKey("id")) {
                Grupo g = dao.findById(Integer.parseInt(q.get("id")));
                if (g == null) { redirigir(ex, "/grupos"); return; }
                List<Persona> integrantes = new PersonaDao(con).findByGrupo(g.id);
                enviarHtml(ex, 200, Html.page("Grupo " + g.nombre, usuario, admin, "/grupos",
                        detalleGrupo(g, integrantes)));
                return;
            }
            StringBuilder b = new StringBuilder("<h1>Grupos de conexión</h1>");
            b.append("<div class=\"bar\"><span class=\"sp\"></span><a class=\"btn\" href=\"/grupos/form\">Nuevo grupo</a></div>");
            b.append("<div class=\"wrap\"><table><thead><tr><th>Grupo</th><th>Líder de conexión</th><th>Integrantes</th><th></th></tr></thead><tbody>");
            for (Grupo g : dao.findAll()) {
                int n = new PersonaDao(con).findByGrupo(g.id).size();
                b.append("<tr>")
                 .append("<td><a class=\"glink\" href=\"/grupos/ver?id=").append(g.id).append("\">")
                 .append(Html.esc(g.nombre)).append("</a></td>")
                 .append(td(g.lider))
                 .append("<td>").append(n).append("</td>")
                 .append("<td style=\"white-space:nowrap\"><a class=\"btn sec\" href=\"/grupos/ver?id=").append(g.id).append("\">Ver</a> ")
                 .append("<a class=\"btn sec\" href=\"/grupos/form?id=").append(g.id).append("\">Editar</a> ")
                 .append(formEliminar("/grupos/eliminar", g.id, g.nombre)).append("</td></tr>");
            }
            b.append("</tbody></table></div>");
            enviarHtml(ex, 200, Html.page("Grupos", usuario, admin, "/grupos", b.toString()));
        }
    }

    static String formGrupo(Grupo g, String err) {
        StringBuilder b = new StringBuilder("<h1>").append(g.id == 0 ? "Nuevo grupo" : "Editar grupo").append("</h1>");
        if ("1".equals(err)) b.append("<div class=\"err\">El nombre es obligatorio.</div>");
        if ("2".equals(err)) b.append("<div class=\"err\">No se pudo guardar. ¿El grupo ya existe?</div>");
        b.append("<form class=\"formbox\" method=\"post\" action=\"/grupos/guardar\">")
         .append("<input type=\"hidden\" name=\"id\" value=\"").append(g.id).append("\">")
         .append("<label>Nombre del grupo (ej: C18)</label><input type=\"text\" name=\"nombre\" value=\"").append(Html.esc(g.nombre)).append("\">")
         .append("<label>Líder de conexión</label><input type=\"text\" name=\"lider\" value=\"").append(Html.esc(g.lider)).append("\">")
         .append("<div style=\"margin-top:16px\"><button class=\"btn\">Guardar</button> <a class=\"btn sec\" href=\"/grupos\">Cancelar</a></div></form>");
        return b.toString();
    }

    /** Detalle de un grupo: líder + tabla de integrantes. */
    static String detalleGrupo(Grupo g, List<Persona> integrantes) {
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"bar\"><a class=\"btn sec\" href=\"/grupos\">&larr; Grupos</a>")
         .append("<span class=\"sp\"></span>")
         .append("<a class=\"btn sec\" href=\"/grupos/form?id=").append(g.id).append("\">Editar grupo</a></div>");
        b.append("<h1>Grupo ").append(Html.esc(g.nombre)).append("</h1>");
        b.append("<div class=\"grupo-head\">")
         .append("<div><span class=\"gk\">Líder de conexión</span><span class=\"gv\">")
         .append(g.lider == null || g.lider.isBlank() ? "—" : Html.esc(g.lider)).append("</span></div>")
         .append("<div><span class=\"gk\">Integrantes</span><span class=\"gv\">").append(integrantes.size()).append("</span></div>")
         .append("</div>");
        if (integrantes.isEmpty()) {
            b.append("<p class=\"count\">Este grupo todavía no tiene personas registradas.</p>");
            return b.toString();
        }
        b.append("<div class=\"wrap\"><table><thead><tr>")
         .append("<th>Documento</th><th>Nombres completos</th><th>Celular</th><th>Correo</th>")
         .append("<th>Seguimiento</th><th>Reset</th><th>N° Cursos</th><th>Cursos estudiados</th><th></th>")
         .append("</tr></thead><tbody>");
        for (Persona p : integrantes) {
            b.append("<tr>")
             .append(td(p.numDocumento)).append(td(p.nombresCompletos)).append(td(p.celular)).append(td(p.correo))
             .append(badgeSeguimiento(p)).append(badgeReset(p.reset))
             .append(td(String.valueOf(p.numCursos))).append(td(p.cursosTexto))
             .append("<td class=\"acciones\"><a class=\"btn sec\" href=\"/personas/form?id=").append(p.id).append("\">Editar</a></td>")
             .append("</tr>");
        }
        b.append("</tbody></table></div>");
        b.append("<p class=\"count\">").append(integrantes.size()).append(" integrantes</p>");
        return b.toString();
    }

    // =================== RESUMEN ===================

    static void rutaResumen(HttpExchange ex, String usuario, boolean admin) throws Exception {
        try (Connection con = Db.open()) {
            ResumenDao r = new ResumenDao(con);
            StringBuilder b = new StringBuilder("<h1>Resumen</h1>");
            b.append("<div class=\"cards\">")
             .append(card(r.totalPersonas(), "Personas"))
             .append(card(r.totalGrupos(), "Grupos"))
             .append(card(r.personasSinCurso(), "Sin ningún curso", "/personas?vista=sincurso"))
             .append(card(r.personasSinReset(), "Sin ningún Reset", "/personas?vista=sinreset"))
             .append(card(r.porConectar(), "Por conectar", "/personas?vista=porconectar"))
             .append(card(r.vencidosSinConectar(), "Vencidos sin conectar", "/personas?vista=vencidos"))
             .append(card(r.ingresadosFinSemana(), "Ganados (fin de semana)", "/personas?vista=ganados"))
             .append("</div>");
            b.append(graficoComparativo(r));
            b.append("<div class=\"grid3\">");
            b.append(tablaInscritosCurso(r.inscritosPorCurso()));
            b.append(tablaResumen("Cursos por persona", new String[]{"Persona", "Grupo", "N° Cursos"}, r.cursosPorPersona()));
            b.append(tablaResumen("Personas por grupo", new String[]{"Grupo", "Líder", "Personas"}, r.personasPorGrupo()));
            b.append("</div>");
            b.append("<p style=\"margin-top:16px\"><a class=\"btn\" href=\"/exportar\">Exportar Excel</a>");
            if (admin) b.append(" <a class=\"btn sec\" href=\"/respaldo\">Descargar respaldo</a> ")
                        .append("<a class=\"btn sec\" href=\"/papelera\">Papelera</a>");
            b.append("</p>");
            enviarHtml(ex, 200, Html.page("Resumen", usuario, admin, "/resumen", b.toString()));
        }
    }

    // =================== METAS POR PERIODO ===================

    /** Fila calculada de la tabla de metas (por grupo). */
    static final class FilaMeta {
        String codigo, nombre, sexo;
        int disc, lider, total, totalPrev;
        String avance;
    }

    static void rutaMetas(HttpExchange ex, String metodo, String path, String usuario, boolean admin) throws Exception {
        try (Connection con = Db.open()) {
            MetasDao mdao = new MetasDao(con);

            // Importar archivo (Excel/CSV) con los totales del trimestre
            if (path.equals("/metas/importar") && metodo.equals("POST")) {
                if (!admin) { redirigir(ex, "/metas"); return; }
                byte[][] archivo = leerArchivoSubido(ex);
                String aviso;
                if (archivo == null || archivo[1] == null || archivo[1].length == 0) {
                    aviso = "err|No se recibió ningún archivo.";
                } else {
                    String nombre = new String(archivo[0], java.nio.charset.StandardCharsets.UTF_8);
                    try {
                        List<List<String>> filas;
                        if (nombre.toLowerCase().endsWith(".csv"))
                            filas = Importador.leerCsv(new String(archivo[1], java.nio.charset.StandardCharsets.UTF_8));
                        else if (nombre.toLowerCase().endsWith(".pdf"))
                            throw new IllegalArgumentException("PDF");
                        else
                            filas = XlsxReader.leer(archivo[1]);
                        int n = importarMetas(mdao, filas);
                        aviso = "ok|Se cargaron/actualizaron " + n + " grupos.";
                    } catch (IllegalArgumentException pdf) {
                        aviso = "err|Por ahora el import de Metas acepta <b>.xlsx</b> o <b>.csv</b>. "
                              + "Para leer tu <b>PDF</b> necesito un ejemplo de su formato; mientras tanto, "
                              + "pásalo a Excel/CSV con las columnas indicadas.";
                    } catch (Exception err) {
                        aviso = "err|No se pudo leer el archivo: " + Html.esc(String.valueOf(err.getMessage()));
                    }
                }
                redirigir(ex, "/metas?aviso=" + java.net.URLEncoder.encode(aviso, "UTF-8"));
                return;
            }

            // Guardar/editar un grupo en un periodo (alta manual)
            if (path.equals("/metas/guardar") && metodo.equals("POST")) {
                if (!admin) { redirigir(ex, "/metas"); return; }
                Map<String, List<String>> f = leerForm(ex);
                Meta m = new Meta();
                m.periodo = Periodo.norm(val(f, "periodo"));
                m.codigo = val(f, "codigo").trim();
                m.nombre = val(f, "nombre").trim();
                m.insLider = ent(val(f, "ins_lider"));
                m.insDisc = ent(val(f, "ins_disc"));
                m.resLider = ent(val(f, "res_lider"));
                m.resDisc = ent(val(f, "res_disc"));
                if (!m.periodo.isEmpty() && !m.codigo.isEmpty()) mdao.upsert(m);
                redirigir(ex, "/metas?periodo=" + m.periodo + "&equipo=" + (Periodo.esNext(m.codigo) ? "next" : "principal"));
                return;
            }

            // Eliminar un grupo de un periodo
            if (path.equals("/metas/eliminar") && metodo.equals("POST")) {
                if (!admin) { redirigir(ex, "/metas"); return; }
                Map<String, List<String>> f = leerForm(ex);
                String per = Periodo.norm(val(f, "periodo")), cod = val(f, "codigo").trim();
                if (!per.isEmpty() && !cod.isEmpty()) mdao.delete(per, cod);
                redirigir(ex, "/metas?periodo=" + per);
                return;
            }

            List<String> periodos = mdao.periodos();
            Map<String, String> q = query(ex);
            String sel = Periodo.norm(q.getOrDefault("periodo", ""));
            if (sel.isEmpty() && !periodos.isEmpty()) sel = periodos.get(periodos.size() - 1);
            String prev = Periodo.anterior(sel);
            boolean next = "next".equalsIgnoreCase(q.getOrDefault("equipo", "principal"));
            String equipo = next ? "next" : "principal";
            boolean reset = "reset".equalsIgnoreCase(q.getOrDefault("metrica", "ins"));
            String metrica = reset ? "reset" : "ins";

            StringBuilder b = new StringBuilder("<h1>Metas por periodo</h1>");
            b.append(avisoMeta(q.get("aviso")));

            if (periodos.isEmpty()) {
                b.append("<p class=\"muted\" style=\"max-width:680px;font-size:14px;line-height:1.55\">")
                 .append("Aún no hay metas cargadas. Las metas se llenan subiendo un <b>archivo por trimestre</b> "
                   + "(Excel o CSV) con los totales por grupo, o escribiéndolos a mano abajo. El <b>periodo</b> es "
                   + "el año-trimestre, ej. <b>2026-3</b> o <b>2027-1</b>.</p>");
                if (admin) { b.append(metaImportForm()); b.append(metaManualForm(null, "2026-3")); }
                enviarHtml(ex, 200, Html.page("Metas", usuario, admin, "/metas", b.toString()));
                return;
            }

            // Cargar metas del periodo seleccionado y del anterior, indexadas por código
            Map<String, Meta> mSel = new LinkedHashMap<>(), mPrev = new HashMap<>();
            for (Meta m : mdao.porPeriodo(sel)) mSel.put(m.codigo, m);
            for (Meta m : mdao.porPeriodo(prev)) mPrev.put(m.codigo, m);

            // Filas del equipo seleccionado (unión de códigos con datos en sel o prev)
            List<FilaMeta> hombres = new ArrayList<>(), mujeres = new ArrayList<>(), otros = new ArrayList<>();
            java.util.LinkedHashSet<String> codigos = new java.util.LinkedHashSet<>(mSel.keySet());
            codigos.addAll(mPrev.keySet());
            for (String cod : codigos) {
                if (Periodo.esNext(cod) != next) continue;
                FilaMeta f = metaFila(mSel.get(cod), mPrev.get(cod), reset);
                if (f.total == 0 && f.totalPrev == 0) continue;
                ("H".equals(f.sexo) ? hombres : "M".equals(f.sexo) ? mujeres : otros).add(f);
            }

            // Gráfica: total por periodo del equipo y métrica actuales
            Map<String, Long> chart = new TreeMap<>();
            for (Meta m : mdao.todas())
                if (Periodo.esNext(m.codigo) == next) chart.merge(m.periodo, (long) m.total(reset), Long::sum);

            int[] kH = suma(hombres), kM = suma(mujeres), kO = suma(otros);
            int[] kT = { kH[0]+kM[0]+kO[0], kH[1]+kM[1]+kO[1], kH[2]+kM[2]+kO[2], kH[3]+kM[3]+kO[3] };

            String qs = "&equipo=" + equipo + "&metrica=" + metrica + "&periodo=" + sel;

            // Pestañas de equipo
            b.append("<div class=\"tabs eqtabs\">")
             .append("<a class=\"tab").append(next ? "" : " on").append("\" href=\"/metas?equipo=principal&metrica=").append(metrica).append("&periodo=").append(sel).append("\">Equipo Principal</a>")
             .append("<a class=\"tab").append(next ? " on" : "").append("\" href=\"/metas?equipo=next&metrica=").append(metrica).append("&periodo=").append(sel).append("\">Equipo Next</a>")
             .append("</div>");

            // Pestañas de métrica (Inscritos / Reset)
            b.append("<div class=\"tabs eqtabs\" style=\"margin-left:10px\">")
             .append("<a class=\"tab").append(reset ? "" : " on").append("\" href=\"/metas?metrica=ins&equipo=").append(equipo).append("&periodo=").append(sel).append("\">Inscritos</a>")
             .append("<a class=\"tab").append(reset ? " on" : "").append("\" href=\"/metas?metrica=reset&equipo=").append(equipo).append("&periodo=").append(sel).append("\">Reset</a>")
             .append("</div>");

            // Selector de periodo
            b.append("<form class=\"bar\" method=\"get\" action=\"/metas\">")
             .append("<input type=\"hidden\" name=\"equipo\" value=\"").append(equipo).append("\">")
             .append("<input type=\"hidden\" name=\"metrica\" value=\"").append(metrica).append("\">")
             .append("<label style=\"font-weight:600;color:var(--muted);font-size:13px\">Periodo</label>")
             .append("<select name=\"periodo\" onchange=\"this.form.submit()\">");
            for (String p : periodos)
                b.append("<option value=\"").append(p).append("\"").append(p.equals(sel) ? " selected" : "")
                 .append(">").append(Html.esc(Periodo.etiqueta(p))).append("</option>");
            b.append("</select>");
            b.append("<span class=\"muted\" style=\"font-size:13px\">Compara contra: ")
             .append(prev.isEmpty() ? "—" : Html.esc(Periodo.etiqueta(prev))).append("</span>");
            b.append("<span class=\"sp\"></span>");
            if (admin) b.append("<a class=\"btn sec\" href=\"/metas?form=1").append(qs).append("\">Cargar / editar datos</a>");
            b.append("</form>");

            if (admin && "1".equals(q.get("form"))) {
                b.append(metaImportForm());
                Meta edit = q.containsKey("edit") ? mdao.get(sel, q.get("edit")) : null;
                b.append(metaManualForm(edit, sel));
            }

            String etqMetrica = reset ? "Reset" : "Inscritos";
            b.append("<div class=\"cards cards3\">");
            b.append(kpiMeta((next ? "Equipo Next" : "Equipo Principal") + " · " + etqMetrica, kT, prev));
            b.append(kpiMeta("Hombres", kH, prev));
            b.append(kpiMeta("Mujeres", kM, prev));
            b.append("</div>");

            b.append("<div class=\"grid2\">");
            b.append(metaTabla("Hombres", hombres, prev, admin, sel, equipo, metrica));
            b.append(metaTabla("Mujeres", mujeres, prev, admin, sel, equipo, metrica));
            b.append("</div>");
            if (!otros.isEmpty()) b.append(metaTabla("Otros grupos (código sin C1/C2)", otros, prev, admin, sel, equipo, metrica));

            List<Object[]> chartData = new ArrayList<>();
            for (Map.Entry<String, Long> e : chart.entrySet()) chartData.add(new Object[]{e.getKey(), e.getValue()});
            b.append(graficoMetas(chartData, etqMetrica));

            enviarHtml(ex, 200, Html.page("Metas", usuario, admin, "/metas", b.toString()));
        }
    }

    /** Calcula una fila a partir de la meta del periodo y la del anterior. */
    static FilaMeta metaFila(Meta sel, Meta prev, boolean reset) {
        Meta base = sel != null ? sel : prev;
        FilaMeta f = new FilaMeta();
        f.codigo = base.codigo;
        f.nombre = base.nombre == null || base.nombre.isBlank() ? base.codigo : base.nombre;
        f.sexo = Periodo.sexoDeCodigo(f.codigo);
        f.lider = sel != null ? sel.lider(reset) : 0;
        f.disc = sel != null ? sel.disc(reset) : 0;
        f.total = f.lider + f.disc;
        f.totalPrev = prev != null ? prev.total(reset) : 0;
        f.avance = f.totalPrev > 0 ? Math.round(100f * f.total / f.totalPrev) + "%"
                : (f.total > 0 ? "Nuevo" : "—");
        return f;
    }

    /** Parsea filas de un archivo e inserta/actualiza metas. Devuelve cuántas se cargaron. */
    static int importarMetas(MetasDao mdao, List<List<String>> filas) {
        if (filas == null || filas.isEmpty()) return 0;
        List<String> cab = filas.get(0);
        int cPer = colMeta(cab, "periodo", "trimestre");
        int cCod = colMeta(cab, "cod", "codigo", "código", "grupo");
        int cNom = colMeta(cab, "nombre", "lider nombre");
        int cIL = colMeta(cab, "lider", "líder", "lideres", "líderes", "ins lider", "lider ins");
        int cID = colMeta(cab, "disc", "asistentes", "ins disc", "discipulos", "discípulos");
        int cRL = colMeta(cab, "reset lider", "reset líder", "res lider");
        int cRD = colMeta(cab, "reset", "reset disc", "reset asistentes", "res disc");
        int n = 0;
        for (int i = 1; i < filas.size(); i++) {
            List<String> fila = filas.get(i);
            String per = Periodo.norm(celdaMeta(fila, cPer));
            String cod = celdaMeta(fila, cCod).trim();
            if (per.isEmpty() || cod.isEmpty()) continue;
            Meta m = new Meta();
            m.periodo = per; m.codigo = cod;
            m.nombre = celdaMeta(fila, cNom).trim();
            m.insLider = ent(celdaMeta(fila, cIL));
            m.insDisc = ent(celdaMeta(fila, cID));
            m.resLider = ent(celdaMeta(fila, cRL));
            m.resDisc = ent(celdaMeta(fila, cRD));
            mdao.upsert(m);
            n++;
        }
        return n;
    }

    private static int colMeta(List<String> cab, String... nombres) {
        for (int i = 0; i < cab.size(); i++) {
            String h = cab.get(i) == null ? "" : com.redsurco.web.Txt.clave(cab.get(i));
            for (String nm : nombres) if (h.equals(com.redsurco.web.Txt.clave(nm))) return i;
        }
        return -1;
    }
    private static String celdaMeta(List<String> fila, int i) {
        return (i >= 0 && i < fila.size() && fila.get(i) != null) ? fila.get(i).trim() : "";
    }
    static int ent(String s) {
        if (s == null) return 0;
        String t = s.trim().replaceAll("[^0-9-]", "");
        if (t.isEmpty() || t.equals("-")) return 0;
        try { return Integer.parseInt(t); } catch (NumberFormatException e) { return 0; }
    }

    /** Suma de una lista de filas: [lider, disc, total, totalPrev]. */
    static int[] suma(List<FilaMeta> fs) {
        int l=0,d=0,t=0,tp=0;
        for (FilaMeta f : fs) { l+=f.lider; d+=f.disc; t+=f.total; tp+=f.totalPrev; }
        return new int[]{l,d,t,tp};
    }

    /** Aviso "ok|texto" o "err|texto" codificado en la URL. */
    static String avisoMeta(String aviso) {
        if (aviso == null || aviso.isBlank()) return "";
        boolean ok = aviso.startsWith("ok|");
        String txt = aviso.contains("|") ? aviso.substring(aviso.indexOf('|') + 1) : aviso;
        return "<div class=\"aviso " + (ok ? "ok" : "err") + "\">" + txt + "</div>";
    }

    /** Formulario para subir el archivo del trimestre. */
    static String metaImportForm() {
        return "<form class=\"formbox\" method=\"post\" action=\"/metas/importar\" enctype=\"multipart/form-data\" style=\"margin:12px 0\">"
             + "<label>Importar trimestre (Excel o CSV)</label>"
             + "<input type=\"file\" name=\"archivo\" accept=\".xlsx,.csv\" required>"
             + "<p class=\"muted\" style=\"margin:6px 0 0\">Columnas reconocidas: <b>Periodo</b> (ej. 2026-3), "
             + "<b>COD</b> (ej. C181), <b>Nombre</b>, <b>Líder</b>, <b>Disc</b>, <b>Reset Líder</b>, <b>Reset Disc</b>. "
             + "Obligatorias: Periodo y COD. Se identifica por Periodo+COD: si ya existe, se actualiza. "
             + "El sexo (Hombres/Mujeres) y el equipo (Principal/Next) se deducen del código.</p>"
             + "<div style=\"margin-top:12px\"><button class=\"btn\">Subir</button> "
             + "<a class=\"btn sec\" href=\"/metas\">Cancelar</a></div></form>";
    }

    /** Formulario para cargar/editar a mano un grupo en un periodo. */
    static String metaManualForm(Meta m, String periodoDefault) {
        String per = m != null ? m.periodo : periodoDefault;
        String cod = m != null ? m.codigo : "";
        String nom = m != null ? m.nombre : "";
        int il = m != null ? m.insLider : 0, id = m != null ? m.insDisc : 0;
        int rl = m != null ? m.resLider : 0, rd = m != null ? m.resDisc : 0;
        return "<form id=\"manual\" class=\"formbox\" method=\"post\" action=\"/metas/guardar\" style=\"margin:12px 0\">"
             + "<label>Cargar / editar un grupo a mano</label>"
             + "<div class=\"mgrid\">"
             + "<div><span class=\"flab\">Periodo</span><input type=\"text\" name=\"periodo\" value=\"" + Html.esc(per) + "\" placeholder=\"2026-3\"></div>"
             + "<div><span class=\"flab\">COD</span><input type=\"text\" name=\"codigo\" value=\"" + Html.esc(cod) + "\" placeholder=\"C181\"" + (m != null ? " readonly" : "") + "></div>"
             + "<div class=\"wide\"><span class=\"flab\">Nombre / líder</span><input type=\"text\" name=\"nombre\" value=\"" + Html.esc(nom) + "\"></div>"
             + "<div><span class=\"flab\">Inscritos · Líder</span><input type=\"number\" name=\"ins_lider\" min=\"0\" value=\"" + il + "\"></div>"
             + "<div><span class=\"flab\">Inscritos · Disc</span><input type=\"number\" name=\"ins_disc\" min=\"0\" value=\"" + id + "\"></div>"
             + "<div><span class=\"flab\">Reset · Líder</span><input type=\"number\" name=\"res_lider\" min=\"0\" value=\"" + rl + "\"></div>"
             + "<div><span class=\"flab\">Reset · Disc</span><input type=\"number\" name=\"res_disc\" min=\"0\" value=\"" + rd + "\"></div>"
             + "</div>"
             + "<div style=\"margin-top:12px\"><button class=\"btn\">Guardar grupo</button> "
             + "<a class=\"btn sec\" href=\"/metas\">Cancelar</a></div></form>";
    }

    /** Tarjeta KPI con avance (k = [lider, disc, total, totalPrev]). */
    static String kpiMeta(String titulo, int[] k, String prev) {
        int total = k[2], tp = k[3];
        String avance = tp > 0 ? Math.round(100f * total / tp) + "%" : (total > 0 ? "Nuevo" : "—");
        StringBuilder b = new StringBuilder("<div class=\"card\">");
        b.append("<div class=\"kpi-top\"><span class=\"t\">").append(Html.esc(titulo)).append("</span>")
         .append("<span class=\"kpi-pct\">").append(avance).append("</span></div>");
        b.append("<div class=\"n\">").append(total).append("</div>");
        b.append("<div class=\"t\">en el periodo");
        if (tp > 0) b.append(" · ").append(tp).append(" en el anterior");
        b.append("</div>");
        int pct = tp > 0 ? Math.min(100, Math.round(100f * total / tp)) : (total > 0 ? 100 : 0);
        b.append("<div class=\"pbar\"><span style=\"width:").append(pct).append("%\"></span></div>");
        b.append("<div class=\"kpi-foot\">Líder ").append(k[0]).append("   Disc ").append(k[1]).append("</div>");
        b.append("</div>");
        return b.toString();
    }

    static String metaTabla(String titulo, List<FilaMeta> filas, String prev,
                            boolean admin, String sel, String equipo, String metrica) {
        StringBuilder b = new StringBuilder("<div><h2 class=\"meta-h2\">").append(Html.esc(titulo)).append("</h2>");
        b.append("<div class=\"wrap\"><table><thead><tr>")
         .append("<th>COD</th><th>Nombre</th><th class=\"c\">Ant.</th>")
         .append("<th class=\"c\">Líder</th><th class=\"c\">Disc</th><th class=\"c\">Total</th><th class=\"c\">Avance</th>")
         .append(admin ? "<th></th>" : "")
         .append("</tr></thead><tbody>");
        int cols = admin ? 8 : 7;
        if (filas.isEmpty()) {
            b.append("<tr><td colspan=\"").append(cols).append("\" class=\"muted\" style=\"text-align:center;padding:16px\">Sin datos en este periodo.</td></tr>");
        } else {
            for (FilaMeta f : filas) {
                b.append("<tr>")
                 .append(td(f.codigo))
                 .append("<td>").append(Html.esc(f.nombre)).append("</td>")
                 .append("<td class=\"c muted\">").append(f.totalPrev == 0 ? "—" : f.totalPrev).append("</td>")
                 .append("<td class=\"c\">").append(f.lider == 0 ? "" : f.lider).append("</td>")
                 .append("<td class=\"c\">").append(f.disc == 0 ? "" : f.disc).append("</td>")
                 .append("<td class=\"c\"><b>").append(f.total).append("</b></td>")
                 .append("<td class=\"c\">").append(avanceBadge(f.avance)).append("</td>");
                if (admin)
                    b.append("<td class=\"c\"><a class=\"glink\" href=\"/metas?form=1&edit=").append(Html.esc(f.codigo))
                     .append("&periodo=").append(sel).append("&equipo=").append(equipo).append("&metrica=").append(metrica)
                     .append("#manual\">Editar</a></td>");
                b.append("</tr>");
            }
        }
        b.append("</tbody></table></div></div>");
        return b.toString();
    }

    /** Colorea el % de avance (verde ≥100, ámbar 70-99, rojo <70). */
    static String avanceBadge(String av) {
        String cls = "mid";
        if ("Nuevo".equals(av)) cls = "ok";
        else if (av.endsWith("%")) {
            try { int v = Integer.parseInt(av.substring(0, av.length()-1));
                  cls = v >= 100 ? "ok" : v >= 70 ? "mid" : "low"; } catch (NumberFormatException ignored) {}
        } else cls = "na";
        return "<span class=\"av " + cls + "\">" + Html.esc(av) + "</span>";
    }

    static String graficoMetas(List<Object[]> porTrim) { return graficoMetas(porTrim, "Inscritos"); }

    /** Gráfica de metas: por trimestre (como viene) y por año (agregado). */
    static String graficoMetas(List<Object[]> porTrim, String titulo) {
        // Agregar por año
        Map<String, Long> anios = new LinkedHashMap<>();
        for (Object[] r : porTrim) {
            String anio = Periodo.anio(String.valueOf(r[0]));
            if (anio.isEmpty()) anio = String.valueOf(r[0]);
            anios.merge(anio, ((Number) r[1]).longValue(), Long::sum);
        }
        List<Object[]> porAnio = new ArrayList<>();
        for (Map.Entry<String, Long> e : anios.entrySet()) porAnio.add(new Object[]{e.getKey(), e.getValue()});
        // Etiquetas amigables para el eje por trimestre
        List<Object[]> trimEtq = new ArrayList<>();
        for (Object[] r : porTrim) trimEtq.add(new Object[]{Periodo.norm(String.valueOf(r[0])).isEmpty()
                ? String.valueOf(r[0]) : String.valueOf(r[0]), r[1]});

        StringBuilder b = new StringBuilder();
        b.append("<div class=\"chartbox\"><div class=\"chart-head\"><h2>").append(Html.esc(titulo)).append(" — comparativo</h2>")
         .append("<div class=\"tabs\">")
         .append("<button type=\"button\" class=\"tab on\" onclick=\"verTab(this,'m-trim')\">Por trimestre</button>")
         .append("<button type=\"button\" class=\"tab\" onclick=\"verTab(this,'m-anio')\">Por año</button>")
         .append("</div></div>");
        b.append("<div id=\"m-trim\" class=\"panel\">").append(svgBarras(trimEtq, "Trimestre")).append("</div>");
        b.append("<div id=\"m-anio\" class=\"panel oculto\">").append(svgBarras(porAnio, "Año")).append("</div>");
        b.append("</div>");
        b.append("""
            <script>
            function verTab(btn,id){
              var box=btn.closest('.chartbox');
              box.querySelectorAll('.tab').forEach(function(t){t.classList.remove('on');});
              btn.classList.add('on');
              box.querySelectorAll('.panel').forEach(function(p){p.classList.add('oculto');});
              var el=box.querySelector('#'+id); if(el) el.classList.remove('oculto');
            }
            </script>
            """);
        return b.toString();
    }

    // =================== USUARIOS (solo admin) ===================

    static void rutaUsuarios(HttpExchange ex, String metodo, String path, String usuario) throws Exception {
        Map<String, String> q = query(ex);
        try (Connection con = Db.open()) {
            if (path.equals("/usuarios/guardar") && metodo.equals("POST")) {
                Map<String, List<String>> f = leerForm(ex);
                String idS = val(f, "id"), username = val(f, "username"), clave = val(f, "clave");
                String rolForm = val(f, "rol");
                String rol = com.redsurco.web.model.Usuario.ADMIN.equals(rolForm) ? com.redsurco.web.model.Usuario.ADMIN
                        : com.redsurco.web.model.Usuario.REGISTRO.equals(rolForm) ? com.redsurco.web.model.Usuario.REGISTRO
                        : com.redsurco.web.model.Usuario.USUARIO;
                String grupo = com.redsurco.web.Txt.limpia(val(f, "grupo"));
                try {
                    if (idS.isBlank()) {
                        if (username.isBlank() || clave.isBlank()) { redirigir(ex, "/usuarios/form?err=1"); return; }
                        Auth.crearUsuario(con, username, clave, rol, grupo);
                    } else {
                        int id = Integer.parseInt(idS);
                        if (username.isBlank()) { redirigir(ex, "/usuarios/form?err=1&id=" + id); return; }
                        var actual = Auth.buscar(con, id);
                        // no dejar sin administradores
                        if (actual != null && actual.esAdmin() && !rol.equals(com.redsurco.web.model.Usuario.ADMIN)
                                && Auth.contarAdmins(con) <= 1) { redirigir(ex, "/usuarios?err=admin"); return; }
                        Auth.actualizar(con, id, username, rol, grupo);
                        if (!clave.isBlank()) Auth.resetPassword(con, id, clave);
                    }
                    redirigir(ex, "/usuarios");
                               } catch (Exception e) {
                    enviarHtml(ex, 200, Html.page("Usuario", usuario, true, "/usuarios",
                        "<h1>No se pudo guardar</h1>"
                        + "<div class=\"err\">Motivo real: " + Html.esc(String.valueOf(e.getMessage())) + "</div>"
                        + "<p><a class=\"btn\" href=\"/usuarios/form\">Volver</a></p>"));
                    return;
                }
                return;
            }
            
            if (path.equals("/usuarios/eliminar") && metodo.equals("POST")) {
                int id = Integer.parseInt(val(leerForm(ex), "id"));
                var u = Auth.buscar(con, id);
                if (u != null) {
                    if (u.username.equals(usuario)) { redirigir(ex, "/usuarios?err=yo"); return; }
                    if (u.esAdmin() && Auth.contarAdmins(con) <= 1) { redirigir(ex, "/usuarios?err=admin"); return; }
                    Auth.eliminar(con, id);
                }
                redirigir(ex, "/usuarios");
                return;
            }
            if (path.equals("/usuarios/form")) {
                com.redsurco.web.model.Usuario u = q.containsKey("id")
                        ? Auth.buscar(con, Integer.parseInt(q.get("id"))) : new com.redsurco.web.model.Usuario();
                if (u == null) u = new com.redsurco.web.model.Usuario();
                enviarHtml(ex, 200, Html.page("Usuario", usuario, true, "/usuarios", formUsuario(u, q.get("err"))));
                return;
            }
            // listado
            StringBuilder b = new StringBuilder("<h1>Usuarios</h1>");
            if ("admin".equals(q.get("err"))) b.append("<div class=\"err\">Debe quedar al menos un administrador.</div>");
            if ("yo".equals(q.get("err"))) b.append("<div class=\"err\">No puedes eliminar tu propia cuenta.</div>");
            b.append("<div class=\"bar\"><span class=\"sp\"></span><a class=\"btn\" href=\"/usuarios/form\">Nuevo usuario</a></div>");
            b.append("<div class=\"wrap\"><table><thead><tr><th>Usuario</th><th>Rol</th><th>Grupo</th><th></th></tr></thead><tbody>");
            for (com.redsurco.web.model.Usuario u : Auth.listar(con)) {
                b.append("<tr>").append(td(u.username)).append(td(u.rolLabel()))
                 .append(td(u.grupo == null || u.grupo.isBlank() ? "—" : u.grupo))
                 .append("<td class=\"acciones\"><a class=\"btn sec\" href=\"/usuarios/form?id=").append(u.id).append("\">Editar</a> ")
                 .append(formEliminar("/usuarios/eliminar", u.id, u.username)).append("</td></tr>");
            }
            b.append("</tbody></table></div>");
            enviarHtml(ex, 200, Html.page("Usuarios", usuario, true, "/usuarios", b.toString()));
        }
    }

    static String formUsuario(com.redsurco.web.model.Usuario u, String err) {
        boolean nuevo = u.id == 0;
        StringBuilder b = new StringBuilder("<h1>").append(nuevo ? "Nuevo usuario" : "Editar usuario").append("</h1>");
        if ("1".equals(err)) b.append("<div class=\"err\">Usuario y contraseña son obligatorios.</div>");
        if ("2".equals(err)) b.append("<div class=\"err\">No se pudo guardar. ¿El usuario ya existe?</div>");
        b.append("<form class=\"formbox\" method=\"post\" action=\"/usuarios/guardar\">")
         .append("<input type=\"hidden\" name=\"id\" value=\"").append(u.id).append("\">")
         .append("<label>Usuario</label><input type=\"text\" name=\"username\" value=\"").append(Html.esc(u.username)).append("\">")
         .append("<label>Rol</label><select name=\"rol\">")
         .append("<option value=\"USUARIO\"").append(com.redsurco.web.model.Usuario.USUARIO.equals(u.rol) ? " selected" : "").append(">Usuario (registro + reportes)</option>")
         .append("<option value=\"REGISTRO\"").append(u.soloRegistro() ? " selected" : "").append(">Conectar (solo registrar)</option>")
         .append("<option value=\"ADMIN\"").append(u.esAdmin() ? " selected" : "").append(">Administrador</option>")
         .append("</select>")
         .append("<p class=\"muted\" style=\"margin:4px 0 0;font-size:13px\">"
               + "<b>Conectar</b>: únicamente ve Personas para registrar y editar (no ve cursos, grupos, resumen, "
               + "metas ni exportar, y no puede eliminar); su grupo queda fijo en su código. <b>Usuario</b>: registro y "
               + "reportes, sin administración. <b>Administrador</b>: todo, incluidos usuarios, importar, papelera y respaldo.</p>")
         .append("<label>Código de grupo del usuario <span class=\"muted\">(opcional)</span></label>")
         .append("<input type=\"text\" name=\"grupo\" value=\"").append(Html.esc(u.grupo)).append("\" placeholder=\"Ej: C187\">")
         .append("<p class=\"muted\" style=\"margin:4px 0 0;font-size:13px\">Si lo llenas, cuando este usuario registre personas "
               + "se guardarán en ese grupo. Para el rol <b>Conectar</b>, el grupo queda <b>fijo</b> en su código.</p>")
         .append("<label>").append(nuevo ? "Contraseña" : "Nueva contraseña (dejar en blanco para no cambiarla)").append("</label>")
         .append("<div class=\"clave-row\">")
         .append("<input type=\"text\" name=\"clave\" id=\"clave\" autocomplete=\"off\" placeholder=\"")
         .append(nuevo ? "Escribe o genera una contraseña" : "Dejar en blanco para no cambiarla").append("\">")
         .append("<button type=\"button\" class=\"btn sec\" onclick=\"generarClave()\">Generar</button>")
         .append("</div>")
         .append("<p class=\"muted\" style=\"margin:4px 0 0;font-size:13px\">La contraseña se muestra para que la copies "
               + "y se la des al usuario. Él entra con su <b>nombre de usuario</b> y esta contraseña.</p>")
         .append("<div style=\"margin-top:16px\"><button class=\"btn\">Guardar</button> <a class=\"btn sec\" href=\"/usuarios\">Cancelar</a></div></form>")
         .append("<script>function generarClave(){var c='ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';"
               + "var s='';for(var i=0;i<8;i++)s+=c.charAt(Math.floor(Math.random()*c.length));"
               + "document.getElementById('clave').value=s;}</script>");
        return b.toString();
    }

    /** Tabla "Inscritos por curso": cada curso enlaza a la lista de sus personas. filas=[id,nombre,cant]. */
    static String tablaInscritosCurso(List<Object[]> filas) {
        StringBuilder b = new StringBuilder("<div><h2>Inscritos por curso</h2><div class=\"wrap\"><table>");
        b.append("<thead><tr><th>Curso</th><th class=\"c\">Inscritos</th></tr></thead><tbody>");
        for (Object[] f : filas) {
            String id = String.valueOf(f[0]);
            String nombre = f[1] == null ? "" : f[1].toString();
            String cant = String.valueOf(f[2]);
            b.append("<tr>")
             .append("<td><a class=\"glink\" href=\"/personas?curso=").append(id).append("\">")
             .append(Html.esc(nombre)).append("</a></td>")
             .append("<td class=\"c\">").append(cant).append("</td></tr>");
        }
        b.append("</tbody></table></div></div>");
        return b.toString();
    }

    static String tablaResumen(String titulo, String[] cols, List<Object[]> filas) {
        StringBuilder b = new StringBuilder("<div><h2>").append(Html.esc(titulo)).append("</h2><div class=\"wrap\"><table><thead><tr>");
        for (String c : cols) b.append("<th>").append(Html.esc(c)).append("</th>");
        b.append("</tr></thead><tbody>");
        for (Object[] f : filas) {
            b.append("<tr>");
            for (Object o : f) b.append(td(o == null ? "" : o.toString()));
            b.append("</tr>");
        }
        b.append("</tbody></table></div></div>");
        return b.toString();
    }

    /** Gráfica comparativa de "ganados" con pestañas Semana / Mes / Año. */
    static String graficoComparativo(ResumenDao r) {
        List<Object[]> sem = r.ganadosPorSemana();
        List<Object[]> mes = r.ganadosPorMes();
        List<Object[]> anio = r.ganadosPorAnio();
        boolean vacio = sem.isEmpty() && mes.isEmpty() && anio.isEmpty();
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"chartbox\"><div class=\"chart-head\"><h2>Ganados — comparativo</h2>")
         .append("<div class=\"tabs\">")
         .append("<button type=\"button\" class=\"tab on\" onclick=\"verTab(this,'tab-sem')\">Por semana</button>")
         .append("<button type=\"button\" class=\"tab\" onclick=\"verTab(this,'tab-mes')\">Por mes</button>")
         .append("<button type=\"button\" class=\"tab\" onclick=\"verTab(this,'tab-anio')\">Por año</button>")
         .append("</div></div>");
        if (vacio) {
            b.append("<p class=\"muted\" style=\"padding:8px 2px 2px\">Aún no hay registros con fecha. La gráfica se irá llenando "
                   + "a medida que agregues o importes personas nuevas (cada registro guarda su fecha).</p>");
        } else {
            b.append("<div id=\"tab-sem\" class=\"panel\">").append(svgBarras(sem, "Semana")).append("</div>");
            b.append("<div id=\"tab-mes\" class=\"panel oculto\">").append(svgBarras(mes, "Mes")).append("</div>");
            b.append("<div id=\"tab-anio\" class=\"panel oculto\">").append(svgBarras(anio, "Año")).append("</div>");
        }
        b.append("</div>");
        b.append("""
            <script>
            function verTab(btn,id){
              var box=btn.closest('.chartbox');
              box.querySelectorAll('.tab').forEach(function(t){t.classList.remove('on');});
              btn.classList.add('on');
              box.querySelectorAll('.panel').forEach(function(p){p.classList.add('oculto');});
              var el=box.querySelector('#'+id); if(el) el.classList.remove('oculto');
            }
            </script>
            """);
        return b.toString();
    }

    /** Gráfico de barras en SVG (sin librerías). datos: [periodo, cantidad]. */
    static String svgBarras(List<Object[]> datos, String unidad) {
        if (datos.isEmpty())
            return "<p class=\"muted\" style=\"padding:6px 2px\">Sin datos por " + Html.esc(unidad.toLowerCase()) + " todavía.</p>";
        long max = 1;
        for (Object[] d : datos) max = Math.max(max, ((Number) d[1]).longValue());
        int n = datos.size();
        int bw = 54, gap = 22, padL = 44, padR = 16, padT = 18, padB = 46;
        int w = padL + padR + n * bw + (n - 1) * gap;
        int h = 260, chartH = h - padT - padB;
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"svgwrap\"><svg viewBox=\"0 0 ").append(w).append(' ').append(h)
         .append("\" preserveAspectRatio=\"xMinYMin meet\" role=\"img\">");
        // líneas guía y eje Y (0, mitad, max)
        long[] marcas = { 0, (max + 1) / 2, max };
        for (long mv : marcas) {
            int y = padT + chartH - (int) Math.round((double) mv / max * chartH);
            s.append("<line x1=\"").append(padL).append("\" y1=\"").append(y)
             .append("\" x2=\"").append(w - padR).append("\" y2=\"").append(y).append("\" class=\"grid\"/>");
            s.append("<text x=\"").append(padL - 8).append("\" y=\"").append(y + 4)
             .append("\" class=\"yl\" text-anchor=\"end\">").append(mv).append("</text>");
        }
        int x = padL;
        for (Object[] d : datos) {
            long v = ((Number) d[1]).longValue();
            int bh = (int) Math.round((double) v / max * chartH);
            int y = padT + chartH - bh;
            s.append("<rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"").append(bw)
             .append("\" height=\"").append(bh).append("\" rx=\"6\" class=\"bar\"><title>")
             .append(Html.esc(String.valueOf(d[0]))).append(": ").append(v).append("</title></rect>");
            s.append("<text x=\"").append(x + bw / 2).append("\" y=\"").append(y - 6)
             .append("\" class=\"vl\" text-anchor=\"middle\">").append(v).append("</text>");
            s.append("<text x=\"").append(x + bw / 2).append("\" y=\"").append(h - padB + 18)
             .append("\" class=\"xl\" text-anchor=\"middle\">").append(Html.esc(String.valueOf(d[0]))).append("</text>");
            x += bw + gap;
        }
        s.append("</svg></div>");
        return s.toString();
    }

    // =================== EXPORTAR EXCEL ===================

    static void rutaExportar(HttpExchange ex) throws Exception {
        try (Connection con = Db.open()) {
            PersonaDao pdao = new PersonaDao(con);
            ResumenDao r = new ResumenDao(con);
            XlsxWriter wb = new XlsxWriter();
            XlsxWriter.Sheet h = wb.addSheet("Personas");
            h.addRow("Grupo", "Tipo", "Documento", "Nombres completos", "Celular", "Correo",
                    "Seguimiento", "Observaciones", "Reset", "N° Cursos", "Cursos estudiados", "Líder");
            for (Persona p : pdao.findAll("")) {
                h.addRow(p.grupoNombre, p.tipoLabel(), p.numDocumento, p.nombresCompletos,
                        p.celular, p.correo, p.seguimientoTexto(), p.observaciones == null ? "" : p.observaciones,
                        p.resetLabel(), p.numCursos, p.cursosTexto, p.grupoLider);
            }
            XlsxWriter.Sheet h2 = wb.addSheet("Inscritos por curso");
            h2.addRow("Curso", "Inscritos"); for (Object[] f : r.inscritosPorCurso()) h2.addRow(f);
            XlsxWriter.Sheet h3 = wb.addSheet("Cursos por persona");
            h3.addRow("Persona", "Grupo", "N° Cursos"); for (Object[] f : r.cursosPorPersona()) h3.addRow(f);
            XlsxWriter.Sheet h4 = wb.addSheet("Personas por grupo");
            h4.addRow("Grupo", "Líder", "Personas"); for (Object[] f : r.personasPorGrupo()) h4.addRow(f);

            // Ganados por periodo (semana / mes / año), para el comparativo
            XlsxWriter.Sheet h5 = wb.addSheet("Ganados por semana");
            h5.addRow("Semana (año-S##)", "Ganados"); for (Object[] f : r.ganadosPorSemana()) h5.addRow(f);
            XlsxWriter.Sheet h6 = wb.addSheet("Ganados por mes");
            h6.addRow("Mes (año-mes)", "Ganados"); for (Object[] f : r.ganadosPorMes()) h6.addRow(f);
            XlsxWriter.Sheet h7 = wb.addSheet("Ganados por año");
            h7.addRow("Año", "Ganados"); for (Object[] f : r.ganadosPorAnio()) h7.addRow(f);

            // Metas: inscritos por grupo y periodo (Líder/Disc/Total)
            XlsxWriter.Sheet h8 = wb.addSheet("Metas por periodo");
            h8.addRow("Periodo", "COD", "Nombre", "Sexo", "Equipo",
                      "Ins Líder", "Ins Disc", "Ins Total", "Reset Líder", "Reset Disc", "Reset Total");
            for (com.redsurco.web.model.Meta m : new MetasDao(con).todas()) {
                String sexo = Periodo.sexoDeCodigo(m.codigo);
                h8.addRow(m.periodo, m.codigo, m.nombre,
                        "H".equals(sexo) ? "Hombres" : "M".equals(sexo) ? "Mujeres" : "—",
                        Periodo.esNext(m.codigo) ? "Next" : "Principal",
                        m.insLider, m.insDisc, m.insTotal(), m.resLider, m.resDisc, m.resTotal());
            }

            File tmp = File.createTempFile("red-surco", ".xlsx");
            wb.write(tmp);
            byte[] bytes = Files.readAllBytes(tmp.toPath());
            tmp.delete();
            ex.getResponseHeaders().add("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"red-surco.xlsx\"");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    // =================== IMPORTAR (solo admin) ===================

    static void rutaImportar(HttpExchange ex, String metodo, String usuario) throws Exception {
        if (metodo.equals("POST")) {
            byte[][] archivo = leerArchivoSubido(ex);  // [0]=nombre, [1]=contenido
            String nombre = archivo == null ? "" : new String(archivo[0], java.nio.charset.StandardCharsets.UTF_8);
            byte[] datos = archivo == null ? null : archivo[1];
            String aviso;
            if (datos == null || datos.length == 0) {
                aviso = "<div class=\"err\">No se recibió ningún archivo.</div>";
            } else if (nombre.toLowerCase().endsWith(".pdf")) {
                aviso = "<div class=\"err\">El PDF no se puede importar automáticamente. Exporta tu lista a "
                      + "<b>Excel (.xlsx)</b> o <b>CSV</b> con las mismas columnas y vuelve a subirla.</div>";
            } else {
                try {
                    List<List<String>> filas;
                    if (nombre.toLowerCase().endsWith(".csv")) {
                        filas = Importador.leerCsv(new String(datos, java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        filas = XlsxReader.leer(datos);   // .xlsx
                    }
                    Importador.Resultado r;
                    try (Connection con = Db.open()) { r = Importador.importar(con, filas); }
                    aviso = resumenImportacion(r, nombre);
                } catch (Exception e) {
                    aviso = "<div class=\"err\">No se pudo leer el archivo: " + Html.esc(String.valueOf(e.getMessage()))
                          + "<br>Asegúrate de que sea un .xlsx o .csv con una fila de encabezados.</div>";
                }
            }
            enviarHtml(ex, 200, Html.page("Importar", usuario, true, "/importar", paginaImportar(aviso)));
            return;
        }
        enviarHtml(ex, 200, Html.page("Importar", usuario, true, "/importar", paginaImportar("")));
    }

    static String paginaImportar(String aviso) {
        return "<h1>Importar lista (Excel o CSV)</h1>"
            + aviso
            + "<p class=\"muted\" style=\"max-width:720px\">Sube un archivo <b>.xlsx</b> o <b>.csv</b> con una fila de "
            + "encabezados. Columnas reconocidas: <b>Grupo, Líder, Tipo, Documento, Nombres completos, Celular, "
            + "Correo, Cursos estudiados, Reset</b> (los cursos separados por coma). Obligatorias: <b>Documento</b> y "
            + "<b>Nombres completos</b>. Las personas se identifican por su documento: si ya existen se actualizan, "
            + "si no, se agregan. Los grupos y cursos que no existan se crean. <b>No se borra nada.</b></p>"
            + "<form class=\"formbox\" method=\"post\" action=\"/importar\" enctype=\"multipart/form-data\">"
            + "<label>Archivo (.xlsx o .csv)</label>"
            + "<input type=\"file\" name=\"archivo\" accept=\".xlsx,.csv\" required>"
            + "<div style=\"margin-top:16px\"><button class=\"btn\">Importar y actualizar</button> "
            + "<a class=\"btn sec\" href=\"/exportar\">Descargar plantilla (export actual)</a></div></form>";
    }

    static String resumenImportacion(Importador.Resultado r, String nombre) {
        StringBuilder b = new StringBuilder();
        boolean algo = r.nuevos + r.actualizados + r.cursosAgregados > 0;
        b.append("<div class=\"").append(algo ? "okbox" : "err").append("\">")
         .append("Importación de <b>").append(Html.esc(nombre)).append("</b>: ")
         .append("<b>").append(r.nuevos).append("</b> nuevas, ")
         .append("<b>").append(r.actualizados).append("</b> actualizadas, ")
         .append("<b>").append(r.cursosAgregados).append("</b> cursos agregados, ")
         .append("<b>").append(r.gruposCreados).append("</b> grupos creados");
        if (r.filasIgnoradas > 0) b.append(", ").append(r.filasIgnoradas).append(" filas omitidas");
        b.append(".</div>");
        if (!r.avisos.isEmpty()) {
            b.append("<details style=\"margin:10px 0\"><summary>Ver detalles (").append(r.avisos.size()).append(")</summary><ul>");
            int n = 0;
            for (String a : r.avisos) { if (n++ >= 50) { b.append("<li>…</li>"); break; } b.append("<li>").append(Html.esc(a)).append("</li>"); }
            b.append("</ul></details>");
        }
        return b.toString();
    }

    /** Extrae el primer archivo de un envío multipart/form-data: [nombre, contenido]. */
    static byte[][] leerArchivoSubido(HttpExchange ex) throws IOException {
        List<String> ct = ex.getRequestHeaders().get("Content-Type");
        if (ct == null || ct.isEmpty() || !ct.get(0).contains("multipart/form-data")) return null;
        String header = ct.get(0);
        int bi = header.indexOf("boundary=");
        if (bi < 0) return null;
        String boundary = header.substring(bi + 9).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"")) boundary = boundary.substring(1, boundary.length() - 1);
        byte[] body = ex.getRequestBody().readAllBytes();
        return extraerArchivoMultipart(body, boundary);
    }

    /** Lógica pura de extracción (testeable): primer archivo de un cuerpo multipart. */
    static byte[][] extraerArchivoMultipart(byte[] body, String boundary) {
        byte[] delim = ("--" + boundary).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] crlf2 = "\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int inicioHeaders = pos + delim.length;
            // fin de la sección de encabezados de la parte
            int finHeaders = indexOf(body, crlf2, inicioHeaders);
            if (finHeaders < 0) break;
            String headers = new String(body, inicioHeaders, finHeaders - inicioHeaders,
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            int inicioContenido = finHeaders + crlf2.length;
            int siguiente = indexOf(body, delim, inicioContenido);
            if (siguiente < 0) break;
            int finContenido = siguiente - 2; // quita el \r\n antes del delimitador
            if (finContenido < inicioContenido) finContenido = inicioContenido;

            if (headers.toLowerCase().contains("filename=")) {
                String fn = entre(headers, "filename=\"", "\"");
                if (fn != null && !fn.isBlank()) {
                    byte[] contenido = java.util.Arrays.copyOfRange(body, inicioContenido, finContenido);
                    return new byte[][]{ fn.getBytes(java.nio.charset.StandardCharsets.UTF_8), contenido };
                }
            }
            pos = siguiente;
        }
        return null;
    }

    private static String entre(String s, String a, String b) {
        int i = s.indexOf(a); if (i < 0) return null;
        int j = s.indexOf(b, i + a.length()); if (j < 0) return null;
        return s.substring(i + a.length(), j);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    // =================== UTILIDADES ===================

    static String raiz(String path) {
        int i = path.indexOf('/', 1);
        return i < 0 ? path : path.substring(0, i);
    }

    private static byte[] logoCache;
    /** Carga el logo (recurso dentro del jar) una sola vez. */
    static synchronized byte[] logo() {
        if (logoCache == null) {
            try (InputStream in = Server.class.getResourceAsStream("/logo.png")) {
                logoCache = in == null ? new byte[0] : in.readAllBytes();
            } catch (IOException e) { logoCache = new byte[0]; }
        }
        return logoCache;
    }

    static String td(String s) { return "<td>" + Html.esc(s) + "</td>"; }
    static String badgeReset(boolean r) {
        return r ? "<td><span class=\"badge yes\">Sí</span></td>"
                 : "<td><span class=\"badge no\">No</span></td>";
    }
    static String badgeSeguimiento(Persona p) {
        return "<td><span class=\"badge " + p.seguimientoClase() + "\">"
                + Html.esc(p.seguimientoTexto()) + "</span></td>";
    }
    static String opt(String v, String sel) {
        return "<option value=\"" + Html.esc(v) + "\"" + (v.equals(sel == null ? "" : sel) ? " selected" : "") + ">"
                + (v.isEmpty() ? "—" : Html.esc(v)) + "</option>";
    }
    static String card(int n, String t) { return card(n, t, null); }

    /** Tarjeta; si 'href' no es null, es un enlace clickeable (para filtrar). */
    static String card(int n, String t, String href) {
        String cuerpo = "<div class=\"n\">" + n + "</div><div class=\"t\">" + Html.esc(t) + "</div>";
        if (href == null) return "<div class=\"card\">" + cuerpo + "</div>";
        return "<a class=\"card link\" href=\"" + href + "\">" + cuerpo + "<span class=\"ver\">Ver &rarr;</span></a>";
    }
    static String formEliminar(String action, int id, String nombre) {
        return "<form class=\"inline\" method=\"post\" action=\"" + action + "\" onsubmit=\"return confirm('¿Eliminar "
                + Html.esc(nombre) + "?')\"><input type=\"hidden\" name=\"id\" value=\"" + id
                + "\"><button class=\"btn danger\">Eliminar</button></form>";
    }
    static String idParam(Map<String, List<String>> f) {
        String id = val(f, "id");
        return id.isBlank() ? "" : "&id=" + id;
    }

    static void enviar(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }
    static void enviarHtml(HttpExchange ex, int status, String html) throws IOException {
        enviar(ex, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }
    static void redirigir(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    static String cookie(HttpExchange ex, String nombre) {
        List<String> hs = ex.getRequestHeaders().get("Cookie");
        if (hs == null) return null;
        for (String h : hs) for (String part : h.split(";")) {
            String s = part.trim();
            if (s.startsWith(nombre + "=")) return s.substring(nombre.length() + 1);
        }
        return null;
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i < 0) m.put(dec(p), "");
            else m.put(dec(p.substring(0, i)), dec(p.substring(i + 1)));
        }
        return m;
    }

    static Map<String, List<String>> leerForm(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, List<String>> m = new HashMap<>();
        String s = new String(body, StandardCharsets.UTF_8);
        if (s.isBlank()) return m;
        for (String p : s.split("&")) {
            int i = p.indexOf('=');
            String k = dec(i < 0 ? p : p.substring(0, i));
            String v = i < 0 ? "" : dec(p.substring(i + 1));
            m.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return m;
    }

    static String val(Map<String, List<String>> f, String k) {
        List<String> l = f.get(k);
        return (l == null || l.isEmpty()) ? "" : l.get(0).trim();
    }
    static String dec(String s) { return URLDecoder.decode(s.replace("+", " "), StandardCharsets.UTF_8); }
}
