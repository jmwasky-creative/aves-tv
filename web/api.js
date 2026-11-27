(function (global) {
  var DEFAULT_BASE = "http://127.0.0.1:53319";

  function originBase() {
    if (global.location && (global.location.protocol === "http:" || global.location.protocol === "https:")) {
      return global.location.origin;
    }
    return DEFAULT_BASE;
  }

  var api = {
    base: originBase(),
    onRequestLog: null,

    setBase: function (url) {
      this.base = String(url || DEFAULT_BASE).replace(/\/$/, "");
    },

    request: function (method, path, body, headers) {
      var start = Date.now();
      var skipLog = path.indexOf("/api/v1/logs") === 0 || path.indexOf("poll=1") !== -1;
      return fetch(this.base + path, {
        method: method,
        body: body || undefined,
        headers: headers || undefined,
      }).then(function (res) {
        return res.text().then(function (text) {
          var data = null;
          if (text) {
            try {
              data = JSON.parse(text);
            } catch (e) {
              data = { error: text };
            }
          }
          if (!skipLog && typeof api.onRequestLog === "function") {
            api.onRequestLog({
              method: method,
              path: path,
              status: res.status,
              elapsedMs: Date.now() - start,
              error: res.ok ? "" : ((data && data.error) || "请求失败"),
            });
          }
          if (!res.ok) {
            var err = new Error((data && data.error) || "请求失败");
            err.status = res.status;
            throw err;
          }
          return data;
        });
      }, function (netErr) {
        if (!skipLog && typeof api.onRequestLog === "function") {
          api.onRequestLog({
            method: method,
            path: path,
            status: 0,
            elapsedMs: Date.now() - start,
            error: (netErr && netErr.message) || "network error",
          });
        }
        throw netErr;
      });
    },

    info: function () {
      return this.request("GET", "/api/v1/info");
    },

    albums: function () {
      return this.request("GET", "/api/v1/albums");
    },

    photos: function (album) {
      return this.request("GET", "/api/v1/albums/" + encodeURIComponent(album) + "/photos");
    },

    thumbUrl: function (id) {
      return this.base + "/api/v1/photos/" + encodeURIComponent(id) + "/thumb";
    },

    upload: function (album, file, mode) {
      var query = new URLSearchParams();
      query.set("name", file.name || "photo.jpg");
      query.set("mime", file.type || "image/jpeg");
      query.set("mode", mode === "replace" ? "replace" : "append");
      if (file.lastModified > 0) query.set("takenMs", String(file.lastModified));
      var path = "/api/v1/albums/" + encodeURIComponent(album) + "/upload?" + query.toString();
      return this.request("POST", path, file, {
        "Content-Type": file.type || "application/octet-stream",
      });
    },

    deletePhoto: function (id) {
      return this.request("DELETE", "/api/v1/photos/" + encodeURIComponent(id));
    },

    deleteAlbum: function (album) {
      return this.request("DELETE", "/api/v1/albums/" + encodeURIComponent(album));
    },

    logs: function (limit) {
      return this.request("GET", "/api/v1/logs?limit=" + encodeURIComponent(limit || 200));
    },

    clearLogs: function () {
      return this.request("DELETE", "/api/v1/logs");
    },
  };

  global.AvesApi = api;
})(window);
