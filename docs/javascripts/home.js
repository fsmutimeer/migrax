/* Migrax docs: home page animations (typing terminal, counters, scroll reveal, spotlight).
 * Runs on every page load, including Material's instant navigation (document$). Respects
 * prefers-reduced-motion: everything is shown in its final state without animation. */
(function () {
  "use strict";

  var reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var run = 0; // incremented on every page load, so old timers stop

  // The session the terminal plays. Output is what Migrax really prints (shortened lint text).
  var MIGRATIONS = "src/main/resources/db/migration";
  var SCRIPT = [
    { cmd: "migrax generate" },
    { out: "migrax: compiling with Maven...", cls: "mx-t-dim" },
    { out: "Created " + MIGRATIONS + "/0002_add_customers_phone.sql:" },
    { out: "  - add column customers.phone", cls: "mx-t-ok" },
    { out: "Rollback script written to " + MIGRATIONS + "/rollback.", cls: "mx-t-dim" },
    { out: "Review the SQL, then run 'migrax migrate'." },
    { pause: 900 },
    { cmd: "migrax migrate" },
    { out: "Applying migration '0002_add_customers_phone.sql'...", cls: "mx-t-dim" },
    { out: "Successfully applied migration '0002_add_customers_phone.sql'.", cls: "mx-t-ok" },
    { out: "Applied 1 migration(s); 2 total in " + MIGRATIONS + "." },
    { pause: 1400 },
    { cmd: "# rename the field phone to mobile in Customer.java", cls: "mx-t-dim" },
    { cmd: "migrax generate" },
    { out: "migrax: compiling with Maven...", cls: "mx-t-dim" },
    { ask: "Did you rename customers.phone to customers.mobile? [y/N] ", answer: "y" },
    { out: "Created " + MIGRATIONS + "/0003_rename_customers_phone.sql:" },
    { out: "  - rename column customers.phone to mobile", cls: "mx-t-ok" },
    { out: "Rollback script written to " + MIGRATIONS + "/rollback.", cls: "mx-t-dim" },
    { out: "0003_rename_customers_phone.sql, statement 1: warning MX008", cls: "mx-t-warn" },
    { out: "  Application instances still running the old code use the old name and will fail.", cls: "mx-t-dim" },
    { out: "Review the SQL, then run 'migrax migrate'." },
    { pause: 5000 }
  ];

  function span(text, cls) {
    var el = document.createElement("span");
    if (cls) el.className = cls;
    el.textContent = text;
    return el;
  }

  function prompt() {
    return span("$ ", "mx-t-prompt");
  }

  function startTerminal(pre, id) {
    var code = pre.querySelector("code") || pre;
    var cursor = span("", "mx-t-cursor");

    function scroll() {
      pre.scrollTop = pre.scrollHeight;
    }

    if (reducedMotion) {
      code.textContent = "";
      SCRIPT.forEach(function (step) {
        if (step.cmd) { code.appendChild(prompt()); code.appendChild(span(step.cmd + "\n", step.cls || "mx-t-cmd")); }
        if (step.out) code.appendChild(span(step.out + "\n", step.cls));
        if (step.ask) { code.appendChild(span(step.ask, "mx-t-ask")); code.appendChild(span(step.answer + "\n", "mx-t-answer")); }
      });
      return;
    }

    function later(fn, ms) {
      setTimeout(function () { if (id === run) fn(); }, ms);
    }

    function type(text, cls, done) {
      var el = span("", cls);
      code.insertBefore(el, cursor);
      var i = 0;
      (function next() {
        el.textContent = text.slice(0, ++i);
        scroll();
        if (i < text.length) later(next, 28 + Math.random() * 45);
        else later(done, 260);
      })();
    }

    function step(index) {
      if (index >= SCRIPT.length) {
        later(function () { code.textContent = ""; code.appendChild(cursor); step(0); }, 200);
        return;
      }
      var s = SCRIPT[index];
      if (s.pause) return later(function () { step(index + 1); }, s.pause);
      if (s.cmd) {
        code.insertBefore(prompt(), cursor);
        return type(s.cmd, s.cls || "mx-t-cmd", function () {
          code.insertBefore(span("\n"), cursor);
          later(function () { step(index + 1); }, 350);
        });
      }
      if (s.ask) {
        code.insertBefore(span(s.ask, "mx-t-ask"), cursor);
        scroll();
        return later(function () {
          type(s.answer, "mx-t-answer", function () {
            code.insertBefore(span("\n"), cursor);
            later(function () { step(index + 1); }, 300);
          });
        }, 1100);
      }
      code.insertBefore(span(s.out + "\n", s.cls), cursor);
      scroll();
      later(function () { step(index + 1); }, 110 + Math.random() * 120);
    }

    code.textContent = "";
    code.appendChild(cursor);
    later(function () { step(0); }, 700);
  }

  function countUp(el) {
    var target = parseInt(el.getAttribute("data-mx-count"), 10);
    if (reducedMotion || !target) return;
    var start = null;
    var duration = 1200;
    function frame(time) {
      if (start === null) start = time;
      var t = Math.min(1, (time - start) / duration);
      el.textContent = Math.round(target * (1 - Math.pow(1 - t, 3)));
      if (t < 1) requestAnimationFrame(frame);
    }
    el.textContent = "0";
    requestAnimationFrame(frame);
  }

  function reveal(root) {
    root.querySelectorAll(".mx-home .grid.cards > ul > li, .mx-home .grid.mx-example > *")
      .forEach(function (el) { el.classList.add("mx-reveal"); });
    var items = root.querySelectorAll(".mx-reveal");
    if (reducedMotion || !("IntersectionObserver" in window)) {
      items.forEach(function (el) { el.classList.add("is-visible"); });
      return;
    }
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("is-visible");
          observer.unobserve(entry.target);
        }
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.12 });
    items.forEach(function (el) { observer.observe(el); });
  }

  function spotlight(root) {
    var hero = root.querySelector("[data-mx-spotlight]");
    if (!hero || reducedMotion) return;
    hero.addEventListener("pointermove", function (event) {
      var box = hero.getBoundingClientRect();
      hero.style.setProperty("--mx-x", (event.clientX - box.left) + "px");
      hero.style.setProperty("--mx-y", (event.clientY - box.top) + "px");
    });
  }

  function init() {
    window.mxReady = true;
    var id = ++run;
    document.documentElement.classList.add("mx-js");
    var terminal = document.querySelector("[data-mx-terminal]");
    if (terminal) startTerminal(terminal, id);
    document.querySelectorAll("[data-mx-count]").forEach(countUp);
    reveal(document);
    spotlight(document);
  }

  if (window.document$ && typeof window.document$.subscribe === "function") {
    window.document$.subscribe(init);
  } else if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
