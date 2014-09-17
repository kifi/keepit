// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/keep_box.js
// @require scripts/html/keeper/keep_box_keep.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/html/keeper/keep_box_libs.js
// @require scripts/render.js
// @require scripts/listen.js

var keepBox = keepBox || (function () {
  'use strict';
  var $box;

  if (!Array.prototype.find) {
    Array.prototype.find = function (predicate, thisArg) {
      for (var i = 0, n = this.length, val; i < n; i++) {
        if (predicate.call(thisArg, (val = this[i]), i, this)) {
          return val;
        }
      }
    };
  }

  return {
    show: function ($parent, howKept, keepPage, unkeepPage) {
      log('[keepBox.show]');
      if ($box) {
        hide();
      }
      api.port.emit('keeps_and_libraries', function (data) {
        show($parent, data, howKept, keepPage, unkeepPage);
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

  function show($parent, data, howKept, keepPage, unkeepPage) {
    log('[keepBox:show]');
    var librariesById = data.libraries.reduce(indexById, {});
    var partitionedLibs = partitionLibs(data.libraries, data.keeps);
    $box = $(render('html/keeper/keep_box', {
      inLibs: partitionedLibs[0],
      recentLibs: partitionedLibs[1],
      otherLibs: partitionedLibs[2],
      link: true
    }, {
      view: 'keep_box_libs',
      keep_box_lib: 'keep_box_lib'
    }))
    .on('click mousedown', '.kifi-keep-box-x', function (e) {
      if (e.which === 1 && $box) {
        hide(e, 'x');
      }
    })
    .on('click', '.kifi-keep-box-lib[href]', function (e) {
      if (e.which === 1) {
        swipeTo($(render('html/keeper/keep_box_keep', {
          library: librariesById[this.dataset.id],
          title: document.title,
          site: document.location.hostname
        }, {
          keep_box_lib: 'keep_box_lib'
        })));
      }
    })
    .on('click', '.kifi-keep-box-lib-remove', function (e) {
      if (e.which === 1) {
        unkeepPage($(this).prev().data('id'));
        hide(e, 'action');
      }
    })
    .on('click', '.kifi-keep-box-save', function (e) {
      keepPage($(this).prevAll('.kifi-keep-box-lib').data('id'));
      hide(e, 'action');
    })
    .appendTo($parent);

    $(document).data('esc').add(hide);

    // api.port.on(handlers);

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
    // api.port.off(handlers);
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

  function swipeTo($new, left) {
    var $cart = $box.find('.kifi-keep-box-cart').addClass(left ? 'kifi-back' : 'kifi-forward');
    var $old = $cart.find('.kifi-keep-box-view');
    $new[left ? 'prependTo' : 'appendTo']($cart).layout();
    $cart.addClass('kifi-animated').layout().addClass('kifi-roll')
    .on('transitionend', function end(e) {
      if (e.target === this) {
        if (!left) $cart.removeClass('kifi-animated kifi-back kifi-forward');
        $old.remove();
        $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward')
          .off('transitionend', end);
      }
    });
  }

  function partitionLibs(libs, keeps) {
    var inLibs = [];
    var recentLibs = [];
    var otherLibs = [];
    for (var i = 0; i < libs.length; i++) {
      var lib = libs[i];
      var keep = keeps.find(libraryIdIs(lib.id));
      if (keep) {
        lib.removable = keep.removable;
        inLibs.push(lib);
      } else {
        otherLibs.push(lib);
      }
    }
    return [inLibs, recentLibs, otherLibs];
  }

  function indexById(o, item) {
    o[item.id] = item;
    return o;
  }

  function libraryIdIs(id) {
    return function (o) {return o.libraryId === id};
  }
}());
