// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/keep_box.js
// @require scripts/html/keeper/keep_box_keep.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/html/keeper/keep_box_libs.js
// @require scripts/html/keeper/keep_box_libs_list.js
// @require scripts/html/keeper/keep_box_new_lib.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/mustache.js
// @require scripts/lib/q.min.js
// @require scripts/lib/underscore.js
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
    show: function ($parent, data, howKept) {
      log('[keepBox.show]');
      if ($box) {
        hide();
      }
      data.libraries.forEach(function (lib) {
        var keep = data.keeps.find(libraryIdIs(lib.id));
        if (keep) {
          lib.keep = keep;
        }
      });
      show($parent, data.libraries, howKept);
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

  function show($parent, libraries, howKept) {
    log('[keepBox:show]');
    $box = $(render('html/keeper/keep_box', partitionLibs(libraries), {
      view: 'keep_box_libs',
      keep_box_lib: 'keep_box_lib',
      keep_box_libs_list: 'keep_box_libs_list'
    }))
    .on('mousedown', function (e) {
      if (e.which === 1) {
        e.preventDefault();
      }
    })
    .on('click', '.kifi-keep-box-x', function (e) {
      if (e.which === 1 && $box) {
        hide(e, 'x');
      }
    })
    .on('click', '.kifi-keep-box-back', function (e) {
      if (e.which === 1 && $box) {
        navBack();
      }
    })
    .appendTo($parent)
    .data('libraries', libraries);
    addLibrariesBindings($box.find('.kifi-keep-box-view-libs'));

    $(document).data('esc').add(hide);

    $box.layout()
    .on('transitionend', onShown)
    .removeClass('kifi-down');
  }

  function onShown(e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[keepBox:onShown]');
      $box.off('transitionend', onShown);
      $box.find('input').first().focus();
      makeScrollable($box);
    }
  }

  var hideTimeout;
  function hide(e, trigger) {
    clearTimeout(hideTimeout), hideTimeout = null;
    log('[keepBox:hide]');
    $(document).data('esc').remove(hide);
    $box.find('.kifi-keep-box-view').triggerHandler('kifi-hide');
    $box
      .css('overflow', '')
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

  function swipeTo($new, back) {
    var $vp = $box.find('.kifi-keep-box-viewport');
    var $cart = $vp.find('.kifi-keep-box-cart');
    var $old = $cart.find('.kifi-keep-box-view').first();

    var vpHeightOld = $vp[0].offsetHeight;
    $vp.css('height', vpHeightOld);

    $cart.addClass(back ? 'kifi-back' : 'kifi-forward');
    $new[back ? 'prependTo' : 'appendTo']($cart);
    makeScrollable($new);

    $box.find('.kifi-keep-box-back').toggleClass('kifi-hidden', $new.hasClass('kifi-keep-box-view-libs'));

    var heightDelta = $new[0].offsetHeight - $old[0].offsetHeight;

    $cart.addClass('kifi-animated').layout().addClass('kifi-roll')
    .on('transitionend', function end(e) {
      if (e.target === this) {
        if (!back) {
          $cart.removeClass('kifi-animated kifi-back kifi-forward');
        }
        $old.remove();
        $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward')
          .off('transitionend', end);
        $new.find('input[type=text]:visible').last().focus().select();

        $vp.css('height', '');
      }
    });

    $vp.css('height', vpHeightOld + heightDelta);
  }

  function navBack() {
    var $view;
    var data = $box.data(), lib = data.libraryCreated;
    if (lib) {
      data.libraries = data.libraries.filter(idIsNot(lib.id));
      delete data.libraryCreated;
      api.port.emit('delete_library', lib.id);
      var $view = $(render('html/keeper/keep_box_new_lib', {name: lib.name, secret: lib.visibility === 'secret'}));
      addCreateLibraryBindings($view);
    } else {
      $view = $(render('html/keeper/keep_box_libs', partitionLibs(data.libraries), {
        keep_box_lib: 'keep_box_lib',
        keep_box_libs_list: 'keep_box_libs_list'
      }));
      addLibrariesBindings($view);
    }
    swipeTo($view, true);
  }

  function partitionLibs(libs) {
    var inLibs = [];
    var otherLibs = [];
    for (var i = 0; i < libs.length; i++) {
      var lib = libs[i];
      if (lib.keep) {
        inLibs.push(lib);
      } else {
        otherLibs.push(lib);
      }
    }
    (inLibs.length ? inLibs : otherLibs)[0].highlighted = true;
    return {
      inLibs: inLibs,
      recentLibs: [],
      otherLibs: otherLibs
    };
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
              (libs[0] || {}).highlighted = true;
              showLibs($(render('html/keeper/keep_box_libs_list', {filtering: true, libs: libs.map(addNameHtml)}, {
                keep_box_lib: 'keep_box_lib'
              })));
            }
          });
        } else {
          showLibs($(render('html/keeper/keep_box_libs_list', partitionLibs($box.data('libraries')), {
            keep_box_lib: 'keep_box_lib'
          })));
        }
      }
    })
    .on('keydown', '.kifi-keep-box-lib-input', function (e) {
      switch (e.keyCode) {
        case 38: // up
        case 40: // down
          var up = e.keyCode === 38;
          var $items = $view.find('.kifi-keep-box-lib'), numItems = $items.length;
          if (numItems) {
            var $item = $items.filter('.kifi-highlighted');
            var index = $item.length ? ($items.index($item) + (up ? numItems - 1 : 1)) % numItems : (up ? numItems - 1 : 0);
            highlightLibrary($items[index]);
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
    .on('click', '.kifi-keep-box-lib-unkeep', function (e) {
      if (e.which === 1) {
        var libraryId = $(this).prev().data('id');
        var library = $box.data('libraries').find(idIs(libraryId));
        log('[unkeep]', libraryId, document.URL);
        api.port.emit('unkeep', {libraryId: libraryId, keepId: library.keep.id});
        hide(e, 'action');
      }
    });
  }

  function addKeepBindings($view) {
    var debouncedSaveKeepTitleIfChanged = _.debounce(saveKeepTitleIfChanged, 1500);
    var debouncedSaveKeepImageIfChanged = _.debounce(saveKeepImageIfChanged, 3000);
    $view
    .on('input', '.kifi-keep-box-keep-title', $.proxy(debouncedSaveKeepTitleIfChanged, null, $view))
    .on('blur', '.kifi-keep-box-keep-title', function () {
      if (!this.value.trim()) {
        this.value = $view.data().saved.title;
        saveKeepTitleIfChanged.call(this, $view);
      }
    })
    .on('click', '.kifi-keep-box-image-prev,.kifi-keep-box-image-next', function (e) {
      if (e.which === 1) {
        var i = swipeImage($view, this.classList.contains('kifi-keep-box-image-prev'));
        $view.data('imageIdx', i);
        debouncedSaveKeepImageIfChanged($view);
      }
    })
    .on('click', '.kifi-keep-box-close', function (e) {
      if (e.which === 1) {
        hide(e, 'action');
      }
    })
    .on('kifi-hide', function () {
      saveKeepTitleIfChanged($view);
      saveKeepImageIfChanged($view);
    });
  }

  function addCreateLibraryBindings($view) {
    $view
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
    $box.find('.kifi-keep-box-libs').replaceWith($new);
    makeScrollable($new);
  }

  function makeScrollable($view) {
    $view.find('.kifi-scroll-inner').each(function () {
      // $(this).siblings('.kifi-scrollbar').remove();
      $(this.parentNode).antiscroll({x: false});
      $(this).on('mousewheel', onScrollInnerWheel);
    });
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

  function chooseLibrary(el) {
    var libraryId = el.dataset.id;
    if (libraryId) {
      var library = $box.data('libraries').find(idIs(libraryId));
      if (library.keep) {
        api.port.emit('get_keep', library.keep.id, showKeep.bind(null, library));
      } else {
        keepTo(library);
      }
    } else {
      var $view = $(render('html/keeper/keep_box_new_lib'));
      addCreateLibraryBindings($view);
      swipeTo($view);
    }
  }

  function keepTo(library) {
    var data = {libraryId: library.id, title: authoredTitle()};
    log('[keep]', data);
    api.port.emit('keep', withUrls(data), function (keep) {
      if (keep) {
        library.keep = keep;
        showKeep(library, {title: data.title, image: null, tags: []}, true);
      }
    });
  }

  function showKeep(library, keep, justKept) {
    var title = keep.title || formatTitleFromUrl(document.URL);
    var images = findImages(), canvases = [], imageIdx;
    if (keep.image) {
      var img = new Image;
      img.src = keep.image;
      images.unshift(img);
      canvases.unshift(newKeepCanvas(img));
      imageIdx = 0;
    } else if (justKept) {
      if (images.length) {
        canvases.unshift(newKeepCanvas(images[0]));
        api.port.emit('save_keep_image', {libraryId: library.id, image: images[0].src});
      }
      imageIdx = 0;
    } else {
      imageIdx = images.length;
    }

    var $view = $(render('html/keeper/keep_box_keep', {
      library: library,
      static: true,
      title: title,
      site: document.location.hostname,
      hasImages: images.length > 0
    }, {
      keep_box_lib: 'keep_box_lib'
    }));
    $view.find('.kifi-keep-box-keep-image-cart').append(canvases[imageIdx] || newNoImage());

    var $tags = $view.find('.kifi-keep-box-tags')
    .data('pre', (keep && keep.tags || []).map(tagNameToTokenItem))
    .tokenInput(searchTags.bind(null, library.id), {
      classPrefix: 'kifi-keep-box-tags-',
      placeholder: 'Add a tag',
      tokenValue: 'tag',
      preventDuplicates: true,
      allowFreeTagging: true,
      formatToken: formatTagToken,
      formatResult: formatTagResult,
      showResults: showTagSuggestions,
      onAdd: $.proxy(onAddTag, null, library.id),
      onDelete: $.proxy(onDeleteTag, null, library.id)
    });
    $view.data({
      library: library,
      imageIdx: imageIdx,
      images: images,
      canvases: canvases,
      saved: {
        title: keep.title,
        imageIdx: keep.image ? imageIdx : null
      },
      saving: {}
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
    var i = data.imageIdx;
    var $cart = $view.find('.kifi-keep-box-keep-image-cart');
    if ($cart.hasClass('kifi-animated')) {
      log('[swipeImage] already animated');
      return i;
    }
    data.imageIdx = i = (i + (back ? n : 1)) % (n + 1);
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

  function saveKeepImageIfChanged($view) {
    if (!$box) return;  // already removed, no data
    var data = $view.data();
    var i = data.imageIdx;
    if (i !== data['imageIdx' in data.saving ? 'saving' : 'saved'].imageIdx) {
      data.saving.imageIdx = i;
      api.port.emit('save_keep_image', {
        libraryId: data.library.id,
        image: i < data.images.length ? getSrc(data.images[i]) : null
      }, function (success) {
        if (data.saving.imageIdx === i) {
          delete data.saving.imageIdx;
        }
        if (success) {
          data.saved.imageIdx = i;
        }
      });
    }
  }

  function saveKeepTitleIfChanged($view) {
    if (!$box) return;  // already removed, no data
    var input = this || $view.find('.kifi-keep-box-keep-title')[0];
    var data = $view.data();
    var val = input.value.trim();
    if (val && val !== data['title' in data.saving ? 'saving' : 'saved'].title) {
      data.saving.title = val;
      api.port.emit('save_keep', {libraryId: data.library.id, updates: {title: val}}, function (success) {
        if (data.saving.title === val) {
          delete data.saving.title;
        }
        if (success) {
          data.saved.title = val;
        }
      });
    }
  }

  function searchTags(libraryId, numTokens, query, withResults) {
    if (query) {
      api.port.emit('search_tags', {q: query, n: 4, libraryId: libraryId}, function (items) {
        items.push({tag: query, matches: [[0, query.length]]});
        withResults(items);
      });
    } else {
      withResults([]);
    }
  }

  function showTagSuggestions($dropdown, els, done) {
    $dropdown
      .empty().append(els)
      .position({my: 'left-6 bottom', at: 'left top', of: $dropdown.prev().find('input'), collision: 'fit none'})
    done();
  }

  function onAddTag(libraryId, tag) {
    var $tags = $(this);
    api.port.emit('tag', {libraryId: libraryId, tag: tag.tag}, function (name) {
      if (name && name !== tag.tag) {
        $tags.tokenInput('replace', tag, {tag: name});
      }
    });
  }

  function onDeleteTag(libraryId, tag) {
    api.port.emit('untag', {libraryId: libraryId, tag: tag.tag}, api.noop);
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
          $box.data('libraryCreated', library);
          $box.data('libraries').push(library);
          keepTo(library);
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
    lib.nameHtml = appendParts([], lib.nameParts).join('');
    return lib;
  }

  function appendParts(html, parts) {
    for (var i = 0; i < parts.length; i++) {
      if (i % 2) {
        html.push('<b>', Mustache.escape(parts[i]), '</b>');
      } else {
        html.push(Mustache.escape(parts[i]));
      }
    }
    return html;
  }

  function formatTagToken(item) {
    return '<li>' + Mustache.escape(item.tag) + '</li>';
  }

  function formatTagResult(item) {
    var html = ['<li class="kifi-keep-box-tags-dropdown-item-token">'];
    pushWithBoldedMatches(html, item.tag, item.matches);
    html.push('</li>');
    return html.join('');
  }

  function pushWithBoldedMatches(html, text, matches) {
    var i = 0;
    for (var j = 0; j < matches.length; j++) {
      var match = matches[j];
      var pos = match[0];
      var len = match[1];
      if (pos >= i) {
        html.push(Mustache.escape(text.substring(i, pos)), '<b>', Mustache.escape(text.substr(pos, len)), '</b>');
        i = pos + len;
      }
    }
    html.push(Mustache.escape(text.substr(i)));
  }

  function idIs(id) {
    return function (o) {return o.id === id};
  }

  function idIsNot(id) {
    return function (o) {return o.id !== id};
  }

  function libraryIdIs(id) {
    return function (o) {return o.libraryId === id};
  }

  function tagNameToTokenItem(name) {
    return {tag: name};
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
