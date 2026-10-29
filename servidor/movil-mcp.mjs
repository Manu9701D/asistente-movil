// MCP «movil» (stdio): las herramientas que actúan en el móvil. Cada llamada va a index.mjs
// (HTTP local), que la pasa a la app por el WebSocket y devuelve lo que la app contesta.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import readline from 'node:readline';

const DATA = process.env.DATA_DIR || path.join(os.homedir(), 'datos');
const URL_ACCION = process.env.MOVIL_URL || `http://127.0.0.1:${Number(process.env.PORT || 8790) + 1}/accion`;
const token = () => fs.readFileSync(path.join(DATA, 'token'), 'utf8').trim();

const obj = (properties = {}, required = []) => ({ type: 'object', properties, required });
const str = (description, extra = {}) => ({ type: 'string', description, ...extra });
const int = (description, extra = {}) => ({ type: 'integer', description, ...extra });
const bool = (description) => ({ type: 'boolean', description });

export const HERRAMIENTAS = [
  { name: 'info', description: 'Estado del móvil: batería, red, volúmenes, brillo, modo de sonido, no molestar, almacenamiento, modelo y hora.', inputSchema: obj() },
  { name: 'alarma', description: 'Crea una alarma en el reloj del móvil.', inputSchema: obj({
    hora: int('Hora (0-23)', { minimum: 0, maximum: 23 }), minuto: int('Minuto (0-59)', { minimum: 0, maximum: 59 }),
    mensaje: str('Etiqueta de la alarma'),
    dias: { type: 'array', items: { type: 'integer', minimum: 1, maximum: 7 }, description: 'Días que se repite: 1 lunes … 7 domingo. Sin días, solo una vez.' },
  }, ['hora', 'minuto']) },
  { name: 'temporizador', description: 'Pone un temporizador.', inputSchema: obj({ segundos: int('Duración en segundos', { minimum: 1 }), mensaje: str('Etiqueta') }, ['segundos']) },
  { name: 'ver_alarmas', description: 'Abre la lista de alarmas del reloj.', inputSchema: obj() },
  { name: 'apps', description: 'Lista las apps instaladas (nombre y paquete).', inputSchema: obj({ filtro: str('Texto que debe contener el nombre (opcional)') }) },
  { name: 'abrir_app', description: 'Abre una app por su nombre o paquete.', inputSchema: obj({ nombre: str('Nombre visible (p. ej. «Spotify») o paquete') }, ['nombre']) },
  { name: 'abrir_url', description: 'Abre un enlace (web, deep link de una app, etc.).', inputSchema: obj({ url: str('URL') }, ['url']) },
  { name: 'mapa', description: 'Busca un sitio en el mapa o inicia la navegación hasta él.', inputSchema: obj({
    destino: str('Dirección o lugar'), navegar: bool('true para iniciar la navegación'),
    modo: str('Modo de navegación', { enum: ['coche', 'andando', 'bici', 'transporte'] }),
  }, ['destino']) },
  { name: 'llamar', description: 'Llama a un número. Sin directo, abre el marcador con el número (el usuario pulsa llamar); con directo, llama ya: solo tras confirmarlo el usuario.', inputSchema: obj({ numero: str('Número de teléfono'), directo: bool('Llamar sin que el usuario pulse (confírmalo antes)') }, ['numero']) },
  { name: 'sms', description: 'SMS a un número. Sin enviar, lo deja escrito en la app de mensajes; con enviar, lo manda ya: solo tras confirmar con el usuario el destinatario y el texto exacto.', inputSchema: obj({ numero: str('Número'), texto: str('Mensaje'), enviar: bool('Enviarlo sin abrir la app (confírmalo antes)') }, ['numero']) },
  { name: 'compartir', description: 'Abre el menú de compartir con un texto (para mandarlo por WhatsApp, correo, etc.).', inputSchema: obj({ texto: str('Texto') }, ['texto']) },
  { name: 'ajustes', description: 'Abre una pantalla de Ajustes. Los paneles (internet, volumen, nfc) salen encima sin salir de la app.', inputSchema: obj({
    pantalla: str('Pantalla', { enum: ['general', 'wifi', 'internet', 'bluetooth', 'datos', 'volumen', 'sonido', 'pantalla', 'bateria', 'notificaciones', 'no_molestar', 'ubicacion', 'apps', 'almacenamiento', 'accesibilidad', 'nfc', 'fecha', 'idioma', 'seguridad', 'esta_app'] }),
  }, ['pantalla']) },
  { name: 'volumen', description: 'Cambia un volumen (0-100 %).', inputSchema: obj({
    tipo: str('Qué volumen', { enum: ['multimedia', 'timbre', 'notificaciones', 'alarma', 'llamada'] }), nivel: int('Porcentaje', { minimum: 0, maximum: 100 }),
  }, ['tipo', 'nivel']) },
  { name: 'modo_sonido', description: 'Pone el móvil en sonido, vibración o silencio.', inputSchema: obj({ modo: str('Modo', { enum: ['sonido', 'vibracion', 'silencio'] }) }, ['modo']) },
  { name: 'no_molestar', description: 'Activa o desactiva No molestar.', inputSchema: obj({ activar: bool('true para activarlo') }, ['activar']) },
  { name: 'brillo', description: 'Cambia el brillo de la pantalla.', inputSchema: obj({ nivel: int('Porcentaje (0-100)', { minimum: 0, maximum: 100 }), automatico: bool('Brillo automático') }) },
  { name: 'linterna', description: 'Enciende o apaga la linterna.', inputSchema: obj({ encender: bool('true para encenderla') }, ['encender']) },
  { name: 'portapapeles', description: 'Lee el portapapeles o copia un texto en él.', inputSchema: obj({ copiar: str('Texto que copiar; sin él, devuelve lo que hay') }) },
  { name: 'notificacion', description: 'Muestra una notificación en el móvil.', inputSchema: obj({ titulo: str('Título'), texto: str('Texto') }, ['titulo']) },
  { name: 'calendario', description: 'Eventos del calendario entre dos fechas.', inputSchema: obj({
    desde: str('Inicio, ISO 8601 (p. ej. 2026-10-08 o 2026-10-08T09:00)'), hasta: str('Fin, ISO 8601 (incluido si es solo fecha)'), buscar: str('Texto en el título (opcional)'),
  }, ['desde', 'hasta']) },
  { name: 'crear_evento', description: 'Crea un evento en el calendario principal.', inputSchema: obj({
    titulo: str('Título'), inicio: str('Inicio ISO 8601 (hora local)'), fin: str('Fin ISO 8601; por defecto, 1 h después'),
    todo_el_dia: bool('Evento de día completo'), lugar: str('Lugar'), descripcion: str('Descripción'),
    aviso_minutos: int('Recordatorio, minutos antes', { minimum: 0 }),
  }, ['titulo', 'inicio']) },
  { name: 'contactos', description: 'Busca contactos por nombre (teléfonos y correos).', inputSchema: obj({ nombre: str('Nombre o parte') }, ['nombre']) },
  // Control de pantalla (servicio de accesibilidad de la app)
  { name: 'pantalla', description: 'Lee la pantalla del móvil: la app que está delante y sus elementos numerados ([n] tipo «texto» (estado) @x,y). Úsala antes de pulsar o escribir.', inputSchema: obj() },
  { name: 'captura', description: 'Captura de pantalla como imagen, para lo que pantalla no describe (fotos, mapas, juegos, apps sin texto accesible).', inputSchema: obj() },
  { name: 'pulsar', description: 'Pulsa un elemento (número de la última lectura de pantalla) o unas coordenadas en píxeles. Devuelve la pantalla nueva.', inputSchema: obj({
    elemento: int('Número del elemento', { minimum: 1 }), x: int('X en píxeles (sin elemento)'), y: int('Y en píxeles (sin elemento)'), larga: bool('Pulsación larga'),
  }) },
  { name: 'escribir', description: 'Escribe en un campo de texto (el indicado, el enfocado o el primero). Devuelve la pantalla nueva.', inputSchema: obj({
    texto: str('Texto'), elemento: int('Número del campo (opcional)', { minimum: 1 }), anadir: bool('Añadir al texto que ya hay en vez de sustituirlo'), enviar: bool('Pulsar Intro o enviar del teclado al terminar'),
  }, ['texto']) },
  { name: 'deslizar', description: 'Desplaza la pantalla o una lista. «abajo» muestra lo de más abajo. Con x1,y1,x2,y2 hace un deslizamiento libre. Devuelve la pantalla nueva.', inputSchema: obj({
    direccion: str('Hacia dónde', { enum: ['abajo', 'arriba', 'izquierda', 'derecha'] }), elemento: int('Lista que desplazar (opcional)', { minimum: 1 }),
    x1: int('Inicio X'), y1: int('Inicio Y'), x2: int('Fin X'), y2: int('Fin Y'), ms: int('Duración en ms', { minimum: 50 }),
  }) },
  { name: 'boton', description: 'Botones del sistema. Devuelve la pantalla nueva.', inputSchema: obj({
    boton: str('Botón', { enum: ['atras', 'inicio', 'recientes', 'notificaciones', 'ajustes_rapidos', 'bloquear'] }),
  }, ['boton']) },
  { name: 'musica', description: 'Música del móvil (Apple Music u otra app que esté sonando): ver qué suena, reproducir (reanuda), pausar, siguiente, anterior o buscar y poner una canción, artista, álbum o lista. Devuelve qué suena después. Para guardar en una lista o dar «me gusta», usa la pantalla en la app de música.', inputSchema: obj({
    accion: str('Qué hacer', { enum: ['estado', 'reproducir', 'pausar', 'siguiente', 'anterior', 'buscar'] }),
    busqueda: str('Con buscar: qué poner (p. ej. «Bad Bunny», «lista Gimnasio»)'),
  }, ['accion']) },
  { name: 'ubicacion', description: 'Ubicación actual del móvil (latitud, longitud, precisión y dirección aproximada).', inputSchema: obj() },
  // Notificaciones (acceso a notificaciones de la app)
  { name: 'notificaciones', description: 'Notificaciones que hay ahora en el móvil (mensajes de WhatsApp, Telegram, SMS, correos, avisos de apps…), numeradas, con app, remitente, texto, hora y si se pueden contestar.', inputSchema: obj({ app: str('Solo las de esta app (nombre o parte, opcional)') }) },
  { name: 'responder_notificacion', description: 'Contesta un mensaje desde su notificación, sin abrir la app (como «Responder» en la notificación). Antes, confirma con el usuario el texto exacto y a quién va.', inputSchema: obj({ numero: int('Número de la notificación (de notificaciones)', { minimum: 1 }), texto: str('Respuesta') }, ['numero', 'texto']) },
  // Recordatorios y coche (los guarda el móvil y avisa él solo)
  { name: 'recordatorio', description: 'Crea un recordatorio que el móvil avisa solo con una notificación (y en voz alta si va en el coche): a una hora, al subir o bajar del coche, o al llegar o salir de un sitio.', inputSchema: obj({
    texto: str('Qué recordar'),
    cuando: str('Cuándo avisa', { enum: ['hora', 'subir_coche', 'bajar_coche', 'llegar', 'salir'] }),
    hora: str('Con hora: ISO 8601 en hora local (p. ej. 2026-10-09T09:00)'),
    lugar: str('Con llegar o salir: dirección del sitio (si es «casa», «trabajo»…, búscala antes en notas)'),
    radio: int('Con llegar o salir: metros alrededor del sitio (por defecto 150)', { minimum: 50 }),
  }, ['texto', 'cuando']) },
  { name: 'recordatorios', description: 'Lista los recordatorios pendientes o borra uno.', inputSchema: obj({ borrar: int('Número del recordatorio que borrar (opcional)', { minimum: 1 }) }) },
  { name: 'aparcamiento', description: 'Dónde y cuándo se aparcó el coche (se guarda solo al desconectarse el Bluetooth del coche). Para llevar al usuario, usa mapa con las coordenadas, navegar y modo andando.', inputSchema: obj() },
  // Memoria (en el servidor)
  { name: 'notas', description: 'Tu memoria sobre el usuario entre conversaciones: datos, gustos, direcciones (casa, trabajo), personas, dónde guardó algo, listas (compra, tareas). Guarda sin preguntar lo que te cuente que valga para más adelante; busca aquí antes de preguntarle algo personal.', inputSchema: obj({
    accion: str('Qué hacer', { enum: ['guardar', 'buscar', 'listar', 'cambiar', 'borrar'] }),
    texto: str('Con guardar o cambiar: la nota, completa y que se entienda sola (p. ej. «Casa: Calle Mayor 3, Zaragoza»; «Lista de la compra: leche, huevos»)'),
    buscar: str('Con buscar: palabras que buscar'),
    numero: int('Con cambiar o borrar: número de la nota', { minimum: 1 }),
  }, ['accion']) },
];

