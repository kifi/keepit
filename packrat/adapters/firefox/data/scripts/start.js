if (document.readyState === "loading") {
  self.port.emit("api:start");
}

addEventListener("load", function() {
  self.port.emit("api:complete");
});

addEventListener("hashchange", function() {
  self.port.emit("api:nav");
});
