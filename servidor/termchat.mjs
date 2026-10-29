// Copiado de Claude Assistant (claude-assistant/termchat.mjs): si se mejora allí, traer los cambios.
// Chat limpio sobre el `claude` de la terminal: la conversación se lee de su transcripción
// (~/.claude/projects/…/<sesión>.jsonl) y se pasa al mismo modelo de mensajes que el SDK
// (engine.mjs); las preguntas y los permisos llegan por hooks HTTP y se contestan con los
// selectores de la app. Lo que se escribe en el chat se teclea en la terminal.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { cap, resultText, describeSuggestions, applyTask } from './util.mjs';

const PROJECTS = path.join(process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), '.claude'), 'projects');
const POLL_MS = 250;
const MAX_IMAGE = 2 * 1024 * 1024; // imágenes del historial que se enseñan (en base64)
const ANSI = /\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*(?:\x07|\x1b\\)/g;
const INTERRUPTED = 'Interrumpido. ¿Qué quieres que haga en su lugar?';

const between = (text, tag) => {
  const m = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(text);
  return m ? m[1] : null;
};
const clean = (s) => String(s || '').replace(ANSI, '').trim();

/**
 * @param {object} o
 * @param {(event, payload) => void} o.emit  eventos para la interfaz (conv:update, prompt:new…)
 * @param {(id, data) => void} o.write  teclear en la terminal de la pestaña
 */
