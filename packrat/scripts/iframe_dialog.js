// @require styles/insulate.css
// @require styles/iframe_dialog.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/api_iframe.js
// @require scripts/render.js
// @require scripts/html/iframe_dialog.js

var iframeDialog = function () {
  'use strict';
  var iframeOrigin = 'https://www.kifi.com';
  var configs = {
    login: {
      height: 372,
      width: 660,
      templatePath: 'html/iframe_dialog',
      styles: ['styles/iframes/login.css'],
      scripts: ['scripts/iframes/login.js', 'scripts/html/iframes/login.js', 'scripts/iframes/lib/jquery.js'],
      onMessage: onLoginMessage
    }
  };
  var $dialog;

  api.onEnd.push(function() {
    if ($dialog) {
      remove($dialog);
    }
  });

  return {
    origin: function (origin) {
      iframeOrigin = origin;
      return this;
    },
    toggle: function (name, data) {
      if ($dialog) {
        hide();
        if (name && name !== $dialog.data('name')) {
          show(name, data);
        }
      } else {
        show(name, data);
      }
    }
  };

  function show(name, data) {
    var config = configs[name];
    if (config) {
      $dialog = buildAndShow(config, data);
      $dialog.data('name', name);
      document.addEventListener('keydown', onKeyDown, true);
      window.addEventListener('message', config.onMessage);
    }
  }

  function buildAndShow(config, data) {
    var $d = $(render(config.templatePath, {
      logo: api.url('images/kifi_logo.png'),
      iframeSrc: iframeOrigin + '/blank.html#' + Object.keys(data).reduce(function (f, k) {return (f ? f + '&' : '') + k + '=' + data[k]}, '')
    }));
    $d.find('.kifi-dialog-box').css({
      height: config.height,
      width: config.width,
      margin: (-.2 * config.height) + 'px 0 0 ' + (-.5 * config.width) + 'px'});

    $d.find('iframe').one('load', function () {
      api.pwnIframe(this, config.styles, config.scripts);
    });

    $d.appendTo('body').each(function () {this.clientHeight}).addClass('kifi-show')
    .on('click', function (e) {
      var $t = $(e.target);
      if ($t.hasClass('kifi-dialog-x') || !$t.closest('.kifi-dialog-box').length) {
        hide();
      }
      return false;
    });
    return $d;
  }

  function hide() {
    if ($dialog && !$dialog.data('hiding')) {
      $dialog.data('hiding', true).removeClass('kifi-show');
      setTimeout(remove.bind(null, $dialog), 320);
    }
  }

  function remove($d) {
    var name = $d.data('name');
    $d.remove();
    if ($dialog === $d) {
      $dialog = null;
      document.removeEventListener('keydown', onKeyDown, true);
      window.removeEventListener('message', configs[name].onMessage);
    }
  }

  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey && $dialog) {  // Esc
      hide();
      return false;
    }
  }

  function onLoginMessage(e) {
    if (e.origin === iframeOrigin) {
      if (e.data.path) {
        api.port.emit('open_tab', e.data.path);
        hide();
      } else if (e.data.authenticated) {
        api.port.emit('logged_in');
        hide();
      } else if (e.data.close) {
        hide();
      }
    }
  }
}();
