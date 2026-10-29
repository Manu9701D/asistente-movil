// Servicio del asistente móvil: el `claude` interactivo en una pseudoconsola (sin SDK), su
// conversación leída de la transcripción (termchat.mjs) y un WebSocket para la app de Android.
// Solo escucha en la IP de Tailscale y pide un token.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import pty from 'node-pty';
import { WebSocketServer } from 'ws';
import { createTermChat } from './termchat.mjs';
import { SIN_PERMISO, notasParaPrompt } from './movil-mcp.mjs';

const HOME = os.homedir();
const HOST = process.env.HOST || '127.0.0.1';
const PORT = Number(process.env.PORT) || 8790;
const DATA = process.env.DATA_DIR || path.join(HOME, 'datos');
const WORK = process.env.WORK_DIR || path.join(HOME, 'espacio');
const CLAUDE = process.env.CLAUDE_BIN || path.join(HOME, '.local', 'bin', 'claude');
const STATE_FILE = path.join(DATA, 'estado.json');
const MAX_MESSAGES = 200; // los que se mandan al móvil
const MCP = path.join(path.dirname(new URL(import.meta.url).pathname), 'movil-mcp.mjs');

const NOTE = [
  'Eres el asistente personal del usuario y te habla desde su móvil Android. Responde siempre en español de España, breve y directo: se lee en una pantalla pequeña.',
  'Corres en un servidor Linux, no en el móvil. Para actuar en el móvil (alarmas, apps, ajustes, volumen, calendario, contactos, ubicación…) usa las herramientas mcp__movil__*; no tienes acceso a sus archivos.',
  'Cuando el usuario pida algo del móvil, hazlo directamente con la herramienta adecuada y confirma en una frase.',
  'Para lo que no tenga herramienta propia, maneja las apps como una persona: abre la app (abrir_app), lee la pantalla (pantalla), y usa pulsar, escribir, deslizar y boton; cada una devuelve la pantalla nueva. Usa captura solo si la pantalla no basta (imágenes, mapas, juegos). Prefiere siempre las herramientas directas (alarma, ajustes, volumen…) a tocar la pantalla.',
  'Los mensajes que empiezan por «🚗» los manda conduciendo y la respuesta se le lee en voz alta: contesta en una o dos frases para oír, sin listas ni enlaces, y nunca le pidas que mire o toque la pantalla. Para la música usa mcp__movil__musica; para direcciones y rutas, mcp__movil__mapa (con navegar si quiere ir). Las confirmaciones pídelas con una pregunta que pueda contestar de palabra (sí o no), no con opciones para pulsar.',
  'Antes de algo que no se puede deshacer o que afecta a otros (enviar un mensaje o correo, publicar, comprar o pagar, borrar, llamar), para y pide confirmación al usuario diciéndole exactamente qué vas a hacer. Si aparece una pantalla de contraseña, PIN o pago, no la rellenes: avisa al usuario.',
  'Llamar (llamar con directo), mandar SMS (sms con enviar) y contestar mensajes (responder_notificacion) no piden permiso en pantalla: confírmalos tú antes, de palabra, repitiendo a quién y el texto exacto («¿Le mando a Ana: "llego en 10 minutos"?»), y hazlo solo si dice que sí. Si no sabes el número, búscalo en contactos.',
  'Los mensajes que empiezan por «[Mensaje recibido · notificación n …]» (a veces tras «🚗») son un mensaje que el móvil le ha leído al usuario y lo que él ha dicho después. Si quiere contestar, repite la respuesta y pregunta «¿Lo mando?»; si confirma, usa responder_notificacion con ese número.',
  'Tienes memoria entre conversaciones con mcp__movil__notas: guarda sin preguntar lo que el usuario cuente que valga para más adelante (direcciones, gustos, personas, matrículas, dónde dejó algo, listas), corrige las notas que cambien y, antes de preguntarle un dato personal, búscalo ahí.',
  'Para avisos futuros usa mcp__movil__recordatorio (a una hora, al subir o bajar del coche, al llegar o salir de un sitio): el móvil avisa solo aunque no haya conversación. Para despertarse, alarma.',
  'Los mensajes pueden acabar con <contexto>…</contexto>: lo añade el móvil solo (hora, sitio aproximado, batería, coche). Úsalo si viene al caso, sin mencionarlo.',
  'Si el usuario pregunta por «esto», «lo que tengo delante» o lo que sale en la pantalla sin adjuntar foto, mira primero con pantalla (o captura si es una imagen). Si adjunta fotos sin más («¿Qué es esto?»), descríbelas y di lo útil sobre ellas.',
  'Tienes también los conectores de claude.ai (Gmail y Claude Docs, cárgalos con ToolSearch): úsalos si pide algo de su correo o de sus documentos. Antes de enviar un correo, confírmalo.',
].join('\n');

