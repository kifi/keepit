// @require styles/keeper/send_chooser.css
// @require scripts/html/keeper/send_chooser.js
// @require scripts/render.js

k.sendChooser = k.sendChooser || (function () {

  var KEY_PREFIX = MOD_KEYS.c + '-';

  // send chooser API
  return function sendChooser($sendChooserParent, onChange) {
    var self = {
      parent: $sendChooserParent,
      onChange: onChange,
      enterToSend: true,
      updateKeyTip: function (value) {
        if (value != null) {
          this.parent.find('.kifi-send-chooser-tip').attr('data-prefix', value ? '' : KEY_PREFIX);
          this.parent.find('.kifi-send-chooser-tip-alt').attr('data-prefix', value ? KEY_PREFIX : '');
        }
      },
      reflectPrefs: function (prefs) {
        this.enterToSend = prefs.enterToSend;
        this.updateKeyTip(this.enterToSend);
        if (onChange) {
          onChange();
        }
      }
    };

    api.port.emit('prefs', function (prefs) {
      self.enterToSend = prefs.enterToSend;
      self.updateKeyTip(self.enterToSend);
    });

    $sendChooserParent.append($(k.render('html/keeper/send_chooser')));

    function renderMenuItem($tip) {
      var $alt = $(
        '<span class="kifi-send-chooser-tip-alt" data-prefix="' + (self.enterToSend ? KEY_PREFIX : '') + '">' +
        $tip[0].firstChild.textContent +
        '</span>'
      )
      .css({
        'min-width': $tip.outerWidth(),
        'visibility': 'hidden'
      })
      .hover(function () {
        this.classList.add('kifi-hover');
      }, function () {
        this.classList.remove('kifi-hover');
      });

      return $alt;
    }

    $sendChooserParent.on('mousedown', '.kifi-send-chooser-tip', function (e) {
      if (e.originalEvent.isTrusted === false) {
        return;
      }
      e.preventDefault();

      var $tip = $(this);
      var $alt = renderMenuItem($tip);

      var $menu = $('<span class="kifi-send-chooser-tip-menu"/>').append($alt).insertAfter($tip);
      $tip.css('min-width', $alt.outerWidth()).addClass('kifi-active');
      $alt.css('visibility', '').mouseup(hide.bind(null, true));
      document.addEventListener('mousedown', docMouseDown, true);
      function docMouseDown(e) {
        hide($alt[0].contains(e.target));
        if ($tip[0].contains(e.target)) {
          e.stopPropagation();
        }
        e.preventDefault();
      }
      function hide(toggle) {
        document.removeEventListener('mousedown', docMouseDown, true);
        $tip.removeClass('kifi-active');
        $menu.remove();
        if (toggle) {
          self.enterToSend = !self.enterToSend;
          log('[enterToSend]', self.enterToSend);
          self.updateKeyTip(self.enterToSend);
          if (onChange) {
            onChange();
          }
          api.port.emit('set_enter_to_send', self.enterToSend);
        }
      }
    });

    return self;
  }
}());
