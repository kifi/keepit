// @require styles/insulate.css
// @require styles/login_dialog.css
// @require styles/view_email_dialog.css
// @require scripts/lib/jquery.js
// @require scripts/api_iframe.js
// @require scripts/render.js

var iframeDialog = function () {
  'use strict';
  var configs = {
    login: {
      height: 372,
      width: 660,
      templatePath: 'html/login_dialog',
      styles: ['styles/iframes/login.css'],
      scripts: ['scripts/iframes/login.js', 'scripts/html/iframes/login.js', 'scripts/iframes/lib/jquery.js'],
      onMessage: onLoginMessage
    },
    viewEmail: {
      height: 660,
      width: 660,
      templatePath: 'html/view_email_dialog',
      styles: ['styles/iframes/view_email.css'],
      scripts: ['scripts/iframes/view_email.js', 'scripts/html/iframes/view_email.js']
    }
  };
  var $dialog;

  api.onEnd.push(function() {
    if ($dialog) {
      remove($dialog);
    }
  });

  return {
    toggle: function (name, origin, data) {
      if ($dialog) {
        hide();
        if (name && name !== $dialog.data('name')) {
          show(name, origin, data);
        }
      } else {
        show(name, origin, data);
      }
    }
  };

  function show(name, origin, data) {
    if (name === 'login') {
      $('.kifi-signed-out-tooltip').not(':animated').stop().fadeOut(function () { $(this).remove() });
    }
    var config = configs[name];
    if (config) {
      buildAndShow(config, origin, data, name)
    }
  }

  function buildAndShow(config, origin, data, name) {
    api.require('scripts/' + config.templatePath + '.js', function () {
      $dialog = $(k.render(config.templatePath, {
        iframeSrc: origin + '/blank.html#' + Object.keys(data).reduce(function (f, k) {return (f ? f + '&' : '') + k + '=' + data[k]}, '')
      }));
      $dialog.find('.kifi-dialog-box').css({
        height: config.height,
        width: config.width,
        margin: (-.2 * config.height) + 'px 0 0 ' + (-.5 * config.width) + 'px'});

      $dialog.find('iframe').one('load', function () {
        api.pwnIframe(this, config.styles, config.scripts);
      });

      $dialog.appendTo('body').each(function () {this.clientHeight}).addClass('kifi-show')
      .on('click', function (e) {
        var $t = $(e.target);
        if ($t.hasClass('kifi-dialog-x') || !$t.closest('.kifi-dialog-box').length) {
          hide();
        }
        return false;
      });
      $dialog.data({name: name, origin: origin});
      document.addEventListener('keydown', onKeyDown, true);
      window.addEventListener('message', config.onMessage);
    });
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
    if ($dialog && e.origin === $dialog.data('origin')) {
      if (e.data.path) {
        api.port.emit('open_tab', {path: e.data.path});
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
