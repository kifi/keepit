// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/keep_box.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/render.js
// @require scripts/listen.js

var keepBox = (function () {
  'use strict';
  var $box;

  var handlers = {};

  return {
    show: function ($parent, howKept, keepPage, unkeepPage) {
      log('[keepBox.show]');
      if ($box) {
        hide();
      }
      api.port.emit('get_libraries', function (libs) {
        show($parent, libs, howKept, keepPage, unkeepPage);
      });
    },
    hide: function () {
      if ($box) {
        hide();
      } else {
        log('[keepBox:hide] no-op');
      }
    },
    onHide: new Listeners(),
    onHidden: new Listeners(),
    showing: function () {
      return !!$box;
    }
  };

  function show($parent, libraries, howKept, keepPage, unkeepPage) {
    log('[keepBox:show]');
    var partitionedLibs = partitionLibs(libraries, howKept);
    $box = $(render('html/keeper/keep_box', {
      inLibs: partitionedLibs[0],
      recentLibs: partitionedLibs[1],
      otherLibs: partitionedLibs[2]
    }, {
      keep_box_lib: 'keep_box_lib'
    }))
    .on('click mousedown', '.kifi-keep-box-x', function (e) {
      if (e.which === 1 && $box) {
        hide(e, 'x');
      }
    })
    .on('click', '.kifi-keep-box-lib', function (e) {
      if (e.which === 1) {
        keepPage(this.classList.contains('kifi-secret') ? 'private' : 'public');
        hide(e, 'action');
      }
    })
    .on('click', '.kifi-keep-box-lib-remove', function (e) {
      if (e.which === 1) {
        unkeepPage();
        hide(e, 'action');
      }
    })
    .appendTo($parent);

    $(document).data('esc').add(hide);

    api.port.on(handlers);

    $box.layout()
    .on('transitionend', onShown)
    .removeClass('kifi-down');
  }

  function onShown(e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[keepBox:onShown]');
      $(this).off('transitionend', onShown)
        .find('input').focus();
    }
  }

  function hide(e, trigger) {
    log('[keepBox:hide]');
    api.port.off(handlers);
    $(document).data('esc').remove(hide);
    $box.css('overflow', '')
      .on('transitionend', $.proxy(onHidden, null, trigger || (e && e.keyCode === 27 ? 'esc' : undefined)))
      .addClass('kifi-down');
    $box = null;
    if (e) e.preventDefault();
    keepBox.onHide.dispatch();
  }

  function onHidden(trigger, e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[keepBox:onHidden]');
      $(this).remove();
      keepBox.onHidden.dispatch(trigger);
    }
  }

  function partitionLibs(libs, howKept) {
    var inLibs = [];
    var recentLibs = [];
    var otherLibs = [];
    var inPathRe = howKept === 'public' ? /\/main$/ : howKept === 'private' ? /\/secret$/ : /^$/;
    for (var i = 0; i < libs.length; i++) {
      var lib = libs[i];
      if (inPathRe.test(lib.path)) {
        lib.removable = true;
        inLibs.push(lib);
      } else {
        otherLibs.push(lib);
      }
    }
    return [inLibs, recentLibs, otherLibs];
  }
}());
