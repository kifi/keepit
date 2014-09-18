// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/keep_box.js
// @require scripts/html/keeper/keep_box_keep.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/html/keeper/keep_box_libs.js
// @require scripts/html/keeper/keep_box_libs_list.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/listen.js

var keepBox = keepBox || (function () {
  'use strict';
  var $box;

  if (!Array.prototype.find) {
    Object.defineProperty(Array.prototype, 'find', {
      value: function (predicate, thisArg) {
        for (var i = 0, n = this.length, val; i < n; i++) {
          if (predicate.call(thisArg, (val = this[i]), i, this)) {
            return val;
          }
        }
      }
    });
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
    var partitionedLibs = partitionLibs(data.libraries, data.keeps);
    $box = $(render('html/keeper/keep_box', {
      inLibs: partitionedLibs[0],
      recentLibs: partitionedLibs[1],
      otherLibs: partitionedLibs[2]
    }, {
      view: 'keep_box_libs',
      keep_box_lib: 'keep_box_lib',
      keep_box_libs_list: 'keep_box_libs_list'
    }))
    .on('click mousedown', '.kifi-keep-box-x', function (e) {
      if (e.which === 1 && $box) {
        hide(e, 'x');
      }
    })
    .appendTo($parent);
    var $view = $box.find('.kifi-keep-box-view-libs');
    $view.data({
      librariesById: data.libraries.reduce(indexById, {}),
      $all: $view.find('.kifi-keep-box-libs')
    });
    addLibrariesBindings($view);

    $(document).data('esc').add(hide);

    // api.port.on(handlers);

    $box.layout()
    .on('transitionend', onShown)
    .removeClass('kifi-down')
    .data({keepPage: keepPage, unkeepPage: unkeepPage});
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

  function swipeTo($new) {
    var $cart = $box.find('.kifi-keep-box-cart');
    var $old = $cart.find('.kifi-keep-box-view');
    var back = !$new;
    $new = $new || $old.data('$prev');
    $cart.addClass(back ? 'kifi-back' : 'kifi-forward');
    $new[back ? 'prependTo' : 'appendTo']($cart).layout();
    $cart.addClass('kifi-animated').layout().addClass('kifi-roll')
    .on('transitionend', function end(e) {
      if (e.target === this) {
        if (back) {
          $old.remove();
        } else {
          $cart.removeClass('kifi-animated kifi-back kifi-forward');
          $old.detach();
          $new.data('$prev', $old);
        }
        $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward')
          .off('transitionend', end);
        setTimeout(function () {
          $new.find('input,textarea').focus();
        });
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

  function addLibrariesBindings($view) {
    $view.on('input', '.kifi-keep-box-lib-input', function (e) {
      var q = this.value.trim();
      var data = $.data(this);
      if (data.q !== q) {
        data.q = q;
        if (q) {
          api.port.emit('filter_libraries', q, function (libs) {
            if (data.q === q) {
              showLibs($(render('html/keeper/keep_box_libs_list', {filtering: true, libs: libs.map(addNameHtml)}, {
                keep_box_lib: 'keep_box_lib'
              })));
            }
          });
        } else {
          var $all = $view.data('$all');
          if (!$box[0].contains($all[0])) {
            showLibs($all);
          }
        }
      }
    })
    .on('keydown', '.kifi-keep-box-lib-input', function (e) {
      switch (e.keyCode) {
        case 38: // up
        case 40: // down
          var up = e.keyCode === 38;
          var $item = $view.find('.kifi-highlighted');
          $item = $item.length ?
            $item[up ? 'prev' : 'next']().find('.kifi-keep-box-lib').addBack('.kifi-keep-box-lib') :
            $view.find('.kifi-keep-box-lib')[up ? 'last' : 'first']();
          if ($item.length) {
            highlightLibrary($item[0]);
          }
          return false;
        case 13: // enter
        case 108: // numpad enter
          var $item = $view.find('.kifi-highlighted');
          if ($item.length) {
            chooseLibrary($item[0]);
          }
          return false;
      }
    })
    .on('mouseover', '.kifi-keep-box-lib', function () {
      if ($view.data('mouseMoved')) {  // FF immediately triggers mouseover on element inserted under mouse cursor
        highlightLibrary(this);
      }
    })
    .on('mousemove', '.kifi-keep-box-lib', $.proxy(function (data) {
      if (!data.mouseMoved) {
        data.mouseMoved = true;
        highlightLibrary(this);
      }
    }, null, $view.data()))
    .on('mousedown', '.kifi-keep-box-lib', function (e) {
      if (e.which === 1) {
        chooseLibrary(this);
      }
    })
    .on('click', '.kifi-keep-box-lib-remove', function (e) {
      if (e.which === 1) {
        $box.data('unkeepPage')($(this).prev().data('id'));
        hide(e, 'action');
      }
    });
  }

  function addKeepBindings($view) {
    $view
    .on('click', '.kifi-keep-box-back', function (e) {
      if (e.which === 1) {
        swipeTo();
      }
    })
    .on('click', '.kifi-keep-box-save', function (e) {
      if (e.which === 1) {
        $box.data('keepPage')($(this).prevAll('.kifi-keep-box-lib').data('id'));
        hide(e, 'action');
      }
    });
  }

  function showLibs($new) {
    highlightLibrary($new.find('.kifi-keep-box-lib')[0]);
    $box.find('.kifi-keep-box-libs').replaceWith($new);
  }

  function highlightLibrary(el) {
    $(el).addClass('kifi-highlighted').siblings('.kifi-highlighted').removeClass('kifi-highlighted');
  }

  function chooseLibrary(el) {
    var libraryId = el.dataset.id;
    if (libraryId) {
      var $view = $(render('html/keeper/keep_box_keep', {
        library: $(el).closest('.kifi-keep-box-view').data('librariesById')[libraryId],
        static: true,
        title: document.title,
        site: document.location.hostname
      }, {
        keep_box_lib: 'keep_box_lib'
      }));
      addKeepBindings($view);
      swipeTo($view);
    }
  }

  function addNameHtml(lib) {
    var parts = lib.nameParts;
    var html = [];
    for (var i = 0; i < parts.length; i++) {
      if (i % 2) {
        html.push('<b>', Mustache.escape(parts[i]), '</b>');
      } else {
        html.push(Mustache.escape(parts[i]));
      }
    }
    lib.nameHtml = html.join('');
    return lib;
  }

  function indexById(o, item) {
    o[item.id] = item;
    return o;
  }

  function libraryIdIs(id) {
    return function (o) {return o.libraryId === id};
  }
}());
