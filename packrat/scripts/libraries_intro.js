// @require styles/keeper/libraries_intro.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/libraries_intro_1.js
// @require scripts/html/keeper/libraries_intro_2.js

(function () {
  log('[libraries_intro]');
  var $2, $1 = k.keepBox.appendTip(k.render('html/keeper/libraries_intro_1', k.me))
    .layout().addClass('kifi-showing')
    .on('click', '.kifi-li-close', hide);
  api.port.on({hide_library_intro: hide});
  api.onEnd.push(hide);
  api.port.emit('track_ftue', 'l');

  $1.parent().find('.kifi-keep-box-cart').each(function (i, cart) {
    var observer = new MutationObserver(function (records) {
      if ($1) {
        $1.on('transitionend', removeThis).removeClass('kifi-showing');
        $1 = null;
      }
      var view = elementAdded(records, 'kifi-keep-box-view-keep');
      if (view) {
        observer.disconnect();
        observer = null;
        $(cart).on('transitionend', $.proxy(onKeepView, null, view));
      }
    });
    observer.observe(cart, {childList: true});
  });

  function onKeepView(view) {
    log('[libraries_intro:onKeepView]')
    $(this).off('transitionend', onKeepView);
    $(view).on('kifi-hide', hide);
    $2 = $2 || k.keepBox.appendTip(k.render('html/keeper/libraries_intro_2'))
      .layout().addClass('kifi-showing')
      .on('click', '.kifi-li-close', hide);
  }

  function hide(e) {
    log('[libraries_intro:hide]')
    if ($2) {
      api.port.emit('terminate_ftue', {type: 'l', action: e & e.type === 'click' ? 'closed' : undefined});
    }
    $().add($1).add($2).on('transitionend', removeThis).removeClass('kifi-showing');
    $1 = $2 = null;
    if (e && e.preventDefault) {
      e.preventDefault();
    }
  }

  function elementAdded(records, cssClass) {
    for (var i = 0; i < records.length; i++) {
      var nodes = records[i].addedNodes;
      for (var j = 0; j < nodes.length; j++) {
        var node = nodes[j];
        if (node.nodeType === 1 && node.classList.contains(cssClass)) {
          return node;
        }
      }
    }
  }

  function removeThis() {
    $(this).remove();
  }
}());
