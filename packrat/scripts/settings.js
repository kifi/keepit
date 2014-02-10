// @require styles/keeper/settings.css
// @require scripts/lib/antiscroll.min.js
// @require scripts/prevent_ancestor_scroll.js

panes.settings = function () {
  'use strict';
  var handlers = {settings: update};
  var box;
  return {
    render: function ($paneBox) {
      log('[panes.settings.render]')();
      api.port.on(handlers);
      api.port.emit('settings', update);

      box = $paneBox[0];
      $paneBox.find('.kifi-scroll-inner').preventAncestorScroll();

      $paneBox.antiscroll({x: false});
      var scroller = $paneBox.data('antiscroll');
      $(window).on('resize.settings', scroller.refresh.bind(scroller));

      $paneBox
        .on('click', '.kifi-setting-checkbox', onClickCheckbox)
        .on('change', 'select[name=kifi-max-results]', onChangeMaxResults)
        .on('click', '.kifi-play-alert', onClickPlay)
        .on('click', '.kifi-settings-x', onClickX)
        .on('kifi:remove', onRemoved);
      if ($paneBox.data('shown')) {
        takeFocus();
      } else {
        $paneBox.on('kifi:shown', takeFocus);
      }
    }};

  function update(o) {
    if (box) {
      box.querySelector('input[name=kifi-sounds]').checked = o.sounds;
      box.querySelector('input[name=kifi-popups]').checked = o.popups;
      box.querySelector('input[name=kifi-emails]').checked = o.emails;
      box.querySelector('input[name=kifi-keeper]').checked = o.keeper;
      box.querySelector('input[name=kifi-sensitive]').checked = o.sensitive;
      box.querySelector('select[name=kifi-max-results]').value = o.maxResults;
      box.querySelector('.kifi-setting-sensitive').style.cssText = o.keeper ? 'display:block;height:auto' : 'display:none';
    }
  }

  function takeFocus() {
    box.querySelector('.kifi-setting-checkbox').focus();
  }

  function onRemoved() {
    if (box === this) {
      box = null;
      $(window).off('resize.settings');
      api.port.off(handlers);
    }
  }

  function onClickCheckbox() {
    var $status = showSpinner(this);
    var name = this.name.substr(5); // 'kifi-'
    var value = this.checked;
    api.port.emit('save_setting', {name: name, value: value}, function () {
      succeed($status, name, value);
    });
  }

  function onChangeMaxResults() {
    var $status = showSpinner(this);
    api.port.emit('set_max_results', Math.max(1, Math.min(3, Math.round(this.value) || 1)), function () {
      succeed($status);
    });
  }

  function onClickPlay() {
    api.port.emit('play_alert');
  }

  function onClickX() {
    pane.back('/messages:all');
  }

  function showSpinner(el) {
    var $label = $(el).closest('label');
    var $status = $label.next('.kifi-setting-status');
    if (!$status.length) {
      $status = $('<span class="kifi-setting-status"/>').insertAfter($label);
    }
    $status.stop(true).css({
      'background-image': 'url(' + api.url('images/spinner_32.gif') + ')',
      'opacity': 1
    }).data('began', Date.now());
    return $status;
  }

  function succeed($status, name, value) {
    setTimeout(function () {
      $status.css('background-image', '').delay(800).animate({opacity: 0}, 200);
      if (name === 'keeper') {
        $(box).find('.kifi-setting-sensitive').stop(true)[value ? 'slideDown' : 'slideUp'](240, function () {
          var scroller = $(box).data('antiscroll');
          if (scroller) scroller.refresh();
        });
      }
    }, Math.max(0, $status.data('began') + 200 - Date.now()));
  }
}();
