package com.redsurco.web;

/** Plantillas HTML y utilidades de presentación (tema morado). */
public final class Html {

    private Html() { }

    /** Escapa texto para insertarlo con seguridad en HTML. */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Envuelve el contenido en la página completa (barra lateral + estilos). */
    public static String page(String titulo, String usuario, boolean admin, String activo, String contenido) {
        return page(titulo, usuario, admin, false, "", activo, contenido);
    }

    public static String page(String titulo, String usuario, boolean admin, boolean soloRegistro,
                              String activo, String contenido) {
        return page(titulo, usuario, admin, soloRegistro, "", activo, contenido);
    }

    public static String page(String titulo, String usuario, boolean admin, boolean soloRegistro,
                              String grupo, String activo, String contenido) {
        String nav;
        if (soloRegistro) {
            // El equipo de conectar ve Personas (registrar) y su propio resumen
            nav = navItem("/personas", "Personas", "users", activo)
                + navItem("/mi-resumen", "Mi resumen", "chart", activo);
        } else {
            nav = navItem("/personas", "Personas", "users", activo)
                   + navItem("/cursos", "Cursos", "book", activo)
                   + navItem("/grupos", "Grupos", "group", activo)
                   + navItem("/resumen", "Resumen", "chart", activo)
                   + navItem("/metas", "Metas", "target", activo)
                   + navItem("/exportar", "Exportar", "download", activo)
                   + (admin ? navItem("/importar", "Importar", "upload", activo) : "")
                   + (admin ? navItem("/usuarios", "Usuarios", "shield", activo) : "");
        }
        return """
            <!doctype html><html lang="es"><head>
            <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>%s — Red Surco</title>
            <link rel="icon" href="/logo.png">
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap">
            <link rel="stylesheet" href="/style.css"></head>
            <body><div class="app">
              <aside class="side">
                <div class="side-brand"><img src="/logo.png" alt="Red Surco"></div>
                <nav class="side-nav">%s</nav>
                <div class="side-foot">
                  <div class="who"><span class="avatar">%s</span>
                    <div class="who-txt"><div class="who-name">%s</div><div class="who-rol">%s</div>%s</div></div>
                  <a class="salir" href="/logout">%s<span>Salir</span></a>
                </div>
              </aside>
              <div class="content"><main>%s</main>
                <footer class="pie">Red Surco · Sistema de registro de personas y cursos bíblicos</footer>
              </div>
            </div></body></html>
            """.formatted(esc(titulo), nav, inicial(usuario), esc(usuario == null ? "" : usuario),
                          admin ? "Administrador" : soloRegistro ? "Conectar" : "Usuario",
                          (grupo == null || grupo.isBlank()) ? "" : "<div class=\"who-grupo\">Grupo " + esc(grupo) + "</div>",
                          ico("logout"), contenido);
    }

    private static String navItem(String href, String texto, String icon, String activo) {
        String cls = href.equals(activo) ? " active" : "";
        return "<a class=\"nav-link" + cls + "\" href=\"" + href + "\">" + ico(icon)
             + "<span>" + esc(texto) + "</span></a>";
    }

    private static String inicial(String usuario) {
        return (usuario == null || usuario.isBlank()) ? "?" : esc(usuario.substring(0, 1).toUpperCase());
    }