export function createTermChat({ emit, write }) {
  const token = crypto.randomBytes(16).toString('hex');
  const tabs = new Map(); // id -> estado (ver attach)
  let port = 0;

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    const id = url.pathname.split('/')[2];
    if (url.searchParams.get('k') !== token || !tabs.has(id)) { res.writeHead(403).end(); return; }
    let body = '';
    req.on('data', (d) => { body += d; });
    req.on('end', () => {
      let input = {};
      try { input = JSON.parse(body || '{}'); } catch {}
      onHook(tabs.get(id), input, res);
    });
  });
  server.requestTimeout = 0;
  server.listen(0, '127.0.0.1', () => { port = server.address().port; });

  // Hooks que se añaden al --settings de cada pestaña
  function hookSettings(id) {
    if (!port) return null;
    const hook = (timeout) => [{ matcher: '*', hooks: [{ type: 'http', url: `http://127.0.0.1:${port}/hook/${id}?k=${token}`, timeout }] }];
    return { PermissionRequest: hook(86400), UserPromptSubmit: hook(10), Stop: hook(10), Notification: hook(10) };
  }

  function attach(conv) {
    const t = {
      conv, file: null, offset: 0, partial: '', timer: null, flushTimer: null, clearAt: 0,
      reply: null, tools: new Map(), keys: new Map(), usage: new Map(),
      pending: new Map(), // promptId -> { res, toolName, key, suggestions, input }
    };
    tabs.set(conv.id, t);
    return t;
  }

  // ---------- Notificaciones a la interfaz ----------
  function flush(t, now = false) {
    const send = () => { t.flushTimer = null; emit('conv:update', { convId: t.conv.id, conv: t.conv }); };
    if (now) { clearTimeout(t.flushTimer); send(); return; }
    if (!t.flushTimer) t.flushTimer = setTimeout(send, 60);
  }

  // ---------- Transcripción ----------
  function findTranscript(id) {
    try {
      for (const d of fs.readdirSync(PROJECTS)) {
        const f = path.join(PROJECTS, d, `${id}.jsonl`);
        if (fs.existsSync(f)) return f;
      }
    } catch {}
    return null;
  }

  function setTranscript(t, file) {
    if (!file || file === t.file) return;
    const first = !t.file;
    t.file = file;
    // Tras /clear Claude Code sigue en otra sesión: es la que hay que retomar al reiniciar
    t.conv.sessionId = path.basename(file, '.jsonl');
    t.offset = 0;
    t.partial = '';
    // /clear: sesión nueva en la misma pestaña, el chat empieza de cero
    if (!first) {
      t.conv.messages = [];
      t.conv.tasks = [];
      t.reply = null;
      t.tools.clear();
      t.keys.clear();
    }
    read(t);
  }

  // /clear no avisa por ningún hook: la sesión nueva se busca en la misma carpeta (un .jsonl
  // reciente que empiece por ese /clear y que no sea de otra pestaña)
  function findCleared(t) {
    if (Date.now() - t.clearAt > 20000) { t.clearAt = 0; return; }
    const dir = path.dirname(t.file);
    const taken = new Set([...tabs.values()].map((x) => x.file));
    let names = [];
    try { names = fs.readdirSync(dir).filter((n) => n.endsWith('.jsonl')); } catch { return; }
    for (const n of names) {
      const file = path.join(dir, n);
      if (taken.has(file)) continue;
      try {
        if (fs.statSync(file).birthtimeMs < t.clearAt - 2000) continue;
        if (!/<command-name>\/(clear|reset|new)<\/command-name>/.test(fs.readFileSync(file, 'utf8').slice(0, 20000))) continue;
      } catch { continue; }
      t.clearAt = 0;
      setTranscript(t, file);
      return;
    }
  }

  function read(t) {
    if (!t.file) { setTranscript(t, findTranscript(t.conv.sessionId || t.conv.id)); return; }
    if (t.clearAt) findCleared(t);
    let size;
    try { size = fs.statSync(t.file).size; } catch { return; }
    if (size < t.offset) { t.offset = 0; t.partial = ''; }
    if (size === t.offset) return;
    const fd = fs.openSync(t.file, 'r');
    const buf = Buffer.alloc(size - t.offset);
    try { fs.readSync(fd, buf, 0, buf.length, t.offset); } finally { fs.closeSync(fd); }
    t.offset = size;
    const lines = (t.partial + buf.toString('utf8')).split('\n');
    t.partial = lines.pop();
    let changed = false;
    for (const line of lines) {
      if (!line.trim()) continue;
      let rec;
      try { rec = JSON.parse(line); } catch { continue; }
      if (onRecord(t, rec) !== false) changed = true;
    }
    if (changed) flush(t);
  }

  function ensureReply(t) {
    const last = t.conv.messages.at(-1);
    if (last?.role === 'assistant' && !last.done) return (t.reply = last);
    t.reply = { role: 'assistant', blocks: [], done: false };
    t.usage.clear();
    t.conv.messages.push(t.reply);
    return t.reply;
  }

  function closeReply(t) {
    const last = t.conv.messages.at(-1);
    if (last?.role === 'assistant') last.done = true;
  }

  function addUser(t, text, images = [], extra = {}) {
    closeReply(t);
    t.conv.messages.push({ role: 'user', id: crypto.randomUUID(), text, images, ...extra });
    // Lo que estaba en la cola ya lo ha recogido Claude
    const i = t.conv.queue.findIndex((q) => q.text.trim() === text.trim());
    if (i >= 0) t.conv.queue.splice(i, 1);
    ensureReply(t);
  }

  function note(t, text, icon) {
    if (text) ensureReply(t).blocks.push({ type: 'note', text: cap(text, 3000), icon });
  }

  // Texto de un mensaje de usuario o de un comando local
  function onText(t, text) {
    if (text.includes('<command-name>')) {
      const name = clean(between(text, 'command-name'));
      const args = clean(between(text, 'command-args'));
      // /clear empieza una conversación nueva: el chat se queda en blanco
      if (name.replace(/^\//, '') === 'clear') { t.conv.messages = []; t.conv.tasks = []; t.reply = null; return; }
      addUser(t, [name.startsWith('/') ? name : `/${name}`, args].filter(Boolean).join(' '));
      return;
    }
    const out = between(text, 'local-command-stdout') ?? between(text, 'local-command-stderr');
    // La app devuelve el predeterminado que /model y /effort guardan (main.mjs, keepClaudeDefaults)
    if (out !== null) { note(t, clean(out).replace(/ and saved as your default for new sessions/, ' (solo en esta pestaña)')); return; }
    if (text.includes('<bash-input>')) { addUser(t, `! ${clean(between(text, 'bash-input'))}`); return; }
    const so = between(text, 'bash-stdout');
    if (so !== null) {
      const res = [clean(so), clean(between(text, 'bash-stderr'))].filter(Boolean).join('\n');
      const prev = t.conv.messages.at(-2);
      ensureReply(t).blocks.push({ type: 'tool', id: crypto.randomUUID(), name: 'Bash', input: { command: prev?.text?.replace(/^! /, '') || '' }, result: cap(res) });
      return;
    }
    if (text.startsWith('<task-notification>') || text.startsWith('<system-reminder>')) return;
    if (text.startsWith('[Request interrupted by user')) { interrupted(t); return; }
    addUser(t, text);
  }

  function interrupted(t) {
    const r = ensureReply(t);
    if (r.blocks.at(-1)?.type !== 'interrupted') r.blocks.push({ type: 'interrupted', text: INTERRUPTED });
    r.done = true;
    settleAll(t);
  }

  function onRecord(t, rec) {
    if (rec.isSidechain) return false;
    const conv = t.conv;
    switch (rec.type) {
      case 'permission-mode':
        if (rec.permissionMode) conv.permissionMode = rec.permissionMode;
        return true;
      case 'attachment': {
        const a = rec.attachment;
        if (a?.type === 'queued_command' && a.origin?.kind === 'human' && typeof a.prompt === 'string') { addUser(t, a.prompt); return true; }
        return false;
      }
      case 'system':
        if (rec.subtype === 'turn_duration') {
          const r = conv.messages.at(-1);
          if (r?.role === 'assistant') {
            let input = 0, cacheWrite = 0, cacheRead = 0, tokensOut = 0;
            for (const u of t.usage.values()) {
              input += u.input_tokens || 0; cacheWrite += u.cache_creation_input_tokens || 0;
              cacheRead += u.cache_read_input_tokens || 0; tokensOut += u.output_tokens || 0;
            }
            r.stats = { durationMs: rec.durationMs, input, cacheWrite, cacheRead, tokensOut };
            r.done = true;
          }
          settleAll(t);
          return true;
        }
        if (rec.subtype === 'compact_boundary') { note(t, 'Conversación compactada'); return true; }
        if (rec.subtype === 'local_command' && typeof rec.content === 'string') { onText(t, rec.content); return true; }
        if (rec.subtype === 'informational' && rec.content) { note(t, clean(rec.content)); return true; }
        return false;
      case 'user': {
        if (rec.isMeta || rec.isCompactSummary || rec.isVisibleInTranscriptOnly) return false;
        const content = rec.message?.content;
        if (typeof content === 'string') { onText(t, content); return true; }
        if (!Array.isArray(content)) return false;
        let text = '';
        const images = [];
        for (const b of content) {
          if (b.type === 'tool_result') onToolResult(t, b, rec.toolUseResult);
          else if (b.type === 'text') text += (text ? '\n' : '') + b.text;
          else if (b.type === 'image' && b.source?.type === 'base64' && b.source.data.length < MAX_IMAGE) {
            images.push(`data:${b.source.media_type};base64,${b.source.data}`);
          }
        }
        if (text.startsWith('[Request interrupted by user')) interrupted(t);
        else if (text.trim() || images.length) {
          if (text.includes('<command-name>') || text.includes('<local-command-stdout>')) onText(t, text);
          else if (!text.startsWith('<system-reminder>')) addUser(t, text.replace(/\[Image #\d+\]\s*/g, '').trim(), images);
        }
        return true;
      }
      case 'assistant': {
        const msg = rec.message;
        if (!msg || !Array.isArray(msg.content)) return false;
        const r = ensureReply(t);
        if (msg.model === '<synthetic>') {
          const text = msg.content.filter((b) => b.type === 'text').map((b) => b.text).join('\n').trim();
          if (text && text !== 'No response requested.') r.blocks.push({ type: rec.isApiErrorMessage ? 'error' : 'text', text });
          return true;
        }
        if (msg.id && msg.usage) t.usage.set(msg.id, msg.usage);
        for (const b of msg.content) {
          if (b.type === 'text' && b.text.trim()) r.blocks.push({ type: 'text', text: b.text });
          else if (b.type === 'thinking' && b.thinking?.trim()) r.blocks.push({ type: 'thinking', text: b.thinking });
          else if (b.type === 'tool_use') {
            if (b.name === 'TodoWrite') {
              conv.tasks = (b.input?.todos || []).map((x, i) => ({ id: String(i + 1), subject: x.content, activeForm: x.activeForm, status: x.status }));
            }
            const tool = { type: 'tool', id: b.id, name: b.name, input: cap(b.input) };
            t.tools.set(b.id, tool);
            t.keys.set(b.id, b.name + JSON.stringify(b.input));
            r.blocks.push(tool);
          }
        }
        return true;
      }
      default:
        return false;
    }
  }

  function onToolResult(t, b, structured) {
    const tool = t.tools.get(b.tool_use_id);
    if (!tool) return;
    tool.result = cap(resultText(b.content));
    tool.isError = !!b.is_error;
    if (!tool.isError) applyTask(t.conv, tool, structured);
    // Si el permiso se contestó en la terminal, la tarjeta sobra
    const key = t.keys.get(b.tool_use_id);
    for (const [pid, p] of t.pending) if (p.key === key) settle(t, pid, {});
  }

  // ---------- Hooks ----------
  function reply(res, out) {
    if (res.writableEnded || res.destroyed) return;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(out || {}));
  }

  function onHook(t, input, res) {
    if (input.transcript_path) setTranscript(t, input.transcript_path);
    const ev = input.hook_event_name;
    if (ev === 'PermissionRequest') return onPermission(t, input, res);
    reply(res, {});
    if (ev === 'UserPromptSubmit') {
      t.conv.busy = true;
      flush(t, true);
    } else if (ev === 'Stop') {
      // El último texto llega a la transcripción después de este hook: la respuesta la cierra turn_duration
      read(t);
      settleAll(t);
      flush(t, true);
    } else if (ev === 'Notification' && ['permission_prompt', 'elicitation_dialog'].includes(input.notification_type)) {
      // Un diálogo que el chat no sabe enseñar: que la interfaz pase a la terminal
      setTimeout(() => {
        if (!t.pending.size && !t.conv.prompts.length) emit('termchat:attention', { convId: t.conv.id, message: input.message || '' });
      }, 800);
    }
    return undefined;
  }

  function onPermission(t, input, res) {
    const name = input.tool_name;
    const toolInput = input.tool_input || {};
    const kind = name === 'AskUserQuestion' ? 'question' : name === 'ExitPlanMode' ? 'plan' : 'permission';
    const prompt = { id: crypto.randomUUID(), kind, toolName: name, input: cap(toolInput, 8000), terminal: true };
    const suggestions = input.permission_suggestions || [];
    if (kind === 'permission') {
      prompt.canAlways = !!suggestions.length;
      prompt.alwaysLabel = describeSuggestions(suggestions);
    }
    // El plan no admite respuesta por el hook: se contesta tecleando en su diálogo
    if (kind === 'plan') reply(res, {});
    t.pending.set(prompt.id, { res: kind === 'plan' ? null : res, toolName: name, key: name + JSON.stringify(toolInput), suggestions, input: toolInput });
    res.on('close', () => { if (!res.writableEnded && kind !== 'plan') settle(t, prompt.id, null); });
    t.conv.prompts.push(prompt);
    t.conv.busy = true;
    flush(t, true);
    emit('prompt:new', { convId: t.conv.id, kind, title: t.conv.title });
  }

  function settle(t, promptId, out) {
    const p = t.pending.get(promptId);
    if (!p) return;
    t.pending.delete(promptId);
    t.conv.prompts = t.conv.prompts.filter((x) => x.id !== promptId);
    if (p.res && out) reply(p.res, out);
    flush(t, true);
  }
  function settleAll(t) { for (const id of [...t.pending.keys()]) settle(t, id, {}); }

  const decide = (decision) => ({ hookSpecificOutput: { hookEventName: 'PermissionRequest', decision } });

  // Teclas con pausas, para que la interfaz de la terminal procese cada una
  function keys(id, seq) {
    let delay = 0;
    for (const k of seq) { setTimeout(() => write(id, k), delay); delay += 140; }
  }

  function respond(id, promptId, payload = {}) {
    const t = tabs.get(id);
    const p = t?.pending.get(promptId);
    const prompt = t?.conv.prompts.find((x) => x.id === promptId);
    if (!p || !prompt) return;
    if (prompt.kind === 'question') {
      if (payload.skip) return settle(t, promptId, decide({ behavior: 'deny', message: 'El usuario ha preferido no responder a las preguntas.' }));
      return settle(t, promptId, decide({ behavior: 'allow', updatedInput: { ...p.input, answers: payload.answers } }));
    }
    if (prompt.kind === 'plan') {
      // payload.option: número de la opción en el diálogo de la terminal
      const n = Math.max(1, Number(payload.option) || 1);
      const seq = Array(n - 1).fill('\x1b[B');
      if (payload.feedback?.trim()) seq.push(payload.feedback.trim().replace(/\r?\n/g, ' '));
      seq.push('\r');
      keys(id, seq);
      return settle(t, promptId, null);
    }
    if (payload.decision === 'allow') return settle(t, promptId, decide({ behavior: 'allow' }));
    if (payload.decision === 'always') {
      const mode = p.suggestions.find((s) => s.type === 'setMode')?.mode;
      if (mode) t.conv.permissionMode = mode;
      return settle(t, promptId, decide({ behavior: 'allow', updatedPermissions: p.suggestions }));
    }
    const fb = payload.feedback?.trim();
    return settle(t, promptId, decide({
      behavior: 'deny',
      message: fb ? `El usuario no lo permite y dice: ${fb}` : 'El usuario ha denegado el permiso.',
      interrupt: !fb,
    }));
  }

  // ---------- Ciclo de la pestaña ----------
  function start(id) {
    const t = tabs.get(id);
    if (!t || t.timer) return;
    t.timer = setInterval(() => read(t), POLL_MS);
    read(t);
  }

  function stop(id) {
    const t = tabs.get(id);
    if (!t) return;
    clearInterval(t.timer);
    t.timer = null;
    read(t);
    settleAll(t);
    closeReply(t);
    t.conv.busy = false;
    t.conv.queue = [];
    flush(t, true);
  }

  // Estado del título de la terminal: ✳ en espera, un spinner mientras trabaja. El título
  // parpadea a ✳ entre herramientas, así que el fin solo cuenta si dura; la respuesta la
  // cierran Stop, turn_duration o una interrupción
  function setBusy(id, busy) {
    const t = tabs.get(id);
    if (!t) return;
    clearTimeout(t.idleTimer);
    if (busy) {
      if (t.conv.busy) return;
      t.conv.busy = true;
      return flush(t, true);
    }
    if (!t.conv.busy) return;
    t.idleTimer = setTimeout(() => {
      if (!tabs.has(id) || !t.conv.busy) return;
      t.conv.busy = false;
      read(t);
      t.conv.queue = [];
      emit('chat:done', { convId: id, title: t.conv.title });
      flush(t, true);
    }, 1500);
  }

  // Un mensaje del chat: se pega en la terminal y se envía con Enter
  function send(id, { text = '', images = [] } = {}) {
    const t = tabs.get(id);
    if (!t) return;
    // Las imágenes van a archivos temporales y se pega su ruta: Claude Code las adjunta
    const files = images.map(({ mediaType = '', data } = {}) => {
      const ext = mediaType.split('/')[1];
      if (!data || !ext) return null;
      const f = path.join(os.tmpdir(), `claude-assistant-${crypto.randomUUID().slice(0, 8)}.${ext === 'jpeg' ? 'jpg' : ext}`);
      fs.writeFileSync(f, Buffer.from(data, 'base64'));
      return /\s/.test(f) ? `"${f}"` : f;
    }).filter(Boolean);
    const full = [text, ...files].filter(Boolean).join(' ');
    if (!full) return;
    // Los comandos (/x) y el modo bash (!) se teclean; el resto se pega para conservar los saltos de línea
    if (/^[/!]/.test(full) && !full.includes('\n')) write(id, full);
    if (/^\/(clear|reset|new)\s*$/.test(full)) {
      // A veces la transcripción nueva no aparece hasta el siguiente mensaje: el chat se vacía ya
      t.clearAt = Date.now();
      t.conv.messages = [];
      t.conv.tasks = [];
      t.reply = null;
      flush(t, true);
    }
    else write(id, `\x1b[200~${full}\x1b[201~`);
    setTimeout(() => write(id, '\r'), 120);
    if (t.conv.busy) {
      t.conv.queue.push({ id: crypto.randomUUID(), text: full });
      flush(t, true);
    }
  }

  function interrupt(id) {
    const t = tabs.get(id);
    if (!t) return '';
    write(id, '\x1b');
    t.conv.queue = [];
    flush(t, true);
    return '';
  }

  function detach(id) {
    const t = tabs.get(id);
    if (!t) return;
    clearInterval(t.timer);
    clearTimeout(t.flushTimer);
    clearTimeout(t.idleTimer);
    settleAll(t);
    tabs.delete(id);
  }

  return { hookSettings, attach, start, stop, setBusy, send, interrupt, respond, detach, has: (id) => tabs.has(id) };
}
