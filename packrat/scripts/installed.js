// @match /^https?:\/\/(dev\.ezkeep\.com:9000|www\.kifi\.com)\/.*$/
// @require scripts/api.js
// @asap

(function (v) {
  document.documentElement.dataset.kifiExt = v;
  document.dispatchEvent(new CustomEvent('kifi:installed', {version: v}));
}(this.chrome && chrome.runtime && chrome.runtime.getManifest().version ||
  this.self && self.options && self.options.version ||
  true));