    /** Íconos SVG (trazo, heredan el color). */
    static String ico(String n) {
        String p = switch (n) {
            case "users"    -> "<circle cx='9' cy='7' r='4'/><path d='M3 21v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2'/><path d='M16 3.1a4 4 0 0 1 0 7.8'/><path d='M21 21v-2a4 4 0 0 0-3-3.8'/>";
            case "book"     -> "<path d='M4 19.5A2.5 2.5 0 0 1 6.5 17H20'/><path d='M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z'/>";
            case "group"    -> "<circle cx='12' cy='7' r='3'/><circle cx='5' cy='17' r='2.5'/><circle cx='19' cy='17' r='2.5'/><path d='M12 10v3M12 13l-5 2M12 13l5 2'/>";
            case "chart"    -> "<path d='M3 3v18h18'/><rect x='7' y='11' width='3' height='6'/><rect x='12' y='7' width='3' height='10'/><rect x='17' y='13' width='3' height='4'/>";
            case "download" -> "<path d='M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4'/><path d='M7 10l5 5 5-5'/><path d='M12 15V3'/>";
            case "upload"   -> "<path d='M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4'/><path d='M7 9l5-5 5 5'/><path d='M12 4v12'/>";
            case "shield"   -> "<path d='M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z'/>";
            case "target"   -> "<circle cx='12' cy='12' r='9'/><circle cx='12' cy='12' r='5'/><circle cx='12' cy='12' r='1.5'/>";
            case "hombre"   -> "<circle cx='10' cy='14' r='5'/><path d='M15 9l5-5M20 9V4h-5'/>";
            case "mujer"    -> "<circle cx='12' cy='9' r='5'/><path d='M12 14v7M9 18h6'/>";
            case "logout"   -> "<path d='M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4'/><path d='M16 17l5-5-5-5'/><path d='M21 12H9'/>";
            default          -> "";
        };
        return "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' "
             + "stroke-linecap='round' stroke-linejoin='round'>" + p + "</svg>";
    }

