api.pwnIframe = function (iframe, styles, scripts) {
  var doc = iframe.contentWindow.document;
  var head = doc.head;
  styles.forEach(function (path) {
    var link = doc.createElement('LINK');
    link.setAttribute('rel', 'stylesheet');
    link.setAttribute('href', api.url(path));
    head.appendChild(link);
  });
  scripts.forEach(function (path) {
    var s = $('<script>')[0];
    s.dataset.loading = true;
    s.addEventListener('load', onLoad);
    s.src = api.url(path);
    doc.head.appendChild(s);
  });
  function onLoad() {
    this.dataset.loading = false;
  }
};
