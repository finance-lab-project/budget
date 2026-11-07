const prefix = "finance-lab.guest.v1.";
export const validMonth = value => /^(?!0000)[0-9]{4}-(0[1-9]|1[0-2])$/.test(value);
export const key = month => prefix + month;
export function months() {
  return Object.keys(localStorage).filter(k => k.startsWith(prefix))
    .map(k => k.slice(prefix.length)).filter(validMonth).sort();
}

// Work in integer kopecks across the full NUMERIC(19,2) range.
export function kopecks(raw) {
  if (typeof raw !== "string" || raw.length > 80) throw Error("Проверьте сумму.");
  const value = raw.replace(/^[ \u00a0\u202f]+|[ \u00a0\u202f]+$/gu, "");
  if (!value) return null;
  if (!/^(?:(?:[0-9]+|[0-9]{1,3}(?:[ \u00a0\u202f][0-9]{3})+)(?:[.,][0-9]{1,2})?|[.,][0-9]{1,2})$/u.test(value)
      || /[\r\n]/u.test(value)) throw Error("Введите сумму с точностью до копеек.");
  const [whole, fraction = ""] = value.replace(/[ \u00a0\u202f]/gu, "").split(/[.,]/u);
  const amount = BigInt(whole || "0") * 100n + BigInt(fraction.padEnd(2, "0"));
  if (amount > 9999999999999999999n) throw Error("Сумма превышает допустимый предел.");
  return amount;
}
export function money(amount) {
  const absolute = amount < 0n ? -amount : amount;
  return (amount < 0n ? "−" : "") + (absolute / 100n).toLocaleString("ru-RU") + ","
    + String(absolute % 100n).padStart(2, "0") + "\u00a0₽";
}
export function decode(raw, count) {
  const data = JSON.parse(raw);
  if (!data || !Array.isArray(data.spending) || data.spending.length !== count)
    throw Error("Не удалось прочитать сохранённый месяц.");
  [data.income, data.investment, ...data.spending].forEach(kopecks);
  return data;
}
export const catalogKey = "finance-lab.guest.categories.v1";
const lock = "finance-lab.guest.write";
export async function replaceMonth(month, expected, replacement) {
  return navigator.locks.request(lock, () => {
    if (localStorage.getItem(key(month)) !== expected) return false;
    if (replacement === null) localStorage.removeItem(key(month));
    else localStorage.setItem(key(month), replacement);
    return true;
  });
}

function requireValid(ok) { if (!ok) throw Error("Не удалось прочитать данные браузера."); }
function validateCategories(rows) {
  requireValid(Array.isArray(rows) && new Set(rows.map(c => c.id)).size === rows.length);
  rows.forEach(c => requireValid(typeof c.id === "string" && c.id.length > 0
    && typeof c.label === "string" && c.label.trim() && typeof c.archived === "boolean"));
}
export function starterCategories(labels) {
  return labels.map((label, i) => ({id:"starter-" + i, label, archived:false}));
}
export function localCatalog(starters) {
  const raw = localStorage.getItem(catalogKey);
  if (raw === null) return {version:1, categories:starters};
  const c = JSON.parse(raw);
  requireValid(Number.isSafeInteger(c.version) && c.version > 1);
  validateCategories(c.categories);
  return c;
}
// Preserve saved v1 months. Their first recorded version has no known save time.
export function versions(raw, starters) {
  if (raw === null) return [];
  const v = JSON.parse(raw);
  if (v.schema === undefined) {
    const old = decode(raw, starters.length);
    return [{version:1, catalogVersion:1, at:null, income:old.income, investment:old.investment,
      lines:starters.map((c, i) => ({...c, amount:old.spending[i]}))}];
  }
  requireValid(v.schema === 2 && Array.isArray(v.versions) && v.versions.length);
  v.versions.forEach((s, i) => {
    requireValid(s.version === i + 1 && Number.isSafeInteger(s.catalogVersion) && s.catalogVersion > 0);
    validateCategories(s.lines);
    [s.income, s.investment, ...s.lines.map(l => l.amount)].forEach(kopecks);
  });
  return v.versions;
}
function current(last, catalog) {
  const saved = new Map(last?.lines.map(l => [l.id, l.amount]));
  requireValid([...saved.keys()].every(id => catalog.categories.some(c => c.id === id)));
  return {version:last?.version ?? 0, catalogVersion:catalog.version, stamp:last?.stamp ?? null,
    income:last?.income ?? "", investment:last?.investment ?? "",
    lines:catalog.categories.filter(c => !c.archived || saved.has(c.id)).map(c => ({...c, amount:saved.get(c.id) ?? ""}))};
}

export function browserStorage(starters) {
  return async (action, data) => {
    const operation = () => {
      const catalog = localCatalog(starters);
      if (action === "catalog") return catalog;
      if (action === "saveCatalog") {
        if (catalog.version !== data.version) return {error:"conflict"};
        const rows = data.categories;
        validateCategories(rows);
        const byId = new Map(rows.map(c => [c.id,c]));
        if (catalog.categories.some(c => !byId.has(c.id) || c.archived && !byId.get(c.id).archived)) return {error:"invalid"};
        if (JSON.stringify(catalog.categories) !== JSON.stringify(rows)) {
          localStorage.setItem(catalogKey, JSON.stringify({version:catalog.version + 1, categories:rows}));
        }
        return {saved:true};
      }
      if (!validMonth(data.month)) return {error:"invalid"};
      const raw = localStorage.getItem(key(data.month)), history = versions(raw, starters), last = history.at(-1);
      const view = current(last, catalog);
      if (action === "month") return view;
      if (action === "history") return history.toReversed().map(({version, at}) => ({version, at}));
      if (action === "revision") return history.find(s => s.version === data.version) ?? {error:"invalid"};
      if (action !== "save") return {error:"invalid"};
      if (catalog.version !== data.catalogVersion || view.version !== data.version || view.stamp !== data.stamp) return {error:"conflict"};
      const lines = view.lines.map((c, i) => {
        requireValid(c.id === data.lines[i]?.id);
        return {...c, amount:data.lines[i].amount};
      });
      const amounts = [data.income, data.investment, ...lines.map(l => l.amount)].map(kopecks);
      const previous = last ? [last.income, last.investment, ...lines.map(c => last.lines.find(l => l.id === c.id)?.amount ?? "")].map(kopecks) : [];
      if (!last || last.lines.length !== lines.length || amounts.some((a, i) => a !== previous[i])) {
        const version = {version:view.version+1, catalogVersion:catalog.version, at:new Date().toISOString(),
          stamp:crypto.randomUUID(), income:data.income, investment:data.investment, lines};
        localStorage.setItem(key(data.month), JSON.stringify({schema:2,versions:[...history, version]}));
      }
      return {saved:true};
    };
    return action === "save" || action === "saveCatalog" ? navigator.locks.request(lock, operation) : operation();
  };
}
