import {key, months, versions, replaceMonth, money, kopecks, starterCategories, localCatalog, catalogKey} from "./store.js";

const list = document.querySelector("#months"), status = document.querySelector("#status");
const send = document.querySelector("#send");
const starters = starterCategories([...document.querySelector("#starter-labels").children].map(n => n.textContent));
const importedKey = "finance-lab.guest.imported-catalog.v1";
let imported = {}, catalogPending = false;
const elem = (tag, text) => { const el = document.createElement(tag); el.textContent = text; return el; };
const price = value => value === "" ? "не задано" : money(kopecks(value));
const pending = [];
async function post(params) {
  const response = await fetch("transfer", {method:"POST", body:params,
    headers:{"X-CSRF-Token":document.getElementById("csrf").value}, redirect:"error"});
  if (response.status === 401 || response.status === 403) throw Error("Сеанс завершён. Войдите снова. Локальная копия сохранена.");
  return {response, reply:await response.json()};
}
try {
  const catalog = localCatalog(starters);
  const rawCatalog = localStorage.getItem(catalogKey);
  imported = JSON.parse(localStorage.getItem(importedKey) || "{}");
  catalogPending = catalog.version > 1 && imported.version !== catalog.version;
  if (catalogPending) {
    const section = document.querySelector("#catalog-transfer"), review = document.querySelector("#catalog-review");
    const button = document.querySelector("#copy-categories");
    section.hidden = false;
    review.textContent = "Перенесите названия и порядок, чтобы использовать их в аккаунте.";
    let version;
    button.onclick = async () => {
      button.disabled = true;
      try {
        if (localStorage.getItem(catalogKey) !== rawCatalog) throw Error("Категории в браузере изменились. Откройте перенос заново.");
        const params = new URLSearchParams({operation:"catalog",categories:JSON.stringify(catalog.categories)});
        if (version !== undefined) params.set("version",version);
        const {response, reply} = await post(params);
        if (!response.ok) throw Error(reply.message);
        if (reply.saved) {
          imported = {version:catalog.version, mapping:reply.mapping};
          localStorage.setItem(importedKey,JSON.stringify(imported));
          review.textContent = "Категории сохранены в аккаунте."; button.hidden = true;
        } else {
          version = reply.version; button.textContent = "Подтвердить категории";
          review.replaceChildren(elem("p",reply.fresh ? "Так будут выглядеть категории аккаунта:"
            : "Существующие категории аккаунта сохранят свои названия и порядок. Получится такой список:"),
            elem("p",reply.preview.join(" · ")));
        }
      } catch(e) { review.textContent = e.message || "Не удалось перенести категории."; version = undefined; }
      finally { button.disabled = false; }
    };
  }
  for (const month of months()) {
    const raw = localStorage.getItem(key(month));
    let history;
    try { history = versions(raw, starters); } catch { /* Show a recovery message for this month below. */ }
    if (history && JSON.parse(raw).transferred === history.at(-1).version) continue;
    const row = elem("section", ""), title = elem("h2", new Intl.DateTimeFormat("ru-RU",
      {month:"long",year:"numeric",timeZone:"UTC"}).format(new Date(month + "-01T00:00:00Z")));
    const detail = elem("div", "");
    row.className = "surface transfer-month"; row.append(title, detail); list.append(row);
    if (history) pending.push({month, raw, draft:history.at(-1), history, detail, mappings:{}});
    else detail.textContent = "Формат месяца не распознан. Локальная копия сохранена.";
  }
  if (!list.children.length && !catalogPending) window.location.replace("./");
  else send.hidden = !pending.length;
} catch { status.textContent = "Хранилище браузера недоступно. Вы можете открыть бюджет аккаунта."; }

async function transfer(item, decision = {}) {
  const {month, raw, draft, detail} = item;
  const params = new URLSearchParams({month, income:draft.income, investment:draft.investment, ...item.mappings, ...decision});
  draft.lines.forEach((line, i) => {
    params.append("label",line.label); params.set("spend-" + i,line.amount);
    const target = imported.mapping?.[line.id];
    if (target && !params.has("map-"+i)) params.set("map-"+i,target);
  });
  if (item.history.length > 1) params.set("earlier",JSON.stringify(item.history.slice(0,-1).map(s => {
    const amounts = new Map(s.lines.map(l => [l.id,l.amount]));
    return {income:s.income,investment:s.investment,spending:draft.lines.map(l => amounts.get(l.id) ?? "")};
  })));
  try {
    if (localStorage.getItem(key(month)) !== raw) throw Error("Месяц изменился в другой вкладке. Откройте перенос заново.");
    const {response, reply} = await post(params);
    detail.replaceChildren();
    if (response.ok && reply.saved) {
      const retained = item.history.length > 1 ? JSON.stringify({...JSON.parse(raw),transferred:draft.version}) : null;
      detail.textContent = await replaceMonth(month, raw, retained) ? "Сохранено в аккаунте." + (reply.copiedHistory ? " Суммы прошлых версий перенесены; оригиналы остались в браузере." : retained ? " Прошлые версии остались в браузере." : "")
        : "Сохранено в аккаунте. В браузере осталась более новая версия.";
    } else if (response.status === 409 && reply.version !== undefined) {
      detail.append(elem("p", "Проверьте соответствие категорий и суммы. Переносятся только заполненные поля."));
      for (const change of reply.changes) detail.append(elem("p", change.label + " · в аккаунте " + price(change.cloud)
          + " → из браузера " + price(change.local)));
      const fields = [];
      for (const [name, label] of Object.entries(reply.unresolved)) {
        const select = document.createElement("select");
        select.append(new Option("Выберите категорию", ""));
        for (const opt of reply.options) select.append(new Option(opt.label, opt.id));
        const field = elem("label", label); field.append(select); detail.append(field); fields.push([name, select]);
      }
      const apply = elem("button", "Подтвердить перенос"); apply.className = "secondary";
      apply.onclick = async () => {
        for (const [name, field] of fields) {
          if (!field.value) { field.focus(); return; }
          item.mappings[name] = field.value;
        }
        apply.disabled = true;
        await transfer(item, fields.length ? {} : {version:reply.version, catalog:reply.catalog});
      };
      detail.append(apply);
    } else throw Error(reply.message || "Перенос не подтверждён. Локальная копия сохранена.");
  } catch (err) {
    detail.textContent = err instanceof TypeError ? "Не удалось связаться с сервером. Локальная копия сохранена." : err.message;
  }
}
send.onclick = async () => {
  send.disabled = true;
  status.textContent = "Переносим выбранные месяцы…";
  for (const item of pending) await transfer(item);
  status.textContent = "Откройте бюджет или проверьте сообщения у каждого месяца.";
  send.hidden = true;
};