    public static String css() {
        return """
            :root{--morado:#6A1B9A;--morado-osc:#4A148C;--cl:#E1BEE7;--sel:#F3E7FA;
                  --borde:#E4DBEF;--bg:#F5F3FA;--ink:#2a2135;--muted:#7a7390;--ring:rgba(106,27,154,.18);
                  --ok:#1b7f4b;--ok-bg:#e7f6ec;--ok-bd:#bfe4cc;--side1:#4A148C;--side2:#2b0b52;}
            *{box-sizing:border-box}
            html{scroll-behavior:smooth}
            body{margin:0;font-family:'Inter',system-ui,Segoe UI,Roboto,Arial,sans-serif;color:var(--ink);
                 background:var(--bg);font-size:14px;-webkit-font-smoothing:antialiased}
            a{color:var(--morado);text-decoration:none}

            /* ---- layout ---- */
            .app{display:flex;min-height:100vh}
            .side{width:232px;flex:0 0 232px;background:linear-gradient(180deg,var(--side1),var(--side2));
                  color:#eadff7;display:flex;flex-direction:column;position:sticky;top:0;height:100vh}
            .side-brand{padding:18px 18px 10px;text-align:center}
            .side-brand img{width:100%;max-width:168px;filter:drop-shadow(0 0 10px rgba(170,70,230,.4))}
            .side-nav{display:flex;flex-direction:column;gap:3px;padding:8px 12px;flex:1;overflow:auto}
            .nav-link{display:flex;align-items:center;gap:11px;color:#d7c6ec;padding:10px 12px;border-radius:10px;
                      font-weight:500;transition:background .15s,color .15s}
            .nav-link svg{width:19px;height:19px;flex:0 0 19px;opacity:.92}
            .nav-link:hover{background:rgba(255,255,255,.1);color:#fff}
            .nav-link.active{background:#fff;color:var(--morado);box-shadow:0 3px 10px rgba(0,0,0,.18)}
            .side-foot{padding:12px;border-top:1px solid rgba(255,255,255,.12)}
            .who{display:flex;align-items:center;gap:10px;margin-bottom:10px}
            .avatar{width:36px;height:36px;border-radius:50%;background:rgba(255,255,255,.18);color:#fff;
                    display:flex;align-items:center;justify-content:center;font-weight:700}
            .who-name{font-weight:600;color:#fff;font-size:13px}
            .who-rol{font-size:11px;color:var(--cl)}
            .salir{display:flex;align-items:center;gap:8px;color:#d7c6ec;padding:8px 10px;border-radius:9px;font-size:13px}
            .salir svg{width:17px;height:17px}
            .salir:hover{background:rgba(255,255,255,.1);color:#fff}

            .content{flex:1;min-width:0;display:flex;flex-direction:column}
            main{padding:24px 28px;flex:1;animation:fade .25s ease}
            @keyframes fade{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}
            .pie{padding:14px 28px;color:var(--muted);font-size:12px;border-top:1px solid var(--borde)}
            h1{font-size:22px;font-weight:700;color:var(--ink);margin:0 0 18px}
            h2{font-size:15px;font-weight:600;color:var(--morado-osc);margin:16px 0 8px}
            .bar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:16px}
            .bar .sp{flex:1}

            /* ---- campos ---- */
            input[type=text],input[type=password],input[type=search],select{
                padding:9px 12px;border:1px solid var(--borde);border-radius:9px;font-size:14px;background:#fff;
                font-family:inherit;color:var(--ink);transition:border-color .15s,box-shadow .15s}
            input:focus,select:focus{outline:none;border-color:var(--morado);box-shadow:0 0 0 3px var(--ring)}
            input::placeholder{color:#b4acc2}

            /* ---- botones ---- */
            .btn{background:var(--morado);color:#fff;border:0;border-radius:9px;padding:9px 16px;font-size:14px;
                 font-weight:500;font-family:inherit;cursor:pointer;display:inline-flex;align-items:center;gap:7px;
                 transition:background .15s,transform .07s,box-shadow .15s}
            .btn:hover{background:var(--morado-osc);box-shadow:0 4px 12px rgba(74,20,140,.28)}
            .btn:active{transform:translateY(1px)}
            .btn.sec{background:#fff;color:var(--morado);border:1px solid #d7c4e8}
            .btn.sec:hover{background:var(--sel);box-shadow:none}
            .btn.danger{background:#c0392b}.btn.danger:hover{background:#a5281b}

            /* ---- tabla ---- */
            .wrap{overflow:auto;border:1px solid var(--borde);border-radius:14px;background:#fff;
                  box-shadow:0 1px 3px rgba(30,10,60,.05)}
            table{border-collapse:collapse;width:100%;font-size:13.5px}
            th{background:#faf7fd;color:var(--morado-osc);text-align:left;padding:12px;position:sticky;top:0;
               white-space:nowrap;font-weight:600;border-bottom:1px solid var(--borde)}
            td{padding:11px 12px;border-bottom:1px solid #f1ecf7;vertical-align:middle;
               word-break:break-word;overflow-wrap:anywhere}
            tbody tr{transition:background .12s}
            tbody tr:last-child td{border-bottom:0}
            tbody tr:hover td{background:var(--sel)}
            td.acciones{white-space:nowrap}
            .count{font-weight:600;color:var(--muted);margin-top:12px;font-size:13px}
            form.inline{display:inline}

            /* ---- badges ---- */
            .badge{display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;font-weight:600;white-space:nowrap}
            .badge.yes{background:var(--ok-bg);color:var(--ok);border:1px solid var(--ok-bd)}
            .badge.no{background:#fdecee;color:#b3261e;border:1px solid #f3c2c7}
            .badge.warn{background:#fff6e5;color:#9a6a00;border:1px solid #f3dca6}

            /* ---- resumen ---- */
            .cards{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:8px}
            @media(max-width:800px){.cards{grid-template-columns:repeat(2,1fr)}}
            .card{background:#fff;border:1px solid var(--borde);border-radius:14px;padding:16px 18px;
                  box-shadow:0 1px 3px rgba(30,10,60,.05);transition:transform .12s,box-shadow .15s}
            .card:hover{transform:translateY(-2px);box-shadow:0 6px 18px rgba(74,20,140,.12)}
            .card .n{font-size:30px;font-weight:700;color:var(--morado);line-height:1.1}
            .card .t{font-size:12px;color:var(--muted);margin-top:4px;font-weight:500}
            .card.link{display:block;position:relative;color:inherit;cursor:pointer}
            .card.link .ver{position:absolute;top:12px;right:14px;font-size:11px;font-weight:600;color:var(--morado);
                            opacity:0;transition:opacity .15s}
            .card.link:hover{border-color:var(--morado)}
            .card.link:hover .ver{opacity:1}
            .grid3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px}
            @media(max-width:1000px){.grid3{grid-template-columns:1fr}}
            /* Grupo clickeable y detalle de grupo */
            .glink{color:var(--morado);font-weight:600;text-decoration:none}
            .glink:hover{text-decoration:underline}
            .grupo-head{display:flex;gap:28px;flex-wrap:wrap;background:#fff;border:1px solid var(--borde);
                        border-radius:14px;padding:14px 18px;margin-bottom:16px}
            .grupo-head>div{display:flex;flex-direction:column;gap:2px}
            .grupo-head .gk{font-size:12px;color:var(--muted);font-weight:500}
            .grupo-head .gv{font-size:18px;font-weight:700;color:var(--morado-osc)}
            /* Gráfica comparativa */
            .chartbox{background:#fff;border:1px solid var(--borde);border-radius:16px;padding:16px 18px;margin:8px 0 18px}
            .chart-head{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;margin-bottom:6px}
            .chart-head h2{margin:0;font-size:17px;color:var(--morado-osc)}
            .tabs{display:inline-flex;gap:4px;background:var(--sel);border-radius:10px;padding:4px}
            .tab{border:none;background:transparent;color:var(--morado);font-weight:600;font-size:13px;
                 padding:7px 14px;border-radius:8px;cursor:pointer;font-family:inherit}
            .tab.on{background:var(--morado);color:#fff;box-shadow:0 2px 8px rgba(74,20,140,.25)}
            .panel.oculto{display:none}
            .svgwrap{overflow-x:auto;padding-top:6px}
            .svgwrap svg{height:260px;max-width:100%;min-width:320px}
            .svgwrap .bar{fill:var(--morado);transition:fill .12s}
            .svgwrap .bar:hover{fill:var(--morado-osc)}
            .svgwrap .grid{stroke:#eee4f5;stroke-width:1}
            .svgwrap .yl{fill:var(--muted);font-size:11px}
            .svgwrap .xl{fill:var(--ink);font-size:11px}
            .svgwrap .vl{fill:var(--morado-osc);font-size:12px;font-weight:700}
            /* Metas: KPIs con avance */
            .cards3{grid-template-columns:repeat(3,1fr)}
            @media(max-width:800px){.cards3{grid-template-columns:1fr}}
            .kpi-top{display:flex;align-items:baseline;justify-content:space-between}
            .kpi-pct{font-size:26px;font-weight:800;color:var(--morado)}
            .pbar{height:8px;background:var(--sel);border-radius:6px;overflow:hidden;margin:10px 0 8px}
            .pbar>span{display:block;height:100%;background:var(--morado);border-radius:6px}
            .kpi-foot{font-size:12px;color:var(--muted);font-weight:600;letter-spacing:.02em}
            .grid2{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:4px}
            @media(max-width:1000px){.grid2{grid-template-columns:1fr}}
            .meta-h2{font-size:16px;color:var(--morado-osc);margin:4px 0 8px}
            th.c,td.c{text-align:center}
            td.c.muted{color:var(--muted)}
            .av{display:inline-block;min-width:46px;padding:2px 8px;border-radius:999px;font-weight:700;font-size:12px}
            .av.ok{background:var(--ok-bg);color:var(--ok);border:1px solid var(--ok-bd)}
            .av.mid{background:#fff5e6;color:#9a6b00;border:1px solid #f4e2bd}
            .av.low{background:#fdeaea;color:#b23b3b;border:1px solid #f3cccc}
            .av.na{background:#f0edf4;color:#8a8398;border:1px solid #e3dcec}
            .eqtabs{display:inline-flex;margin:2px 0 16px}
            .eqtabs .tab{text-decoration:none}
            .aviso{padding:11px 14px;border-radius:10px;margin:4px 0 14px;font-size:14px}
            .aviso.ok{background:var(--ok-bg);color:var(--ok);border:1px solid var(--ok-bd)}
            .aviso.err{background:#fdeaea;color:#b23b3b;border:1px solid #f3cccc}
            .mgrid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:8px}
            .mgrid .wide{grid-column:span 2}
            .mgrid>div{display:flex;flex-direction:column;gap:3px}
            .mgrid .flab{font-size:12px;color:var(--muted);font-weight:600}
            .mgrid input{width:100%}
            @media(max-width:700px){.mgrid{grid-template-columns:1fr 1fr}.mgrid .wide{grid-column:span 2}}
            .who-grupo{font-size:12px;color:var(--cl);font-weight:600;margin-top:1px}
            .saludo{display:flex;gap:26px;flex-wrap:wrap;background:var(--sel);border:1px solid var(--borde);
                    border-radius:14px;padding:14px 18px;margin:0 0 16px}
            .saludo>div{display:flex;flex-direction:column;gap:2px}
            .saludo .s-k{font-size:12px;color:var(--muted);font-weight:500}
            .saludo .s-v{font-size:16px;font-weight:700;color:var(--morado-osc)}
            .clave-row{display:flex;gap:8px;align-items:center}
            .clave-row input{flex:1}

            /* ---- formularios ---- */
            .formbox{max-width:640px;background:#fff;border:1px solid var(--borde);border-radius:16px;
                     padding:22px 24px;box-shadow:0 2px 10px rgba(30,10,60,.06)}
            .formbox label{display:block;margin:14px 0 5px;font-size:13px;color:#574d68;font-weight:600}
            .formbox input,.formbox select{width:100%}
            .doc{display:flex;gap:8px}.doc select{max-width:220px;flex:0 0 auto}.doc input{flex:1}
            .cursos{border:1px solid var(--borde);border-radius:10px;padding:10px;background:#fcfaff}
            /* Cursos en grilla ordenada: cada casilla alineada en su columna */
            .cursos-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:8px;
                         max-height:260px;overflow:auto;padding:2px}
            .curso-item{display:flex;align-items:flex-start;gap:9px;font-weight:400;cursor:pointer;
                        border:1px solid var(--borde);border-radius:9px;padding:9px 11px;background:#fff;
                        transition:border-color .12s,background .12s}
            .curso-item input{margin:2px 0 0;flex:0 0 auto}
            .curso-item span{line-height:1.3}
            .curso-item:hover{border-color:var(--cl);background:var(--sel)}
            .curso-item.on{border-color:var(--morado);background:var(--sel)}
            /* Toggle Sí/No (Reset) */
            .toggle{display:inline-flex;align-items:center;gap:10px;cursor:pointer;
                    border:1px solid var(--borde);border-radius:9px;padding:9px 12px;background:#fff;width:fit-content}
            .toggle input{width:18px;height:18px;accent-color:var(--morado)}
            .sino{font-weight:700;font-size:13px;padding:2px 12px;border-radius:999px}
            .sino.si{background:var(--ok-bg);color:var(--ok);border:1px solid var(--ok-bd)}
            .sino.no{background:#f0edf4;color:#8a8398;border:1px solid #e3dcec}
            details summary{cursor:pointer;color:var(--morado);font-weight:600}
            textarea{width:100%;padding:9px 12px;border:1px solid var(--borde);border-radius:9px;font-size:14px;
                     font-family:inherit;color:var(--ink);resize:vertical}
            textarea:focus{outline:none;border-color:var(--morado);box-shadow:0 0 0 3px var(--ring)}
            .segui{border:1px solid var(--borde);border-radius:12px;padding:12px 14px;margin-top:14px;background:#fcfaff}
            .segui-top{display:flex;align-items:center;gap:8px;color:var(--morado-osc)}
            .candado{background:#fff6e5;color:#8a5a00;border:1px solid #f3dca6;padding:7px 10px;border-radius:8px;
                     font-size:12.5px;margin:6px 0}
            .hist{margin-top:10px;font-size:13px}
            .hist ul{margin:8px 0 0;padding-left:18px}
            .hist li{margin:3px 0;color:#574d68}
            .hist .fecha{color:var(--muted);font-size:12px}

            /* ---- alertas ---- */
            .err{background:#fdecee;color:#8c1d18;border:1px solid #f3c2c7;padding:10px 13px;border-radius:10px;margin-bottom:14px}
            .okbox{background:var(--ok-bg);color:var(--ok);border:1px solid var(--ok-bd);padding:10px 13px;border-radius:10px;margin-bottom:14px}
            input[type=file]{width:100%;padding:10px 12px;border:1px dashed #c9b9df;border-radius:10px;background:#fcfaff}
            .muted{color:var(--muted);font-size:12px}

            /* ---- login (oscuro, con logo) ---- */
            .login-wrap{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px;
                background:radial-gradient(circle at 50% 28%, #3a1260 0%, #180b28 55%, #090411 100%)}
            .login{max-width:390px;width:100%;border:1px solid #5a2a86;border-radius:18px;padding:24px 28px 28px;
                   background:rgba(27,15,44,.92);box-shadow:0 14px 55px rgba(123,31,162,.45);animation:fade .3s ease;color:#eadff7}
            .login .logo{display:block;width:100%;max-width:320px;margin:0 auto 16px;filter:drop-shadow(0 0 14px rgba(170,70,230,.45))}
            .login label{display:block;margin:12px 0 5px;font-size:13px;font-weight:600;color:#d7c6ec}
            .login input{width:100%}
            .login .muted{color:#b299d4}
            .pass{position:relative}.pass input{padding-right:44px}
            .eye{position:absolute;right:6px;top:50%;transform:translateY(-50%);background:none;border:0;cursor:pointer;
                 padding:6px;color:#8a6fb0;display:flex;align-items:center;transition:color .15s}
            .eye:hover{color:#c98bff}.eye svg{width:21px;height:21px}

            /* ---- responsive: barra arriba en pantallas chicas ---- */
            @media(max-width:760px){
              .app{flex-direction:column}
              .side{width:100%;flex:none;height:auto;position:static;flex-direction:row;flex-wrap:wrap;align-items:center}
              .side-brand{padding:10px 14px}.side-brand img{max-width:120px}
              .side-nav{flex-direction:row;flex-wrap:wrap;flex:1;padding:8px}
              .side-foot{border-top:0;display:flex;align-items:center;gap:12px}
              .who{margin-bottom:0}
              main{padding:18px 16px}
            }
            """;
    }