// El prompt del sistema: la nota y la memoria tal como está al arrancar claude
function prompt() {
  const n = notasParaPrompt();
  return n ? `${NOTE}\n\nTus notas sobre el usuario (mcp__movil__notas):\n${n}` : NOTE;
}

fs.mkdirSync(DATA, { recursive: true });
fs.mkdirSync(WORK, { recursive: true });

const readJson = (f, fb) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return fb; } };

// Token de la app: se crea una vez y se copia a la app al emparejar
const TOKEN_FILE = path.join(DATA, 'token');
if (!fs.existsSync(TOKEN_FILE)) fs.writeFileSync(TOKEN_FILE, crypto.randomBytes(24).toString('base64url'), { mode: 0o600 });
const TOKEN = fs.readFileSync(TOKEN_FILE, 'utf8').trim();

// ---------- Conversación ----------
const newConv = (id = crypto.randomUUID()) => ({
  id, title: 'Asistente', messages: [], tasks: [], queue: [], prompts: [], busy: false, permissionMode: 'default',
});
const saved = readJson(STATE_FILE, {});
let conv = newConv(saved.convId);
conv.sessionId = saved.sessionId;
let resume = !!saved.convId;
let proc = null;
let ready = null; // promesa: la terminal ya acepta texto

const saveState = () => fs.writeFileSync(STATE_FILE, JSON.stringify({ convId: conv.id, sessionId: conv.sessionId }));

const clients = new Set();
function broadcast(msg) {
  const s = JSON.stringify(msg);
  for (const ws of clients) if (ws.readyState === 1) ws.send(s);
}
// Al móvil no se le manda el contexto automático ni las imágenes enteras (pesarían en cada actualización)
const sinContexto = (s) => typeof s === 'string' ? s.replace(/\s*<contexto>[\s\S]*?<\/contexto>\s*/g, ' ').trim() : s;
const ligero = (m) => m.role !== 'user' ? m : { ...m, text: sinContexto(m.text), images: undefined, nImages: m.images?.length || undefined };
const snapshot = () => ({
  ...conv,
  messages: conv.messages.slice(-MAX_MESSAGES).map(ligero),
  queue: (conv.queue || []).map((q) => ({ ...q, text: sinContexto(q.text) })),
  running: !!proc,
});

const termchat = createTermChat({
  emit: (event, payload) => {
    if (event === 'conv:update') {
      if (conv.sessionId !== saved.sessionId) { saved.sessionId = conv.sessionId; saveState(); }
      broadcast({ type: 'conv', conv: snapshot() });
    } else broadcast({ type: event, ...payload });
  },
  write: (id, data) => { if (id === conv.id) proc?.write(data); },
});
termchat.attach(conv);

function sessionExists(id) {
  const root = path.join(HOME, '.claude', 'projects');
  try { return fs.readdirSync(root).some((d) => fs.existsSync(path.join(root, d, `${id}.jsonl`))); } catch { return false; }
}

