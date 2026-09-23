(() => {
  "use strict";

  const initializerUrl = document.currentScript && document.currentScript.src;
  if (!initializerUrl) {
    return;
  }

  const vendorBase = new URL("../assets/swagger-ui/", initializerUrl);
  const localStyleUrl = new URL("../stylesheets/api-explorer.css", initializerUrl);
  let swaggerUiLoad;

  function addStylesheet(id, href) {
    if (document.getElementById(id)) {
      return;
    }
    const link = document.createElement("link");
    link.id = id;
    link.rel = "stylesheet";
    link.href = href;
    document.head.appendChild(link);
  }

  function loadSwaggerUi() {
    if (window.SwaggerUIBundle) {
      return Promise.resolve();
    }
    if (!swaggerUiLoad) {
      addStylesheet(
        "swagger-ui-vendor-style",
        new URL("swagger-ui.css", vendorBase).href
      );
      addStylesheet("swagger-ui-local-style", localStyleUrl.href);
      swaggerUiLoad = new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.src = new URL("swagger-ui-bundle.js", vendorBase).href;
        script.onload = resolve;
        script.onerror = () => reject(new Error("Unable to load Swagger UI"));
        document.head.appendChild(script);
      });
    }
    return swaggerUiLoad;
  }

  function renderExplorer() {
    const root = document.getElementById("swagger-ui");
    if (!root || root.dataset.initialized === "true") {
      return;
    }
    root.dataset.initialized = "true";
    const specificationUrl = new URL(root.dataset.specUrl, window.location.href).href;

    loadSwaggerUi()
      .then(() => {
        if (!document.body.contains(root)) {
          return;
        }
        window.finbankSwaggerUi = window.SwaggerUIBundle({
          domNode: root,
          url: specificationUrl,
          presets: [window.SwaggerUIBundle.presets.apis],
          layout: "BaseLayout",
          deepLinking: true,
          displayOperationId: true,
          docExpansion: "list",
          filter: true,
          defaultModelsExpandDepth: 0,
          supportedSubmitMethods: [],
          tryItOutEnabled: false,
          persistAuthorization: false,
          validatorUrl: null,
          requestInterceptor: (request) => {
            const requestedUrl = new URL(request.url, window.location.href).href;
            if (requestedUrl !== specificationUrl) {
              throw new Error("API execution is disabled in this contract viewer");
            }
            request.method = "GET";
            request.credentials = "same-origin";
            return request;
          },
        });
      })
      .catch(() => {
        root.dataset.initialized = "false";
        root.textContent =
          "The API explorer could not load. Use the raw OpenAPI YAML link above.";
      });
  }

  if (typeof document$ !== "undefined") {
    document$.subscribe(renderExplorer);
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", renderExplorer);
  } else {
    renderExplorer();
  }
})();