    public static String loginPage(String error) {
        String err = (error == null || error.isBlank()) ? "" : "<div class=\"err\">" + esc(error) + "</div>";
        return """
            <!doctype html><html lang="es"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Ingresar — Red Surco</title>
            <link rel="icon" href="/logo.png">
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap">
            <link rel="stylesheet" href="/style.css"></head>
            <body><div class="login-wrap"><div class="login">
            <img src="/logo.png" class="logo" alt="Red Surco">%s
            <form method="post" action="/login">
              <label>Usuario</label><input type="text" name="usuario" autofocus>
              <label>Contraseña</label>
              <div class="pass">
                <input type="password" id="clave" name="clave">
                <button type="button" class="eye" onclick="verClave(this)" aria-label="Mostrar contraseña">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z"/><circle cx="12" cy="12" r="3"/></svg>
                </button>
              </div>
              <div style="margin-top:16px"><button class="btn" style="width:100%%;justify-content:center">Ingresar</button></div>
            </form>
            <script>
            var OJO_ON='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z"/><circle cx="12" cy="12" r="3"/></svg>';
            var OJO_OFF='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-7-11-7a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 7 11 7a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';
            function verClave(b){var i=document.getElementById('clave');var m=i.type==='password';i.type=m?'text':'password';b.innerHTML=m?OJO_OFF:OJO_ON;b.setAttribute('aria-label',m?'Ocultar contraseña':'Mostrar contraseña');i.focus();}
            </script>
            </div></div></body></html>
            """.formatted(err);
    }
}
