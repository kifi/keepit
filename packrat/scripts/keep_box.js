// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/keep_box.js
// @require scripts/html/keeper/keep_box_keep.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/html/keeper/keep_box_libs.js
// @require scripts/html/keeper/keep_box_libs_list.js
// @require scripts/html/keeper/keep_box_new_lib.js
// @require scripts/lib/antiscroll.min.js
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
      $box.off('transitionend', onShown)
      var $libs = $box.find('.kifi-keep-box-libs');
      $libs.antiscroll({x: false});
      var scroller = $libs.data('antiscroll');
      $(window).off('resize.keepBox').on('resize.keepBox', scroller.refresh.bind(scroller));
      $box.find('input').focus();
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

  function hideAfter(ms) {
    clearTimeout(hideTimeout);
    hideTimeout = setTimeout(hide.bind(null, null, 'action'), ms);
  }

  function onHidden(trigger, e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[keepBox:onHidden]');
      $(this).remove();
      keepBox.onHidden.dispatch(trigger);
    }
  }

  function swipeTo($new, replace) {
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
          if (replace) {
            $new.data('$prev', $old.data('$prev'));
            $old.remove();
          } else {
            $new.data('$prev', $old);
            $old.detach();
          }
        }
        $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward')
          .off('transitionend', end);
        ($new.find('textarea')[0] || $new.find('input')[0]).focus();
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
    $view
    .on('input', '.kifi-keep-box-lib-input', function (e) {
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

    $view.find('.kifi-scroll-inner')
    .on('mousewheel', function (e) {
      var dY = e.originalEvent.deltaY;
      var sT = this.scrollTop;
      if (dY > 0 && sT + this.clientHeight < this.scrollHeight ||
          dY < 0 && sT > 0) {
        e.originalEvent.didScroll = true; // crbug.com/151734
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
    .on('click', '.kifi-keep-box-delete[href]', function (e) {
      if (e.which === 1) {
        deleteKeep($view, $(this));
      }
    })
    .on('click', '.kifi-keep-box-btn[href]', function (e) {
      if (e.which === 1) {
        if ($view.data('dirty').length) {
          saveKeep($view, $(this));
        } else {
          hide(null, 'action');
        }
      }
    });
  }

  function addCreateLibraryBindings($view) {
    $view
    .on('click', '.kifi-keep-box-back', function (e) {
      if (e.which === 1) {
        swipeTo();
      }
    })
    .on('click', '.kifi-keep-box-new-lib-secret', function (e) {
      this.parentNode.classList.toggle('kifi-checked', this.checked);
    })
    .on('keydown', '.kifi-keep-box-new-lib-name', function (e) {
      if (e.keyCode === 13) {
        createLibrary($view, $view.find('.kifi-keep-box-create'));
      }
    })
    .on('click', '.kifi-keep-box-create[href]', function (e) {
      if (e.which === 1) {
        createLibrary($view, $(this));
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
      showCreateLibrary();
    }
  }

  function showCreateLibrary() {
    var $view = $(render('html/keeper/keep_box_new_lib'));
    addCreateLibraryBindings($view);
    swipeTo($view);
  }

  function showKeep(library, keep, replace) {
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
    swipeTo($view, replace);
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

  function saveKeep($view, $btn) {
    var libraryId = $view.find('.kifi-keep-box-lib').data('id');
    var $title = $view.find('.kifi-keep-box-keep-title');
    var $comment = $view.find('.kifi-keep-box-keep-comment');
    var $progress = $btn.find('.kifi-keep-box-progress');
    var title = $title.val().trim();
    if (title) {
      $title.add($comment).prop('disabled', true);
      $btn.removeAttr('href').addClass('kifi-doing');
      if ($view.hasClass('kifi-kept')) {
        api.port.emit('save_keep', {libraryId: libraryId, updates: {title: title}}, function (success) {
          endProgress($progress, 'save', success);
          if (success) {
            hideAfter(1000);
          }
        });
      } else {
        $box.data('keepPage')({libraryId: libraryId, title: title}, function (success) {
          endProgress($progress, 'keep', success);
          if (success) {
            $btn.closest('.kifi-keep-box-view').addClass('kifi-kept');
            hideAfter(1000);
          }
        });
      }
      updateProgress.call($progress[0], 0);
    } else {
      $title.focus().select(); // TODO: restore saved title or suggest our best-guess title
    }
  }

  function deleteKeep($view, $btn) {
    var libraryId = $view.find('.kifi-keep-box-lib').data('id');
    var $progress = $btn.find('.kifi-keep-box-progress');
    $btn.removeAttr('href').addClass('kifi-doing');
    $box.data('unkeepPage')(libraryId, function (success) {
      endProgress($progress, 'unkeep', success);
      if (success) {
        hideAfter(1000);
      }
    });
    updateProgress.call($progress[0], 0);
  }

  function createLibrary($view, $btn) {
    var $name = $view.find('.kifi-keep-box-new-lib-name');
    var $secret = $view.find('.kifi-keep-box-new-lib-secret');
    var $progress = $btn.find('.kifi-keep-box-progress');
    var name = $name.val().trim();
    if (name) {
      $name.prop('disabled', true);
      $btn.removeAttr('href').addClass('kifi-doing');
      api.port.emit('create_library', {
        name: name,
        visibility: $secret.prop('checked') ? 'secret' : 'discoverable'
      }, function (library) {
        endProgress($progress, 'create', !!library);
        if (library) {
          var $old = $view.data('$prev');
          $old.data('librariesById')[library.id] = library;
          $old.find('.kifi-keep-box-lib.kifi-create').before(render('html/keeper/keep_box_lib', library));
          setTimeout(showKeep.bind(null, library, null, true), 200);
        }
      });
      updateProgress.call($progress[0], 0);
    } else {
      $name.focus().select();
    }
  }

  var progressTimeout;
  function updateProgress(frac) {
    log('[updateProgress]', frac);
    this.style.width = Math.min(frac * 100, 100) + '%';
    var fracLeft = .9 - frac;
    if (fracLeft > .0001) {
      progressTimeout = setTimeout(updateProgress.bind(this, frac + .06 * fracLeft), 10);
    }
  }

  function endProgress($progress, desc, success) {
    log('[endProgress]', desc, success ? 'success' : 'error');
    clearTimeout(progressTimeout), progressTimeout = null;
    var $btn = $progress.parent().removeClass('kifi-doing');
    if (success) {
      $btn.addClass('kifi-done');
    } else {
      $btn.prop('href', 'javascript:').one('transitionend', function () {
        $progress.css('width', 0);
        $btn.removeClass('kifi-fail');
      }).addClass('kifi-fail');
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
