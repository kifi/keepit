if (document.readyState === "loading") {
  self.port.emit("api:start");
}

addEventListener("hashchange", function() {
  self.port.emit("api:nav");
});
