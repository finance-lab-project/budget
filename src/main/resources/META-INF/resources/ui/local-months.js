import {key, months, replaceMonth} from "./store.js";
const select = document.querySelector("#local-months"), remove = document.querySelector("#delete-local");
const status = document.querySelector("#local-status");
function update() {
  select.replaceChildren();
  try {
    for (const m of months()) select.add(new Option(m, m));
    if (!select.length) select.add(new Option("Нет сохранённых месяцев", ""));
    remove.disabled = !select.value;
  } catch { remove.disabled = true; status.textContent = "Хранилище браузера недоступно."; }
}
remove.onclick = async () => {
  const m = select.value;
  try {
    const raw = localStorage.getItem(key(m));
    if (!raw || !confirm("Удалить месяц " + m + " и его версии из этого браузера?")) return;
    status.textContent = await replaceMonth(m, raw, null) ? "Месяц удалён из браузера." : "Месяц изменился в другой вкладке.";
    update();
  } catch { status.textContent = "Не удалось удалить месяц."; }
};
update();
