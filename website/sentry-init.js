(function () {
  "use strict";
  if (!window.Sentry) return;
  window.Sentry.init({
    dsn: "https://9680d640a14320fb3282ed4112c55c3f@o4508955926396928.ingest.us.sentry.io/4512245212512256",
    environment: location.hostname === "localhost" ? "development" : "production",
    release: "ai-daily-memory-web@0.1.0",
    sendDefaultPii: false,
    maxBreadcrumbs: 0,
    tracesSampleRate: 0,
    beforeBreadcrumb: function () { return null; },
    beforeSend: function (event) {
      [
        "breadcrumbs", "contexts", "culprit", "debug_meta", "extra", "logentry", "message",
        "request", "server_name", "spans", "tags", "threads", "transaction", "user"
      ].forEach(function (field) { delete event[field]; });
      if (event.exception && event.exception.values) {
        event.exception.values.forEach(function (value) {
          delete value.value;
          if (value.stacktrace && value.stacktrace.frames) {
            value.stacktrace.frames.forEach(function (frame) {
              if (typeof frame.filename === "string") {
                var normalizedPath = frame.filename.replace(/\\/g, "/");
                if (normalizedPath.charAt(0) === "/" || /^[A-Za-z]:\//.test(normalizedPath)) {
                  frame.filename = normalizedPath.split("/").pop();
                }
              }
              ["abs_path", "vars", "pre_context", "context_line", "post_context"]
                .forEach(function (field) { delete frame[field]; });
            });
          }
        });
      }
      return event;
    }
  });
})();
