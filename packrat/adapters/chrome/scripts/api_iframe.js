api.pwnIframe = function (iframe, styles, scripts) {
  api.port.emit('api:iframe', {url: iframe.src, styles: styles, scripts: scripts});
};
