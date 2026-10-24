/* Migrax docs: counts install intent in Google Analytics (mkdocs.yml, extra.analytics).
 * Sends "install_copy" when someone copies a code block that installs Migrax and
 * "release_download" when someone clicks a file on the Download page. The theme creates
 * window.dataLayer only after a visitor accepts cookies, so nothing is sent otherwise. */
(function () {
  "use strict";

  // The Gradle plugin, or a Gradle dependency on a Migrax artifact ("implementation" also
  // matches testImplementation).
  var GRADLE = new RegExp("id[ (]+[\"']org\\.migrax[\"']|" +
    "(implementation|compileOnly|classpath)[ (]+[\"']org\\.migrax:", "i");

  // How a copied code block installs Migrax; the first match wins. "container" is any block
  // that uses the image (docker run, Dockerfile, Kubernetes), not only a first pull.
  var METHODS = [
    ["homebrew", /brew install/],
    ["scoop", /scoop install/],
    ["installer", /install\.(sh|ps1)/],
    ["container", /ghcr\.io\/fsmutimeer\/migrax/],
    ["gradle", GRADLE],
    ["maven", /<groupId>org\.migrax<\/groupId>|org\.migrax:migrax/]
  ];

  // gtag.js only reads dataLayer entries that are an "arguments" object.
  function gtag() {
    window.dataLayer.push(arguments);
  }

  function send(name, params) {
    if (!window.dataLayer) return;
    params.page_path = location.pathname;
    gtag("event", name, params);
  }

  function method(text) {
    for (var i = 0; i < METHODS.length; i++) {
      if (METHODS[i][1].test(text)) return METHODS[i][0];
    }
    return null;
  }

  // auxclick: a middle-click opens a download in a new tab. "Save link as" is not seen, so
  // download counts are a lower bound.
  function onClick(event) {
    var target = event.target instanceof Element ? event.target : null;
    if (!target) return;

    // The theme's copy button on code blocks names the code it copies.
    var copy = target.closest("[data-clipboard-target]");
    if (copy) {
      var code = document.querySelector(copy.getAttribute("data-clipboard-target"));
      var found = code && method(code.textContent);
      if (found) send("install_copy", { method: found });
      return;
    }

    var link = target.closest("a[href*='downloads/']");
    if (link) {
      var file = link.getAttribute("href").split("/").pop();
      var version = link.getAttribute("href").split("/").slice(-2, -1)[0];
      if (/\.(zip|jar)$|SHA256SUMS$/.test(file)) {
        send("release_download", { file_name: file, version: version });
      }
    }
  }

  document.addEventListener("click", onClick, true);
  document.addEventListener("auxclick", onClick, true);
})();
