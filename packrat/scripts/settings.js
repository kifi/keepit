// @require styles/keeper/settings.css
// @require scripts/lib/antiscroll.min.js
// @require scripts/prevent_ancestor_scroll.js

k.panes.settings = k.panes.settings || function () {
  'use strict';
  var handlers = {settings: update};
  var subordinates = {search: 'max-results'};
  var box;
  return {
    render: function ($paneBox) {
      log('[panes.settings.render]');
      api.port.on(handlers);
      api.port.emit('settings');

      box = $paneBox[0];
      $paneBox.find('.kifi-scroll-inner').scroll(onScroll).preventAncestorScroll();

      var $scroll = $paneBox.find('.kifi-settings-scroll').antiscroll({x: false});
      var scroller = $scroll.data('antiscroll');
      $(window).on('resize.settings', scroller.refresh.bind(scroller));

      $paneBox
        .on('click', '.kifi-setting-checkbox', onClickCheckbox)
        .on('change keyup', 'select[name=kifi-max-results]', onChangeMaxResults)
        .on('click', '.kifi-settings-play-alert', onClickPlay)
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
      updateCheckbox('kifi-sounds', o.sounds);
      updateCheckbox('kifi-popups', o.popups);
      updateCheckbox('kifi-emails', o.emails);
      updateCheckbox('kifi-keeper', o.keeper);
      updateCheckbox('kifi-search', o.search);
      var sel = box.querySelector('select[name=kifi-max-results]');
      sel.dataset.val = sel.value = o.maxResults;
      for (var key in subordinates) {
        box.querySelector('.kifi-setting-' + subordinates[key]).style.cssText = o[key] ? 'display:block;height:auto' : 'display:none';
      }
    }
  }

  function updateCheckbox(name, on) {
    box.querySelector('input[name=' + name + ']').checked = on;
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
    var val = this.checked;
    api.port.emit('save_setting', {name: name, value: val}, succeed.bind(null, $status, name, val));
  }

  function onChangeMaxResults(e) {
    var val = this.value;
    if (this.dataset.val !== val) { // bugzil.la/126379
      this.dataset.val = val;
      var $status = showSpinner(this);
      api.port.emit('set_max_results', +val, succeed.bind(null, $status));
    }
  }

  function onClickPlay() {
    api.port.emit('play_alert');
  }

  function onClickX() {
    k.pane.back();
  }

  function onScroll() {
    this.parentNode.classList.toggle('kifi-scrolled', this.scrollTop > 0);
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
      var sub = subordinates[name];
      if (sub) {
        $(box).find('.kifi-setting-' + sub).stop(true)[value ? 'slideDown' : 'slideUp'](240, function () {
          var scroller = $(box).data('antiscroll');
          if (scroller) scroller.refresh();
        });
      }
    }, Math.max(0, $status.data('began') + 200 - Date.now()));
  }
}();
