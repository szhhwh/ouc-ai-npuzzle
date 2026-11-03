"use strict";
(() => {
  const buttons = [...document.querySelectorAll(".tab-btn")];
  const panels = buttons.map((button) =>
    document.getElementById(button.getAttribute("aria-controls")),
  );
  let activePanel;
  const notify = (panel, action, detail) =>
    panel.dispatchEvent(new CustomEvent(`npuzzle:${action}`, { detail }));

  function activate(button, focusPanel = true) {
    const index = buttons.indexOf(button);
    if (activePanel && activePanel !== panels[index])
      notify(activePanel, "pause");
    activePanel = panels[index];
    buttons.forEach((tab, i) => {
      const selected = i === index;
      tab.classList.toggle("active", selected);
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
      panels[i].hidden = !selected;
    });
    notify(activePanel, "resize");
    if (focusPanel) activePanel.focus({ preventScroll: true });
  }

  buttons.forEach((button, index) => {
    button.addEventListener("click", () => activate(button));
    button.addEventListener("keydown", (event) => {
      let next;
      if (event.key === "ArrowRight") next = (index + 1) % buttons.length;
      else if (event.key === "ArrowLeft")
        next = (index + buttons.length - 1) % buttons.length;
      else if (event.key === "Home") next = 0;
      else if (event.key === "End") next = buttons.length - 1;
      else return;
      event.preventDefault();
      activate(buttons[next], false);
      buttons[next].focus();
    });
  });

  document.addEventListener("keydown", (event) => {
    if (
      event.defaultPrevented ||
      document.getElementById("library-dialog").open ||
      event.altKey ||
      event.ctrlKey ||
      event.metaKey ||
      event.target.closest(
        "input, select, textarea, [contenteditable], [role=tab]",
      )
    )
      return;
    if (event.code === "Space") {
      if (event.target.closest("button")) return;
      event.preventDefault();
      notify(activePanel, "play");
    } else if (event.code === "ArrowLeft" || event.code === "ArrowRight") {
      event.preventDefault();
      notify(activePanel, "step", event.code === "ArrowRight" ? 1 : -1);
    }
  });

  const cinema = document.getElementById("cinema");
  cinema.addEventListener("click", () => {
    const enabled = document.body.classList.toggle("cinema");
    cinema.textContent = enabled ? "退出录屏" : "录屏布局";
    cinema.setAttribute("aria-pressed", String(enabled));
    notify(activePanel, "resize");
  });
  window.addEventListener("resize", () => notify(activePanel, "resize"));
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) panels.forEach((panel) => notify(panel, "pause"));
  });
  activate(
    buttons.find((button) => button.getAttribute("aria-selected") === "true"),
    false,
  );
})();
