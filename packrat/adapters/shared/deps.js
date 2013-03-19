var meta = meta || require("./meta");

(this.exports || this).deps = function(path, injected) {
  injected = injected || {};
  function notYetInjected(path) {
    return !injected[path];
  }

  if (path.substr(-4) === ".css") {
    return {scripts: [], styles: injected[path] ? [] : [path]};
  }

  var styles = meta.styleDeps[path];
  var scripts = meta.scriptDeps[path];
  return {
    styles: styles ? styles.filter(notYetInjected) : [],
    scripts: scripts ? scripts.concat([path]).filter(notYetInjected) : [path]};
};
