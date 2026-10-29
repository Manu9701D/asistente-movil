// Utilidades copiadas de Claude Assistant (engine.mjs)
const MAX_FIELD = 6000;

// Recorta los campos largos para no mandar megas a la interfaz
export function cap(value, max = MAX_FIELD) {
  if (typeof value === 'string') return value.length > max ? value.slice(0, max) + `\n… (${value.length - max} caracteres más)` : value;
  if (Array.isArray(value)) return value.map((v) => cap(v, max));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = cap(v, max);
    return out;
  }
  return value;
}

export function resultText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((b) => (b.type === 'text' ? b.text : `[${b.type}]`)).join('\n');
  return content == null ? '' : JSON.stringify(content);
}

// Lista de tareas como la de la terminal, a partir de TaskCreate / TaskUpdate
export function applyTask(conv, t, structured) {
  const tasks = conv.tasks;
  if (t.name === 'TaskCreate') {
    const id = structured?.task?.id || String(t.result || '').match(/#(\d+)/)?.[1];
    if (id) tasks.push({ id: String(id), subject: t.input.subject, activeForm: t.input.activeForm, status: 'pending' });
  } else if (t.name === 'TaskUpdate') {
    const task = tasks.find((x) => x.id === String(t.input.taskId));
    if (!task) return;
    if (t.input.status === 'deleted') conv.tasks = tasks.filter((x) => x !== task);
    else Object.assign(task, Object.fromEntries(['status', 'subject', 'activeForm'].filter((k) => t.input[k]).map((k) => [k, t.input[k]])));
  }
}

export function describeSuggestions(suggestions) {
  if (!suggestions?.length) return null;
  const rules = suggestions.flatMap((s) => s.rules || []);
  if (rules.length) {
    const r = rules[0];
    const what = r.ruleContent ? `${r.toolName}(${r.ruleContent})` : r.toolName;
    return `Sí, y no volver a preguntar por ${what}`;
  }
  const mode = suggestions.find((s) => s.type === 'setMode');
  if (mode?.mode === 'acceptEdits') return 'Sí, y aceptar ediciones el resto de la sesión';
  const dirs = suggestions.find((s) => s.type === 'addDirectories');
  if (dirs) return `Sí, y permitir acceso a ${dirs.directories?.[0] || 'esa carpeta'}`;
  return 'Sí, y no volver a preguntar';
}
