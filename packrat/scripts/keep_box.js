// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/keep_box.js
// @require scripts/html/keeper/keep_box_keep.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/html/keeper/keep_box_libs.js
// @require scripts/html/keeper/keep_box_libs_list.js
// @require scripts/html/keeper/keep_box_new_lib.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/lib/mustache.js
// @require scripts/lib/q.min.js
// @require scripts/render.js
// @require scripts/listen.js
// @require scripts/title_from_url.js

var keepBox = keepBox || (function () {
  'use strict';

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

  var $box;
  var matches = ['matches', 'mozMatchesSelector', 'webkitMatchesSelector'].find(function (p) { return p in document.body; });
  var IMAGE_WIDTH = 300, IMAGE_HEIGHT = 240;  // size of kifi-keep-box-keep-image-picker

  return {
    show: function ($parent, data, howKept, keepPage, unkeepPage) {
      log('[keepBox.show]');
      if ($box) {
        hide();
      }
      show($parent, data, howKept, keepPage, unkeepPage);
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
        e.preventDefault();
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
      $box.off('transitionend', onShown);
      $box.find('input').first().focus();
      makeLibsScrollable($box.find('.kifi-keep-box-libs'));
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
          $new.data('$prev', $old);
          $old.detach();
        }
        $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward')
          .off('transitionend', end);
        var input = $new.find('textarea')[0] || $new.find('input')[0];
        input.focus();
        input.select();
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
  }

  function addKeepBindings($view) {
    $view
    .on('click', '.kifi-keep-box-back', function (e) {
      if (e.which === 1) {
        var $old = $view.data('$prev');
        if ($old.hasClass('kifi-keep-box-view-new-lib')) {
          var libraryId = $view.find('.kifi-keep-box-lib').data('id');
          api.port.emit('delete_library', libraryId, function (success) {
            if (success) {
              var $libView = $old.data('$prev');
              delete $libView.data('librariesById')[libraryId];
              $libView.data('$all').add($libView).find('.kifi-keep-box-lib[data-id=' + libraryId + ']').remove();
            }
          });
          $old.find('.kifi-keep-box-progress').css('width', '').parent().removeClass('kifi-done');
        }
        swipeTo();
      }
    })
    .on('input', '.kifi-keep-box-keep-title', function () {
      onKeepEdit($view, 'title', this.value.trim());
    })
    .on('click', '.kifi-keep-box-image-prev,.kifi-keep-box-image-next', function (e) {
      if (e.which === 1) {
        var i = swipeImage($view, this.classList.contains('kifi-keep-box-image-prev'));
        onKeepEdit($view, 'imageIdx', i);
      }
    })
    .on('click', '.kifi-keep-box-btn[href]', function (e) {
      if (e.which === 1) {
        if ($view.hasClass('kifi-dirty')) {
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
        $view.data('$prev').find('.kifi-keep-box-lib-input').removeData('q').trigger('input');
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
    makeLibsScrollable($new);
  }

  function makeLibsScrollable($libs) {
    $libs.children('.kifi-scrollbar').remove();
    $libs.antiscroll({x: false});
    $libs.children('.kifi-scroll-inner').on('mousewheel', onScrollInnerWheel);
  }

  function onScrollInnerWheel(e) {
    var dY = e.originalEvent.deltaY;
    var sT = this.scrollTop;
    if (dY > 0 && sT + this.clientHeight < this.scrollHeight ||
        dY < 0 && sT > 0) {
      e.originalEvent.didScroll = true; // crbug.com/151734
    }
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

  function showKeep(library, keep) {
    var title, images = findImages(), canvases = [], imageIdx;
    if (keep) {
      title = keep.title || formatTitleFromUrl(document.URL);
      if (keep.image) {
        var img = new Image;
        img.src = keep.image;
        images.unshift(img);
        canvases.unshift(newKeepCanvas(img));
        imageIdx = 0;
      } else {
        imageIdx = images.length;
      }
    } else {
      title = authoredTitle() || formatTitleFromUrl(document.URL);
      if (images.length) {
        canvases.unshift(newKeepCanvas(images[0]));
      }
      imageIdx = 0;
    }
    var $view = $(render('html/keeper/keep_box_keep', {
      library: library,
      static: true,
      kept: !!keep,
      dirty: !keep,
      title: title,
      site: document.location.hostname,
      hasImages: images.length > 0
    }, {
      keep_box_lib: 'keep_box_lib'
    }));
    $view.find('.kifi-keep-box-keep-image-cart').append(canvases[imageIdx] || newNoImage());
    $view.data({
      images: images,
      canvases: canvases,
      saved: {
        title: keep ? keep.title : null,
        imageIdx: keep && keep.image ? 0 : null,
      },
      shown: {
        title: title,
        imageIdx: imageIdx
      },
      dirty: !keep
    });
    addKeepBindings($view);
    swipeTo($view);
  }

  function findImages() {
    return Array.prototype.slice.call(document.getElementsByTagName('img')).filter(isSuitableImage);
  }

  function isSuitableImage(img) {
    return (
      img.naturalHeight >= 200 &&
      img.naturalWidth >= 200 &&
      img.offsetWidth > 0 &&
      img.offsetHeight > 0 &&
      img.complete &&
      img.src.lastIndexOf('http', 0) === 0 &&
      !img[matches]('.kifi-root *'));
  }

  function newKeepCanvas(img) {
    var cv = document.createElement('canvas');
    cv.className = 'kifi-keep-box-keep-image';
    var gc = cv.getContext('2d');
    var scale = (window.devicePixelRatio || 1) /  // html5rocks.com/en/tutorials/canvas/hidpi/
      (gc.webkitBackingStorePixelRatio ||
       gc.mozBackingStorePixelRatio ||
       gc.backingStorePixelRatio || 1);
    cv.width = IMAGE_WIDTH * scale;
    cv.height = IMAGE_HEIGHT * scale;
    if (scale !== 1) {
      cv.style.width = IMAGE_WIDTH + 'px';
      cv.style.height = IMAGE_HEIGHT + 'px';
      gc.scale(scale, scale);
    }
    if (img.complete) {
      fillWith(cv, img);
    } else {
      img.onload = fillWith.bind(null, cv, img);
    }
    return cv;
  }

  function fillWith(canvas, img) {
    var w = img.naturalWidth;
    var h = img.naturalHeight;
    var scale = Math.min(1, IMAGE_WIDTH / w, IMAGE_HEIGHT / h);
    var dw = w * scale;
    var dh = h * scale;
    var dx = (IMAGE_WIDTH - dw) / 2;
    var dy = (IMAGE_HEIGHT - dh) / 2;
    canvas.getContext('2d').drawImage(img, dx, dy, dw, dh);
  }

  function newNoImage() {
    return '<div class="kifi-keep-box-keep-image kifi-none"></div>';
  }

  function swipeImage($view, back) {
    var data = $view.data();
    var n = data.images.length;
    var i = data.shown.imageIdx;
    var $cart = $view.find('.kifi-keep-box-keep-image-cart');
    if ($cart.hasClass('kifi-animated')) {
      log('[swipeImage] already animated');
      return i;
    }
    data.shown.imageIdx = i = (i + (back ? n : 1)) % (n + 1);
    var $old = $cart.find('.kifi-keep-box-keep-image');  // TODO: verify new img still qualifies, capture its current src
    var $new = $(i < n ? data.canvases[i] || (data.canvases[i] = newKeepCanvas(data.images[i])) : newNoImage());
    $cart.addClass(back ? 'kifi-back' : 'kifi-forward');
    $new[back ? 'prependTo' : 'appendTo']($cart).layout();
    $cart.addClass('kifi-animated').layout()
    .on('transitionend', function end(e) {
      if (e.target === this) {
        if (!back) {
          $cart.removeClass('kifi-animated kifi-back kifi-forward');
        }
        $old.remove();
        $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward')
          .off('transitionend', end);
      }
    }).addClass('kifi-roll');
    return i;
  }

  function onKeepEdit($view, prop, val) {
    var data = $view.data();
    data.shown[prop] = val;
    if (Object.keys(data.shown).every(propsEqual.bind(null, data.shown, data.saved))) {
      if (data.dirty) {
        data.dirty = false;
        $view.removeClass('kifi-dirty');
      }
    } else if (!data.dirty) {
      data.dirty = true;
      $view.addClass('kifi-dirty');
    }
  }

  function saveKeep($view, $btn) {
    var libraryId = $view.find('.kifi-keep-box-lib').data('id');
    var data = $view.data();
    var n = data.images.length;
    var i = data.shown.imageIdx;
    var title = data.shown.title;
    if (title) {
      var imageUrl = i < n ? getSrc(data.images[i]) : null;
      var promises = [];
      if ($view.hasClass('kifi-kept')) {
        if (title !== data.saved.title) {
          var d1 = Q.defer();
          api.port.emit('save_keep', {
            libraryId: libraryId,
            updates: {title: title}
          }, function (success) {
            d1[success ? 'resolve' : 'reject']();
          });
          promises.push(d1.promise);
        }
        if (i !== data.saved.imageIdx) {
          var d2 = Q.defer();
          api.port.emit('save_keep_image', {
            libraryId: libraryId,
            image: imageUrl
          }, function (success) {
            d2[success ? 'resolve' : 'reject']();
          });
          promises.push(d2.promise);
        }
      } else {
        var d3 = Q.defer();
        $box.data('keepPage')({
          libraryId: libraryId,
          title: title,
          image: imageUrl
        }, function (keep) {
          if (keep) {
            $view.addClass('kifi-kept');
          }
          d3[keep ? 'resolve' : 'reject']();
        });
        promises.push(d3.promise);
      }
      if (promises.length) {
        var $progress = $btn.find('.kifi-keep-box-progress');
        $view.find('.kifi-keep-box-keep-title,.kifi-keep-box-keep-comment').prop('disabled', true);
        $btn.removeAttr('href').addClass('kifi-doing');
        updateProgress.call($progress[0], 0);
        Q.all(promises).done(function () {
          endProgress($progress, true);
          hideAfter(1000);
        }, function () {
          endProgress($progress, false);
        });
      }
    } else {
      $title.val($title.attr('value')).focus().select();
    }
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
        endProgress($progress, !!library);
        if (library) {
          var $old = $view.data('$prev');
          $old.data('librariesById')[library.id] = library;
          $old.data('$all').find('.kifi-keep-box-lib.kifi-create').before(render('html/keeper/keep_box_lib', library));
          setTimeout(function () {
            $name.prop('disabled', false);
            showKeep(library);
          }, 200);
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

  function endProgress($progress, success) {
    log('[endProgress]', success ? 'success' : 'error');
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

  function getSrc(img) {
    return img.currentSrc || largestSrc(img.srcset) || img.src;
  }

  function largestSrc(srcset) { // until currentSrc is widely supported
    if (srcset) {
      var uri = srcset.split(/\s*,\s*/).map(function (s) {
        return s.split(/\s+/);
      }).sort(function (a, b) {
        return (parseFloat(b[1]) || 0) - (parseFloat(a[1]) || 0);
      })[0][0];
      if (uri) {
        try {
          return new URL(uri, document.baseURI).toString();
        } catch (e) {}
      }
    }
  }

  function propsEqual(o1, o2, p) {
    return o1[p] === o2[p];
  }
}());
