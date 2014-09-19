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

  var hideTimeout;
  function hide(e, trigger) {
    clearTimeout(hideTimeout), hideTimeout = null;
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
        ($new.find('textarea')[0] || $new.find('input')).focus();
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
        lib.keep = keep;
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
            chooseLibrary($view, $item[0]);
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
        chooseLibrary($view, this);
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
    .on('input', '.kifi-keep-box-keep-title', function () {
      updateDirty($view, this, this.value.trim() !== $view.data('title'));
    })
    .on('click', '.kifi-keep-box-btn', function (e) {
      if (e.which === 1) {
        if ($view.data('dirty').length) {
          saveKeep($view);
        } else {
          hide(null, 'action');
        }
      }
    });
  }

  function showLibs($new) {
    highlightLibrary($new.find('.kifi-keep-box-lib')[0]);
    $box.find('.kifi-keep-box-libs').replaceWith($new);
  }

  function highlightLibrary(el) {
    $(el).closest('.kifi-keep-box-libs').find('.kifi-highlighted').removeClass('kifi-highlighted');
    el.classList.add('kifi-highlighted');
  }

  function chooseLibrary($view, el) {
    var libraryId = el.dataset.id;
    if (libraryId) {
      var library = $view.data('librariesById')[libraryId];
      var kept = el.classList.contains('kifi-kept');
      if (kept) {
        api.port.emit('get_keep', libraryId, showKeep.bind(null, library));
      } else {
        showKeep(library);
      }
    } else {
      // TODO: create library
    }
  }

  function showKeep(library, keep) {
    var title = keep ? keep.title : authoredTitle();
    var $view = $(render('html/keeper/keep_box_keep', {
      library: library,
      static: true,
      kept: !!keep,
      title: title,
      site: document.location.hostname
    }, {
      keep_box_lib: 'keep_box_lib'
    }));
    $view.data({
      title: keep ? title : null,
      dirty: keep ? [] : $view.find('.kifi-keep-box-keep-title').get()
    });
    addKeepBindings($view);
    swipeTo($view);
  }

  function updateDirty($view, el, dirty) {
    var dirtyEls = $view.data('dirty');
    var i = dirtyEls.indexOf(el);
    if (dirty && i < 0) {
      dirtyEls.push(el);
      if (dirtyEls.length === 1) {
        $view.addClass('kifi-dirty');
      }
    } else if (!dirty && i >= 0) {
      dirtyEls.splice(i, 1);
      if (dirtyEls.length === 0) {
        $view.removeClass('kifi-dirty');
      }
    }
  }

  function saveKeep($view) {
    var libraryId = $view.find('.kifi-keep-box-lib').data('id');
    var $title = $view.find('.kifi-keep-box-keep-title');
    var $comment = $view.find('.kifi-keep-box-keep-comment');
    var $btn = $view.find('.kifi-keep-box-btn');
    var title = $title.val().trim();
    if (title) {
      $title.add($comment).prop('disabled', true);
      $btn.addClass('kifi-doing');
      if ($view.hasClass('kifi-kept')) {
        api.port.emit('save_keep', {libraryId: libraryId, updates: {title: title}}, done.bind(null, true));
      } else {
        $box.data('keepPage')({libraryId: libraryId, title: title}, done.bind(null, false));
      }
      $btn.removeAttr('href');
      var $progress = $btn.find('.kifi-keep-box-progress');
      updateSaveProgress.call($progress[0], 0);
    } else {
      $title.focus().select(); // TODO: restore saved title or suggest our best-guess title
    }

    function done(edit, success) {
      log('[handleSaveKeepResult]', edit ? 'edit' : 'keep', success ? 'success' : 'error');
      clearTimeout(saveProgressTimeout), saveProgressTimeout = null;
      $btn.removeClass('kifi-doing');
      if (success) {
        $btn.addClass('kifi-done');
        $view.addClass('kifi-kept');
        clearTimeout(hideTimeout);
        hideTimeout = setTimeout(hide.bind(null, null, 'action'), 1000);
      } else {
        $btn.prop('href', 'javascript:').one('transitionend', function () {
          $progress.css('width', 0);
          $btn.removeClass('kifi-fail');
        }).addClass('kifi-fail');
      }
    }
  }

  var saveProgressTimeout;
  function updateSaveProgress(frac) {
    log('[updateSaveProgress]', frac);
    this.style.width = Math.min(frac * 100, 100) + '%';
    var fracLeft = .9 - frac;
    if (fracLeft > .0001) {
      saveProgressTimeout = setTimeout(updateSaveProgress.bind(this, frac + .06 * fracLeft), 10);
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
