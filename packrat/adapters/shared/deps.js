var meta = meta || require("./meta");

(this.exports || this).deps = function(path, injected) {
  injected = injected || {};
  function notYetInjected(path) {
    return !injected[path];
  }

  var styles = meta.styleDeps[path];
  var scripts = meta.scriptDeps[path];
  return {
    styles: styles ? styles.filter(notYetInjected) : [],
    scripts: scripts ? scripts.concat([path]).filter(notYetInjected) : []};
};