// ---------- Notas: la memoria del asistente, aquí en el servidor ----------
const NOTAS = path.join(DATA, 'notas.json');
const leerNotas = () => { try { return JSON.parse(fs.readFileSync(NOTAS, 'utf8')); } catch { return []; } };
const guardarNotas = (l) => fs.writeFileSync(NOTAS, JSON.stringify(l, null, 1), { mode: 0o600 });
const plano = (s) => String(s || '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
const lineaNota = (n) => `[${n.n}] ${n.texto} (${new Date(n.cambiada || n.creada).toISOString().slice(0, 10)})`;

// Las notas en texto para el prompt del sistema (las primeras, si son muchas)
export function notasParaPrompt(max = 6000) {
  let out = '';
  const l = leerNotas();
  for (const n of l) {
    const s = lineaNota(n) + '\n';
    if (out.length + s.length > max) { out += `… (${l.length} notas en total: busca las demás con notas)\n`; break; }
    out += s;
  }
  return out.trim();
}

function notas(a) {
  const l = leerNotas();
  if (a.accion === 'guardar') {
    const texto = String(a.texto || '').trim();
    if (!texto) return { ok: false, texto: 'Falta el texto de la nota.' };
    const n = { n: l.reduce((m, x) => Math.max(m, x.n), 0) + 1, texto, creada: Date.now() };
    l.push(n);
    guardarNotas(l);
    return { ok: true, texto: `Guardada la nota ${n.n}.` };
  }
  if (a.accion === 'cambiar' || a.accion === 'borrar') {
    const i = l.findIndex((x) => x.n === Number(a.numero));
    if (i < 0) return { ok: false, texto: `No hay nota ${a.numero}.` };
    if (a.accion === 'borrar') { const [x] = l.splice(i, 1); guardarNotas(l); return { ok: true, texto: `Borrada: ${x.texto}` }; }
    const texto = String(a.texto || '').trim();
    if (!texto) return { ok: false, texto: 'Falta el texto nuevo.' };
    Object.assign(l[i], { texto, cambiada: Date.now() });
    guardarNotas(l);
    return { ok: true, texto: `Nota ${l[i].n} cambiada.` };
  }
  if (a.accion === 'buscar') {
    const palabras = plano(a.buscar).split(/\s+/).filter((w) => w.length > 1);
    const puntos = (x) => palabras.filter((w) => plano(x.texto).includes(w)).length;
    const r = l.map((x) => [puntos(x), x]).filter(([p]) => p > 0).sort((x, y) => y[0] - x[0]).slice(0, 30).map(([, x]) => lineaNota(x));
    return { ok: true, texto: r.length ? r.join('\n') : 'Ninguna nota coincide.' };
  }
  return { ok: true, texto: l.length ? l.map(lineaNota).join('\n') : 'No hay notas.' };
}

// Las que se hacen aquí, sin pasar por el móvil
const LOCALES = { notas };

// Las de solo lectura no piden permiso en el chat
// El control de pantalla tampoco: pedir permiso en cada toque lo haría inútil (y el chat está detrás de la app que
// se maneja). Ni llamar, sms y responder_notificacion: en el coche no se puede pulsar la tarjeta de permiso.
// Lo que no se puede deshacer lo confirma Claude con el usuario antes, de palabra (ver NOTE en index.mjs).
export const SIN_PERMISO = ['notas', 'notificaciones', 'recordatorio', 'recordatorios', 'aparcamiento', 'llamar', 'sms', 'responder_notificacion', 'musica', 'mapa', 'abrir_app', 'volumen', 'info', 'apps', 'calendario', 'contactos', 'ubicacion', 'ver_alarmas', 'pantalla', 'captura', 'pulsar', 'escribir', 'deslizar', 'boton'];

async function llamar(nombre, args) {
  const res = await fetch(URL_ACCION, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${token()}` },
    body: JSON.stringify({ nombre, args }),
    signal: AbortSignal.timeout(130000),
  });
  return res.json();
}

// ---------- JSON-RPC por stdio ----------
const out = (msg) => process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...msg }) + '\n');

async function atender(m) {
  const { id, method, params } = m;
  if (method === 'initialize') {
    return out({ id, result: {
      protocolVersion: params?.protocolVersion || '2025-06-18',
      capabilities: { tools: {} },
      serverInfo: { name: 'movil', version: '0.5.0' },
      instructions: 'Herramientas para actuar en el móvil Android del usuario. Solo funcionan con la app Asistente abierta o reciente.',
    } });
  }
  if (method === 'tools/list') return out({ id, result: { tools: HERRAMIENTAS } });
  if (method === 'tools/call') {
    let r;
    try { r = LOCALES[params.name] ? LOCALES[params.name](params.arguments || {}) : await llamar(params.name, params.arguments || {}); } catch (e) { r = { ok: false, texto: `Sin respuesta del servicio: ${e.message}` }; }
    const content = [{ type: 'text', text: r.texto || (r.ok ? 'Hecho.' : 'Error.') }];
    if (r.imagen) content.push({ type: 'image', data: r.imagen, mimeType: 'image/jpeg' });
    return out({ id, result: { content, isError: !r.ok } });
  }
  if (method === 'ping') return out({ id, result: {} });
  if (id !== undefined) out({ id, error: { code: -32601, message: `Método desconocido: ${method}` } });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  readline.createInterface({ input: process.stdin }).on('line', (l) => {
    let m;
    try { m = JSON.parse(l); } catch { return; }
    atender(m).catch((e) => m.id !== undefined && out({ id: m.id, error: { code: -32603, message: e.message } }));
  });
}
