api.pwnIframe = function (iframe, styles, scripts) {
  var doc = iframe.contentWindow.document;
  doc.head.innerHTML = styles.map(function (path) {return '<link rel="stylesheet" href="' + api.url(path) + '">'}).join('');
  scripts.forEach(function (path) {
    var s = doc.createElement('SCRIPT');
    s.dataset.loading = true;
    s.addEventListener('load', onLoad);
    s.src = api.url(path);
    doc.head.appendChild(s);
  });
  function onLoad() {
    this.dataset.loading = false;
  }
};