function args() {
  const a = [];
  const sid = conv.sessionId || conv.id;
  if (resume && sessionExists(sid)) a.push('--resume', sid);
  else a.push('--session-id', conv.id);
  a.push('--append-system-prompt', prompt());
  // Para gastar menos del plan: Sonnet basta para el móvil, y de las herramientas integradas solo
  // las que sirven aquí (cada una pesa en el contexto de todas las peticiones)
  a.push('--model', process.env.MODELO || 'sonnet');
  // ToolSearch: los conectores de claude.ai (Gmail, Claude Docs) quedan diferidos y se cargan al usarlos;
  // sin él entran enteros en cada petición (~31 k tokens en vez de ~7 k)
  a.push('--tools', 'WebSearch,WebFetch,AskUserQuestion,ToolSearch');
  const hooks = termchat.hookSettings(conv.id);
  a.push('--mcp-config', JSON.stringify({ mcpServers: { movil: { command: process.execPath, args: [MCP] } } }));
  a.push('--settings', JSON.stringify({ ...(hooks && { hooks }), permissions: { allow: SIN_PERMISO.map((n) => `mcp__movil__${n}`) } }));
  return a;
}

// Título de la terminal: «✳ …» en espera, un spinner delante mientras trabaja
function watchTitle(d) {
  const re = /\x1b\][02];([^\x07\x1b]*)(?:\x07|\x1b\\)/g;
  let m, last = null;
  while ((m = re.exec(d))) last = m[1];
  if (last === null) return null;
  const s = /^([\p{S}\p{P}⠀-⣿])\s+/u.exec(last);
  return s ? s[1] !== '✳' : null;
}

function start() {
  if (proc) return ready;
  const p = pty.spawn(CLAUDE, args(), {
    name: 'xterm-256color', cols: 100, rows: 40, cwd: WORK,
    env: { ...process.env, COLORTERM: 'truecolor', LANG: 'es_ES.UTF-8' },
  });
  proc = p;
  resume = true;
  saveState();
  let trust = '';
  const trustUntil = Date.now() + 20000;
  let markReady;
  ready = new Promise((r) => { markReady = r; });
  const fallback = setTimeout(() => markReady(), 10000);
  p.onData((d) => {
    // «¿Confías en esta carpeta?»: es la carpeta de trabajo del asistente
    if (trust !== null) {
      trust = (trust + d).slice(-4000);
      if (/itrustthisfolder/i.test(trust.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '').replace(/\s/g, ''))) {
        trust = null;
        setTimeout(() => { p.write('\x1b[B'); setTimeout(() => p.write('\r'), 150); }, 300);
      } else if (Date.now() > trustUntil) trust = null;
    }
    const busy = watchTitle(d);
    if (busy === false) { clearTimeout(fallback); markReady(); }
    if (busy !== null) termchat.setBusy(conv.id, busy);
  });
  p.onExit(() => {
    if (proc !== p) return;
    proc = null;
    termchat.stop(conv.id);
    broadcast({ type: 'conv', conv: snapshot() });
  });
  termchat.start(conv.id);
  return ready;
}

function stopProc() {
  const p = proc;
  proc = null;
  try { p?.kill(); } catch {}
}

async function newConversation() {
  stopProc();
  termchat.detach(conv.id);
  conv = newConv();
  saved.sessionId = undefined;
  resume = false;
  termchat.attach(conv);
  saveState();
  broadcast({ type: 'conv', conv: snapshot() });
}

