import {browserStorage, catalogKey, key, money, kopecks, starterCategories, validMonth} from "./store.js";

function el(tag, text, props) {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = text;
  return Object.assign(n, props);
}
export function startPlanner(root, accountStorage) {
  const $ = s => root.querySelector(s);
  const planner = $("#planner");
  if (!planner || planner.dataset.ready) return;
  planner.dataset.ready = "true";
  const cloud = planner.dataset.mode === "cloud", account = $("#account-action");
  const storage = accountStorage || browserStorage(starterCategories([...$("#starter-labels").children].map(n => n.textContent)));
  const savedText = cloud ? "Сохранено в аккаунте." : "Сохранено в этом браузере.";
  const periods = new Intl.DateTimeFormat("ru-RU", {month:"long", timeZone:"UTC"});
  for (let i = 0; i < 12; i++) {
    const n = periods.format(new Date(Date.UTC(2000, i, 15)));
    $("#month").add(new Option(n[0].toUpperCase() + n.slice(1), String(i+1).padStart(2,"0")));
  }
  let month = new Intl.DateTimeFormat("sv-SE", {timeZone:"Europe/Moscow", year:"numeric", month:"2-digit"}).format(new Date());
  let panel = "month", view, catalog, rows = [], baseline, busy = false, blocked = false;
  const fields = () => [...$("#plan").querySelectorAll("input")];
  const spending = () => [...$("#spending").querySelectorAll("input")];
  const values = () => panel === "catalog" ? rows : fields().map(f => f.value);
  const dirty = () => panel !== "history" && baseline !== undefined && JSON.stringify(values()) !== baseline;
  const status = t => { $("#status").textContent = t; };
  function accountLabel() { if (!cloud) account.textContent = dirty() ? "Сохранить и войти" : "Войти"; }
  function edited() {
    accountLabel();
    if (!blocked) status(dirty() ? "Есть несохранённые изменения." : "Изменений нет.");
  }
  function lock(state) {
    busy = state;
    $("#month").disabled = $("#year").disabled = state;
    for (const s of [".planner-nav", ".date-controls", "#month-panel", "#catalog-panel", "#history-panel"]) $(s).inert = state;
  }
  async function request(action, data) {
    const result = await storage(action, data);
    if (result?.error) throw Object.assign(new Error(), {kind:result.error});
    return result;
  }
  function failure(e, write) {
    const kind = e.kind || (cloud && write ? "unknown" : "failed");
    blocked = !write || kind === "unknown" || kind === "conflict";
    $("#reload-plan").hidden = !blocked;
    $("#save").disabled = $("#save-catalog").disabled = blocked;
    if (kind === "invalid") status("Проверьте введённые данные. Изменения не сохранены.");
    else if (kind === "conflict") status("Сохранённые данные изменились. Ваш ввод остался на экране. Загрузите актуальную версию.");
    else if (kind === "unknown" && write) status("Результат сохранения не подтверждён. Ваш ввод остался на экране. Проверьте сохранённые данные перед новой попыткой.");
    else status(write ? "Не удалось сохранить. Ваш ввод остался на экране." : "Не удалось прочитать данные. Попробуйте загрузить их снова.");
  }
  const formatInput = raw => { const n = kopecks(raw); return n === null ? "" : money(n).slice(0,-2); };
  function drawLine(line, i) {
    const id = "spend-" + i;
    const label = el("label", line.label + (line.archived ? " · в архиве" : ""), {className:"amount", htmlFor:id});
    const box = el("span", undefined, {className:"amount-box"});
    const input = el("input", undefined, {id, name:id, type:"text", inputMode:"decimal", autocomplete:"off", maxLength:80, value:formatInput(line.amount)});
    input.setAttribute("aria-describedby", id + "-error");
    const unit = el("span", "₽"); unit.setAttribute("aria-hidden","true");
    box.append(input, unit); label.append(box, el("small", "", {id:id+"-error"}));
    return label;
  }
  function totals(report = false) {
    let invalid;
    const all = fields();
    const amounts = all.map(f => {
      const error = $("#" + f.id + "-error");
      try { const value = kopecks(f.value); error.textContent = ""; f.removeAttribute("aria-invalid"); return value ?? 0n; }
      catch(e) { invalid ??= f; if (report || f === document.activeElement || f.hasAttribute("aria-invalid")) {
        error.textContent = e.message; f.setAttribute("aria-invalid","true");
      } return 0n; }
    });
    const [income, investment, ...lines] = amounts, spent = lines.reduce((a,b)=>a+b,0n), balance = income-investment-spent;
    for (const [id, n] of [["balance",balance],["income-total",income],["investment-total",investment],["spending-total",spent]])
      $("#"+id).textContent = invalid ? "—" : money(n);
    $("#balance").classList.toggle("negative", !invalid && balance < 0n);
    if (report && invalid) invalid.focus();
    return !invalid;
  }
  function renderCategories(focus) {
    const target = $("#categories");
    target.replaceChildren(...rows.map((c, i) => {
      const row = el("div", undefined, {className:"category-row"});
      const input = el("input", undefined, {value:c.label, readOnly:c.archived, placeholder:"Название категории"});
      input.setAttribute("aria-label", "Название категории " + (i+1));
      input.oninput = () => { c.label = input.value; input.removeAttribute("aria-invalid"); edited(); };
      row.append(input);
      const action = (name, label, disabled, callback) => {
        const b = el("button",name,{type:"button",className:"secondary",disabled});
        b.setAttribute("aria-label", label); b.onclick = callback; row.append(b);
      };
      const move = j => { [rows[i],rows[j]] = [rows[j],rows[i]]; renderCategories(j); edited(); };
      action("↑", "Переместить выше: " + c.label, i === 0, () => move(i-1));
      action("↓", "Переместить ниже: " + c.label, i === rows.length-1, () => move(i+1));
      if (c.archived && catalog.categories.find(x=>x.id===c.id)?.archived) row.append(el("span","В архиве"));
      else action(c.archived ? "Вернуть" : "В архив", (c.archived ? "Вернуть: " : "В архив: ") + c.label, false, () => {
        // An unsaved, unnamed row can be removed without ever entering the catalog.
        if (!c.label.trim() && !catalog.categories.some(x=>x.id===c.id)) rows.splice(i,1);
        else c.archived = !c.archived;
        renderCategories(); edited();
      });
      return row;
    }));
    if (focus !== undefined) target.children[focus]?.querySelector("input").focus();
  }
  function showRevision(v) {
    const dl = el("dl"), add = (label, value) => dl.append(el("dt",label),el("dd",value));
    const show = a => { const n = kopecks(a); return n === null ? "Не задано" : money(n); };
    add("Доход",show(v.income)); add("Инвестиции",show(v.investment));
    const spent = v.lines.reduce((n,l)=>n+(kopecks(l.amount) ?? 0n),0n);
    add("Расходы",money(spent));
    for (const l of v.lines) add(l.label+(l.archived ? " · в архиве" : ""),show(l.amount));
    add("Остаток",money((kopecks(v.income) ?? 0n)-(kopecks(v.investment) ?? 0n)-spent));
    $("#revision-detail").replaceChildren(dl);
  }
  async function load() {
    lock(true);
    blocked = true; baseline = undefined;
    $("#month").value = month.slice(5); $("#year").value = String(Number(month.slice(0,4)));
    for (const tab of root.querySelectorAll("[data-panel]")) {
      const selected = tab.dataset.panel === panel;
      if (selected) tab.setAttribute("aria-current","page"); else tab.removeAttribute("aria-current");
      $("#"+tab.dataset.panel+"-panel").hidden = !selected;
    }
    status("Загрузка…"); $("#reload-plan").hidden = true;
    try {
      if (panel === "month") {
        view = await request("month",{month});
        $("#income").value = formatInput(view.income); $("#investment").value = formatInput(view.investment);
        $("#spending").replaceChildren(...view.lines.map(drawLine)); totals();
        status(view.version ? savedText : "Пустой месяц. Сохраните план после ввода сумм.");
      } else if (panel === "catalog") {
        catalog = await request("catalog",{}); rows = catalog.categories.map(c=>({...c}));
        renderCategories(); status("");
      } else {
        const revisions = await request("history",{month});
        const select = $("#revision"); select.replaceChildren(); $("#revision-detail").replaceChildren();
        for (const r of revisions) {
          const when = r.at ? " · "+new Intl.DateTimeFormat("ru-RU",{dateStyle:"short",timeStyle:"medium",timeZone:"Europe/Moscow"}).format(new Date(r.at))+" (МСК)" : "";
          select.add(new Option("Версия "+r.version+when,r.version));
        }
        select.disabled = !revisions.length;
        if (revisions.length) showRevision(await request("revision",{month,version:Number(select.value)}));
        status(revisions.length ? "Сохранённые версии доступны только для чтения." : "Сохранённых версий пока нет.");
      }
      baseline = JSON.stringify(values()); blocked = false; accountLabel(); $("#save").disabled = $("#save-catalog").disabled = false;
    } catch(e) { failure(e,false); }
    finally { lock(false); }
  }
  async function save() {
    if (busy || blocked) return false;
    let action, data;
    if (panel === "month") {
      if (!totals(true)) { status("Проверьте отмеченные суммы."); return false; }
      const inputs = spending();
      action = "save"; data = {month,version:view.version,catalogVersion:view.catalogVersion,stamp:view.stamp,
        income:$("#income").value,investment:$("#investment").value,lines:view.lines.map((l,i)=>({id:l.id,amount:inputs[i].value}))};
    } else if (panel === "catalog") {
      const invalid = rows.findIndex(c=>!c.label.trim());
      if (invalid >= 0) {
        const f = $("#categories").children[invalid].querySelector("input"); f.setAttribute("aria-invalid","true"); f.focus();
        status("Введите название категории."); return false;
      }
      action = "saveCatalog"; data = {version:catalog.version,categories:rows.map(c=>({...c,label:c.label.trim()}))};
    } else return false;
    lock(true);
    try {
      await request(action,data);
      await load();
      if (!blocked) status(panel === "catalog" ? "Категории сохранены." : savedText);
      return !blocked;
    } catch(e) { failure(e,true); return false; }
    finally { lock(false); }
  }
  async function canLeave() {
    if (busy) return false;
    if (!dirty()) return true;
    const d = $("#leave-dialog"); d.returnValue = "stay"; d.showModal();
    const choice = await new Promise(resolve=>d.addEventListener("close",()=>resolve(d.returnValue),{once:true}));
    return choice === "discard" || choice === "save" && await save();
  }
  $("#plan").oninput = () => { totals(); edited(); };
  $("#plan").onsubmit = e => { e.preventDefault(); void save(); };
  $("#save-catalog").onclick = save;
  $("#clear").onclick = () => {
    if (busy || blocked || !fields().some(f=>f.value) || !confirm("Очистить все суммы? Чтобы записать изменение, нажмите «Сохранить».")) return;
    fields().forEach(f=>f.value=""); totals(); edited();
  };
  $("#add-category").onclick = () => { rows.push({id:crypto.randomUUID(),label:"",archived:false}); renderCategories(rows.length-1); edited(); };
  $("#reload-plan").onclick = async () => { if (!busy && (!dirty() || confirm("Загрузить сохранённые данные и отменить текущий ввод?"))) await load(); };
  for (const tab of root.querySelectorAll("[data-panel]")) tab.onclick = async () => {
    if (panel !== tab.dataset.panel && await canLeave()) { panel = tab.dataset.panel; await load(); }
  };
  async function selectMonth() {
    const year = $("#year"), next = year.value.padStart(4,"0")+"-"+$("#month").value;
    const valid = year.validity.valid && validMonth(next);
    year.value = String(Number(month.slice(0,4))); $("#month").value = month.slice(5);
    if (valid && next !== month && await canLeave()) { month = next; await load(); }
  }
  $("#year").onchange = $("#month").onchange = selectMonth;
  $("#revision").onchange = async () => {
    lock(true);
    try { showRevision(await request("revision",{month,version:Number($("#revision").value)})); }
    catch(e) { $("#revision-detail").replaceChildren(); failure(e,false); }
    finally { lock(false); }
  };
  account.onclick = async e => {
    if (busy || dirty()) { e.preventDefault(); if (!busy && await save()) location.assign(account.href); }
  };
  window.addEventListener("beforeunload", e => { if (dirty() || busy) { e.preventDefault(); e.returnValue = ""; } });
  if (!cloud) window.addEventListener("storage", e => {
    if (![null,catalogKey,key(month)].includes(e.key)) return;
    if (busy) return;
    if (!dirty()) void load();
    else status("Данные в другой вкладке изменились. Ваш ввод остался на экране.");
  });
  void load();
}
if (document.querySelector('#planner[data-mode="local"]')) startPlanner(document);