// ---------- Mensajes de la app ----------
async function onMessage(msg) {
  switch (msg.type) {
    case 'send':
      await start();
      {
        // El contexto (hora, sitio, batería, coche) va al final, para que «🚗» siga al principio
        const ctx = String(msg.contexto || '').trim();
        const text = [String(msg.text || ''), ctx && `<contexto>${ctx}</contexto>`].filter(Boolean).join('\n\n');
        termchat.send(conv.id, { text, images: Array.isArray(msg.images) ? msg.images : [] });
      }
      break;
    case 'interrupt':
      termchat.interrupt(conv.id);
      break;
    case 'respond':
      termchat.respond(conv.id, msg.promptId, msg.payload || {});
      break;
    case 'resultado': {
      const p = acciones.get(msg.id);
      if (p) { acciones.delete(msg.id); clearTimeout(p.timer); p.resolve({ ok: !!msg.ok, texto: String(msg.texto || ''), ...(msg.imagen && { imagen: String(msg.imagen) }) }); }
      break;
    }
    case 'new':
      await newConversation();
      break;
    default:
  }
}

// ---------- Acciones en el móvil (las pide movil-mcp.mjs) ----------
const acciones = new Map(); // id -> { resolve, timer }
const ESPERA_APP = 20000;   // si la app no está conectada, lo que se espera a que vuelva
const ESPERA_RESULTADO = 120000;

function appConectada() {
  return [...clients].reverse().find((ws) => ws.readyState === 1);
}
async function esperarApp() {
  const hasta = Date.now() + ESPERA_APP;
  while (Date.now() < hasta) {
    const ws = appConectada();
    if (ws) return ws;
    await new Promise((r) => setTimeout(r, 500));
  }
  return null;
}
async function accion(nombre, args) {
  const ws = await esperarApp();
  if (!ws) return { ok: false, texto: 'La app del móvil no está abierta: pide al usuario que la abra y vuelve a intentarlo.' };
  const id = crypto.randomUUID();
  return new Promise((resolve) => {
    const timer = setTimeout(() => { acciones.delete(id); resolve({ ok: false, texto: 'El móvil no ha respondido a tiempo.' }); }, ESPERA_RESULTADO);
    acciones.set(id, { resolve, timer });
    ws.send(JSON.stringify({ type: 'accion', id, nombre, args }));
  });
}

const interno = http.createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/accion' || !authorized(req)) { res.writeHead(404).end(); return; }
  let body = '';
  req.on('data', (d) => { body += d; });
  req.on('end', async () => {
    let m = {};
    try { m = JSON.parse(body); } catch {}
    const r = await accion(String(m.nombre || ''), m.args || {});
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify(r));
  });
});
interno.requestTimeout = 0;

// ---------- HTTP + WebSocket ----------
const authorized = (req) => {
  const url = new URL(req.url, 'http://x');
  const given = url.searchParams.get('k') || (req.headers.authorization || '').replace(/^Bearer /, '');
  const a = Buffer.from(given), b = Buffer.from(TOKEN);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
};

const server = http.createServer((req, res) => {
  if (req.url === '/salud') { res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ ok: true, running: !!proc })); return; }
  res.writeHead(404).end();
});
const wss = new WebSocketServer({ noServer: true, maxPayload: 20 * 1024 * 1024 });
server.on('upgrade', (req, socket, head) => {
  if (!authorized(req)) { socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n'); socket.destroy(); return; }
  wss.handleUpgrade(req, socket, head, (ws) => {
    clients.add(ws);
    ws.send(JSON.stringify({ type: 'conv', conv: snapshot() }));
    ws.on('message', (data) => {
      let msg;
      try { msg = JSON.parse(data); } catch { return; }
      onMessage(msg).catch((err) => ws.send(JSON.stringify({ type: 'error', message: err.message })));
    });
    ws.on('close', () => clients.delete(ws));
  });
});
// Latido para que la conexión del móvil no se quede colgada
setInterval(() => { for (const ws of clients) { try { ws.ping(); } catch {} } }, 25000);

interno.listen(PORT + 1, '127.0.0.1');
server.listen(PORT, HOST, () => console.log(`Asistente en ${HOST}:${PORT} (trabajo en ${WORK})`));
for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => { stopProc(); process.exit(0); });
