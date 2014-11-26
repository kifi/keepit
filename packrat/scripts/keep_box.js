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

k.keepBox = k.keepBox || (function () {
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
    show: function ($parent, trigger, data, guided) {
      if ($box) {
        hide();
      }
      data.libraries.forEach(function (lib) {
        var keep = data.keeps.find(libraryIdIs(lib.id));
        if (keep) {
          lib.keep = keep;
        }
        setShortcut(lib);
      });
      show($parent, trigger, guided, data.libraries, data.showLibraryIntro);
    },
    hide: function (trigger) {
      if ($box) {
        hide(null, trigger);
      } else {
        log('[keepBox:hide] no-op', trigger || '');
      }
    },
    onHide: new Listeners(),
    showing: function () {
      return !!$box;
    },
    appendTip: function (tip) {
      return $(tip).appendTo($box.data('tip', true));
    },
    keep: function (priv, guided) {
      $box.find('.kifi-keep-box-lib.kifi-system' + (priv ? '.kifi-secret' : '.kifi-discoverable')).each(function () {
        chooseLibrary(this, 'key', guided);
      });
    }
  };

  function show($parent, trigger, guided, libraries, showIntro) {
    log('[keepBox:show]', trigger, guided ? 'guided' : '');
    $box = $(k.render('html/keeper/keep_box', partitionLibs(libraries), {
      view: 'keep_box_libs',
      keep_box_lib: 'keep_box_lib',
      keep_box_libs_list: 'keep_box_libs_list'
    }))
    .on('mousedown', function (e) {
      var $target = $(e.target);
      if (e.which === 1 && !$target.is('input[type=text]')) {
        e.preventDefault();  // prevent keeper drag
        $target.focus();
      }
    })
    .on('click', '.kifi-keep-box-x', function (e) {
      if (e.which === 1 && $box) {
        hide(e, 'x');
      }
    })
    .on('click', '.kifi-keep-box-back', function (e) {
      if (e.which === 1 && $box) {
        navBack('mouse', e.originalEvent.guided);
      }
    })
    .appendTo($parent)
    .data('libraries', libraries);
    addLibrariesBindings($box.find('.kifi-keep-box-view-libs'));

    $(document).data('esc').add(hide);

    api.port.emit('track_pane_view', {type: 'libraryChooser', subsource: trigger, guided: guided});

    $box.layout()
    .on('transitionend', $.proxy(onShown, null, showIntro))
    .removeClass('kifi-down');
  }

  function onShown(showIntro, e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[keepBox:onShown]');
      $box.off('transitionend', onShown);
      $box.find('input').first().focus();
      makeScrollable($box);
      if (showIntro && !k.guide) {
        api.require('scripts/libraries_intro.js', api.noop);
      }
    }
  }

  var hideInterval;
  function hide(e, trigger) {
    clearInterval(hideInterval), hideInterval = null;
    trigger = trigger || (e && e.keyCode === 27 ? 'esc' : undefined);
    log('[keepBox:hide]', trigger);
    $(document).data('esc').remove(hide);
    var $view = $box.find('.kifi-keep-box-view');
    $view.triggerHandler('kifi-hide');
    $box
      .css('overflow', '')
      .on('transitionend', onHidden)
      .addClass('kifi-down');
    $box = null;
    if (e) e.preventDefault();
    k.keepBox.onHide.dispatch(trigger);

    api.port.emit('track_pane_click', {
      type: $view.hasClass('kifi-keep-box-view-libs') ? 'libraryChooser' :
            $view.hasClass('kifi-keep-box-view-keep') ? 'keepDetails' : 'createLibrary',
      action: 'closed',
      subaction: trigger === 'clickout' ? 'outside' : (trigger || undefined)
    });
  }

  function onHidden(e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[keepBox:onHidden]');
      $(this).remove();
    }
  }

  function swipeTo($new, back) {
    var $vp = $box.find('.kifi-keep-box-viewport');
    var $cart = $vp.find('.kifi-keep-box-cart');
    var $old = $cart.find('.kifi-keep-box-view').first();
    $old.triggerHandler('kifi-hide');

    var vpHeightOld = $vp[0].offsetHeight;
    $vp.css('height', vpHeightOld);

    $cart.addClass(back ? 'kifi-back' : 'kifi-forward');
    $new[back ? 'prependTo' : 'appendTo']($cart);
    makeScrollable($new);

    $box.find('.kifi-keep-box-back').toggleClass('kifi-hidden', $new.hasClass('kifi-keep-box-view-libs'));
    var $title = $box.find('.kifi-keep-box-title').first().on('transitionend', removeThis);
    $title.clone().text($new.data('boxTitle')).css('opacity', 0).insertAfter($title).layout().css('opacity', '');

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
        $vp.css('height', '');
        $new.find('input[type=text]:visible').last().focus().select();
        $new.triggerHandler('kifi-show');
      }
    });

    $vp.css('height', vpHeightOld + heightDelta);
  }

  function navBack(trigger, guided) {
    var $view, $oldView = $box.find('.kifi-keep-box-view');
    var data = $box.data(), lib = data.libraryCreated;
    if (lib) {
      data.libraries = data.libraries.filter(idIsNot(lib.id));
      delete data.libraryCreated;
      api.port.emit('delete_library', lib.id);
      $view = $(k.render('html/keeper/keep_box_new_lib', {name: lib.name, secret: lib.visibility === 'secret'}));
      addCreateLibraryBindings($view);
    } else {
      $view = $(k.render('html/keeper/keep_box_libs', partitionLibs(data.libraries), {
        keep_box_lib: 'keep_box_lib',
        keep_box_libs_list: 'keep_box_libs_list'
      }));
      addLibrariesBindings($view);
    }
    swipeTo($view, true);

    api.port.emit('track_pane_click', {
      type: $oldView.hasClass('kifi-keep-box-view-keep') ? 'keepDetails' : 'createLibrary',
      action: 'wentBack',
      subaction: trigger === 'key' ? 'key' : undefined,
      guided: guided
    });
  }

  function partitionLibs(libs) {
    var nLibs = libs.length;
    var inLibs = [];
    var otherLibs = [];
    var recentLibs = [];
    for (var i = 0; i < nLibs; i++) {
      var lib = libs[i];
      lib.highlighted = false;
      if (lib.keep) {
        inLibs.push(lib);
      } else if (lib.recent && nLibs >= 6) {
        recentLibs.push(lib);
      } else {
        otherLibs.push(lib);
      }
    }
    (inLibs[0] || recentLibs[0] || otherLibs[0]).highlighted = true;
    return {
      showMyLibrariesHeading: nLibs >= 6 || inLibs.length,
      allowFiltering: nLibs >= 6,
      inLibs: inLibs,
      recentLibs: otherLibs.length ? recentLibs : [],
      otherLibs: otherLibs.length ? otherLibs : recentLibs
    };
  }

  function addLibrariesBindings($view) {
    $view
    .on('input', '.kifi-keep-box-lib-input', function (e) {
      if (this.classList.contains('kifi-disabled')) return;
      var q = this.value.trim();
      var data = $.data(this);
      if (data.q !== q) {
        data.q = q;
        if (q) {
          api.port.emit('filter_libraries', q, function (libs) {
            if (data.q === q) {
              libs.forEach(setShortcut);
              (libs[0] || {}).highlighted = true;
              showLibs($(k.render('html/keeper/keep_box_libs_list', {query: q, libs: libs.map(addNameHtml)}, {
                keep_box_lib: 'keep_box_lib'
              })));
            }
          });
          if (!data.filtered) {
            data.filtered = true;
            api.port.emit('track_pane_click', {type: 'libraryChooser', action: 'filteredLibraries', guided: e.originalEvent.guided});
          }
        } else {
          showLibs($(k.render('html/keeper/keep_box_libs_list', partitionLibs($box.data('libraries')), {
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
            chooseLibrary($item[0], 'enter', e.originalEvent.guided);
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
        chooseLibrary(this, 'mouse', e.originalEvent.guided);
      }
    })
    .on('click', '.kifi-keep-box-lib-unkeep', function (e) {
      if (e.which === 1) {
        unkeep($(this));
      }
    });
  }

  function addKeepBindings($view, autoClose) {
    var debouncedSaveKeepTitleIfChanged = _.debounce(saveKeepTitleIfChanged, 1500);
    var debouncedSaveKeepImageIfChanged = _.debounce(saveKeepImageIfChanged, 2400);
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
        var prev = this.classList.contains('kifi-keep-box-image-prev');
        var i = swipeImage($view, prev);
        $view.data('imageIdx', i);
        debouncedSaveKeepImageIfChanged($view);
        api.port.emit('track_pane_click', {type: 'keepDetails', action: 'slidImage', subaction: prev ? 'left' : 'right'});
      }
    })
    .on('click', '.kifi-keep-box-done', function (e) {
      if (e.which === 1) {
        hide(e, 'button');
      }
    })
    .on('keydown', function (e) {
      if ((e.keyCode === 13 || e.keyCode === 108) && !e.isDefaultPrevented()) { // enter, numpad enter
        hide(e, 'enter');
        e.preventDefault();
      } else if (e.keyCode === 8 && !e.isDefaultPrevented() && (e.target.type !== 'text' || !e.target.selectionStart && !e.target.selectionEnd)) {
        navBack('key', e.originalEvent.guided);
        e.preventDefault();
      }
    })
    .on('kifi-show', function () {
      if (autoClose) {
        var $timer = $view.find('.kifi-keep-box-timer').addClass('kifi-going');
        var $text = $timer.find('.kifi-keep-box-timer-text');
        var nSec = +$text.text();
        if (nSec > 0) {
          hideInterval = setInterval(function () {
            $text.text(--nSec);
            if (nSec === 0) {
              hide(null, 'timer');
            }
          }, 1000);
        }
        $view.on('mouseover keydown', function abortAutoClose() {
          $view.off('mouseover keydown', abortAutoClose);
          clearInterval(hideInterval), hideInterval = null;
          $timer.remove();
        });
      }
    })
    .on('kifi-hide', function () {
      saveKeepTitleIfChanged($view);
      saveKeepImageIfChanged($view);
    });
  }

  function addCreateLibraryBindings($view) {
    var $name = $view
    .on('keydown', function (e) {
      if ((e.keyCode === 13 || e.keyCode === 108) && !e.isDefaultPrevented() && e.originalEvent.isTrusted !== false) { // enter, numpad enter
        createLibrary($view, $submit, 'enter', e.originalEvent.guided);
        e.preventDefault();
      } else if (e.keyCode === 8 && !e.isDefaultPrevented() && (e.target.type !== 'text' || !e.target.selectionStart && !e.target.selectionEnd)) {
        navBack('key', e.originalEvent.guided);
        e.preventDefault();
      }
    });
    $view
    .on('blur', '.kifi-keep-box-new-lib-name', function (e) {
      this.value = this.value.trim();
    })
    .on('mousedown', '.kifi-keep-box-new-lib-secret', toggleVisibility)
    .on('keydown', '.kifi-keep-box-new-lib-secret', function (e) {
      if (e.keyCode === 32 && !e.isDefaultPrevented() && e.originalEvent.isTrusted !== false) {
        toggleVisibility.call(this, e);
      }
    });
    var $submit = $view.find('.kifi-keep-box-new-lib-create')
    .on('click', function (e) {
      if (e.which === 1 && this.href) {
        createLibrary($view, $submit, 'mouse', e.originalEvent.guided);
      }
    });
  }

  function toggleVisibility(e) {
    e.preventDefault();
    $(this).on('transitionend', function end() {
      $(this).off('transitionend', end).removeClass('kifi-transition');
    }).addClass('kifi-transition');
    var secret = this.parentNode.classList.toggle('kifi-secret');
    api.port.emit('track_pane_click', {
      type: 'createLibrary',
      action: 'changedVisibility',
      subaction: secret ? 'private' : 'public',
      guided: e.originalEvent.guided
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
    $(el).closest('.kifi-keep-box-view-libs').find('.kifi-highlighted').removeClass('kifi-highlighted');
    el.classList.add('kifi-highlighted');
  }

  function chooseLibrary(el, trigger, guided) {
    var libraryId = el.dataset.id;
    if (libraryId) {
      var library = $box.data('libraries').find(idIs(libraryId));
      var $head = $([el, el.parentNode]).prevAll('.kifi-keep-box-lib-head').first();
      var subsource =
        $head.length === 0 ? (el[matches]('.kifi-keep-box-libs.kifi-filtered *') ? 'libraryFiltered' : 'libraryNoGroup') :
        $head.hasClass('kifi-already') ? 'libraryKeptIn' :
        $head.hasClass('kifi-recent')? 'libraryRecent' :
        $head.hasClass('kifi-other') ? 'libraryOther' : 'libraryMine';
      if (library.keep) {
        api.port.emit('get_keep', library.keep.id, function (keep) {
          library.keep = keep;
          showKeep(library, subsource, trigger, guided);
        });
      } else {
        el.style.position = 'relative';
        progress(el, keepTo(library, guided)).done(function (keep) {
          showKeep(library, subsource, trigger, guided, true);
        });
      }
    } else {
      var name = (el.firstElementChild || {}).textContent;
      var $view = $(k.render('html/keeper/keep_box_new_lib', {name: name}));
      addCreateLibraryBindings($view);
      swipeTo($view);
      api.port.emit('track_pane_click', {type: 'libraryChooser', action: 'choseCreateLibrary', guided: guided});
      api.port.emit('track_pane_view', {type: 'createLibrary', subsource: trigger === 'enter' ? 'key' : undefined, guided: guided});
    }
  }

  function keepTo(library, guided) {
    var data = {libraryId: library.id, guided: guided};
    log('[keep]', data);
    var deferred = Q.defer();
    api.port.emit('keep', withTitles(withUrls(data)), function (keep) {
      if (keep) {
        if (library.system) {
          $box.data('libraries').forEach(deleteKeepIfSystemLib);
        }
        library.keep = keep;
        deferred.resolve(keep);
        $box.parent().find('.kifi-keep-btn')
          .filter('.kifi-pulse-before').removeClass('kifi-pulse-before').layout().end()
          .addClass('kifi-pulse-before');
      } else {
        deferred.reject();
      }
    });
    return deferred.promise;
  }

  function unkeep($btn) {
    var $lib = $btn.prev();
    var libraryId = $lib.data('id');
    log('[unkeep]', libraryId, document.URL);
    var library = $box.data('libraries').find(idIs(libraryId));
    var deferred = Q.defer();
    api.port.emit('unkeep', {libraryId: libraryId, keepId: library.keep.id}, function (success) {
      if (success) {
        delete library.keep;
        deferred.resolve();
      } else {
        $btn.fadeIn(160);
        deferred.reject();
      }
    });
    $btn.fadeOut(160);
    var $kept = $btn.parent();
    progress($kept, deferred.promise).done(function () {
      var $fromHead = $kept.next().hasClass('kifi-keep-box-lib-head') ? $kept.prev('.kifi-keep-box-lib-head') : $();
      var $from = $('<div class="kifi-keep-box-lib-unkept-from"/>').insertAfter($kept);
      $kept.addClass('kifi-unkeeping');
      var $toHead = $kept.nextAll('.kifi-keep-box-lib-head').first();
      var $to = $('<div class="kifi-keep-box-lib-unkept-to"/>').insertAfter($toHead);
      var dy = $to[0].getBoundingClientRect().bottom - $from[0].getBoundingClientRect().bottom;
      $kept.on('transitionend', function (e) {
        if (e.target === this) {
          $to.replaceWith($lib);
          $from.remove();
          $kept.remove();
        }
      }).css('transform', 'translate(0,' + dy + 'px)');
      $fromHead.add($from).add($to).addClass('kifi-unkeeping');
    });
  }

  function showKeep(library, subsource, trigger, guided, justKept) {
    var images = [];
    determineInitialImage(images, library, justKept, findImages(images))
      .done(showKeep2.bind(null, library, subsource, trigger, guided, justKept && !$box.data('tip'), images));
  }

  function determineInitialImage(images, library, justKept, pageImagePromise) {
    var keepImageUrl = library.keep.image;
    if (keepImageUrl) {
      var img = new Image;
      img.src = keepImageUrl;
      images.unshift(img);
      return Q(true);
    } else if (justKept) {
      return pageImagePromise.then(function (img) {
        api.port.emit('save_keep_image', {libraryId: library.id, image: img.src});
        return true;
      }, function () {
        return false;
      });
    }
    return Q(false);
  }

  function showKeep2(library, subsource, trigger, guided, autoClose, images, showImage) {
    var keep = library.keep;
    var title = keep.title || formatTitleFromUrl(document.URL);
    var canvases = showImage ? [newKeepCanvas(images[0])] : [];   // TODO: show spinner while this image is loading
    var $view = $(k.render('html/keeper/keep_box_keep', {
      library: library,
      title: title,
      site: document.location.hostname,
      hasImages: images.length > 0,
      autoClose: autoClose
    }, {
      keep_box_lib: 'keep_box_lib'
    }));
    $view.find('.kifi-keep-box-keep-image-cart').append(canvases[0] || newNoImage());
    if (images.length === 0) {
      $view.find('.kifi-keep-box-keep-image-picker').addClass('kifi-empty');
    }

    var $tags = $view.find('.kifi-keep-box-tags')
    .data('pre', (keep.tags || []).map(tagNameToTokenItem))
    .tokenInput(suggestTags.bind(null, library.id, keep.id), {
      classPrefix: 'kifi-keep-box-tags-',
      placeholder: 'Add a tag',
      tokenValue: 'tag',
      preventDuplicates: true,
      allowFreeTagging: true,
      formatToken: formatTagToken,
      formatResult: formatTagResult,
      showResults: $.proxy(showTagSuggestions, null, $view),
      onAdd: $.proxy(onAddTag, null, $view, library.id),
      onDelete: $.proxy(onDeleteTag, null, $view, library.id)
    });
    var imageIdx = showImage ? 0 : -1;
    $view.data({
      library: library,
      imageIdx: imageIdx,
      images: images,
      canvases: canvases,
      saved: {
        title: keep.title,
        imageIdx: keep.image ? imageIdx : -1
      },
      saving: {}
    });
    addKeepBindings($view, autoClose);

    api.port.emit('track_pane_view', {
      type: 'keepDetails',
      subsource: subsource,
      key: {key: 'shortcut', enter: 'enter'}[trigger],
      guided: guided
    });

    swipeTo($view);
  }

  // Returns a promise that resolves with the first suitable image identified (an IMG element).
  // Additional images may be appended later.
  function findImages(images) {
    var srcs = {};
    function useImageIfSuitable(img) {
      var src;
      if (isSuitableImage(img) && !srcs[(src = img.src)]) {
        srcs[src] = true;
        images.push(img);
        return true;
      }
    }

    var deferred = Q.defer();
    var imgs = document.getElementsByTagName('img');
    for (var i = 0, n = imgs.length; i < n; i++) {
      var img = imgs[i];
      if (useImageIfSuitable(img)) {
        deferred.resolve(img);
        break;
      }
    }
    imgs = Array.prototype.slice.call(imgs, i + 1);

    function finishFindingImages() {
      imgs.forEach(useImageIfSuitable);

      var bgUrls = Array.prototype.reduce.call(document.styleSheets, appendBgImagesInStylesheet, []);
      var loading = {}, nLoading = 0;
      bgUrls.forEach(function (url) {
        if (nLoading < 120 && !loading[url]) {
          nLoading++, loading[url] = true;
          var img = new Image;
          img.onload = onImageLoadEnd;
          img.onerror = onImageLoadEnd;
          img.src = url;
        }
      });

      function onImageLoadEnd() {
        var url = this.src;
        if (loading[url]) {
          nLoading--, loading[url] = false;
          if (useImageIfSuitable(this)) {
            deferred.resolve(this);
          } else if (nLoading === 0) {
            deferred.reject();
          }
        }
      }

      if (nLoading === 0) {
        deferred.reject();
      }
    }

    if (deferred.promise.isPending()) {
      finishFindingImages();
      setTimeout(deferred.reject.bind(deferred), 1200);
    } else {
      setTimeout(finishFindingImages);
    }

    return deferred.promise;
  }

  function appendBgImagesInStylesheet(arr, ss) {
    var rules;
    try {
      rules = ss.cssRules;
    } catch (e) {  // SecurityError
    }
    if (rules && rules.length) {
      var baseUrl = ss.href;
      if (!baseUrl || baseUrl.lastIndexOf('http', 0) === 0) {  // no extension resource: stylesheets
        var resolveUri = resolveUriRelativeToThis.bind(baseUrl || document.baseURI);
        Array.prototype.reduce.call(rules, appendBgImagesInRule.bind(null, resolveUri), arr);
      }
    }
    return arr;
  }

  function appendBgImagesInRule(resolveUri, arr, rule) {
    var s = rule.style, bi = s && s.backgroundImage;
    if (bi) {
      var uris = bi.match(/url\(\s*(?:[^"'].*?|".*?"|'.*?')\s*\)/g);
      if (uris) {
        uris = uris.map(parseUriFromCssUrl).filter(bgUriLooksInteresting);
        if (uris.length) {
          if (rule.selectorText.indexOf('kifi') < 0) {
            arr.push.apply(arr, uris.map(resolveUri));
          }
        }
      }
    }
    return arr;
  }

  function parseUriFromCssUrl(cssUrl) {  // e.g. 'url( "foo.png" )' -> 'foo.png'
    return cssUrl.substring(4, cssUrl.length - 1).trim().replace(/(?:^['"]*|['"]*$)/g, '');
  }

  function bgUriLooksInteresting(uri) {
    return !/(?:^data:|sprite|icon)/i.test(uri);
  }

  function resolveUriRelativeToThis(uri) {
    return new URL(uri, this).href;
  }

  function isSuitableImage(img) {
    var nH, nW, src;
    // log(img.naturalHeight, img.naturalWidth, img.naturalHeight * img.naturalWidth, img.complete, img.src);
    return (
      (nH = img.naturalHeight) >= 64 &&
      (nW = img.naturalWidth) >= 64 &&
      nW * nH >= 12000 &&
      img.complete &&
      (src = getSrc(img)).lastIndexOf('http', 0) === 0 &&
      src.indexOf('.svg', src.length - 4) === -1 &&  // TODO: exempt SVG from min size when supported
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
    data.imageIdx = i = back ? (i >= 0 ? i : n) - 1 : (i === n - 1 ? -1 : i + 1);
    var $old = $cart.find('.kifi-keep-box-keep-image');  // TODO: verify new img still qualifies, capture its current src
    var $new = $(i >= 0 ? data.canvases[i] || (data.canvases[i] = newKeepCanvas(data.images[i])) : newNoImage());
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
      var deferred = Q.defer();
      data.saving.imageIdx = i;
      api.port.emit('save_keep_image', {
        libraryId: data.library.id,
        image: i >= 0 ? getSrc(data.images[i]) : null
      }, function (success) {
        if (data.saving.imageIdx === i) {
          delete data.saving.imageIdx;
        }
        if (success) {
          data.saved.imageIdx = i;
          deferred.resolve();
        } else {
          deferred.reject();
        }
      });
      showSaveKeepProgress($view, deferred.promise);
    }
  }

  function saveKeepTitleIfChanged($view) {
    if (!$box) return;  // already removed, no data
    var input = this || $view.find('.kifi-keep-box-keep-title')[0];
    var data = $view.data();
    var val = input.value.trim();
    if (val && val !== data['title' in data.saving ? 'saving' : 'saved'].title) {
      var deferred = Q.defer();
      data.saving.title = val;
      api.port.emit('save_keep_title', {libraryId: data.library.id, title: val}, function (success) {
        if (data.saving.title === val) {
          delete data.saving.title;
        }
        if (success) {
          data.saved.title = val;
          deferred.resolve();
        } else {
          deferred.reject();
        }
      });
      showSaveKeepProgress($view, deferred.promise);
    }
  }

  function showSaveKeepProgress($view, promise) {
    var $pp = $view.find('.kifi-keep-box-progress-parent').empty();
    progress($pp, promise).fin(function () {
      $pp.children().delay(300).fadeOut(300);
    });
  }

  // Takes a promise for a task's outcome. Returns a promise that relays
  // the outcome after visual indication of the outcome is complete.
  function progress(parent, promise) {
    var $el = $('<div class=kifi-keep-box-progress>').appendTo(parent);
    var frac = 0, ms = 10, deferred = Q.defer();
    var timeout = setTimeout(function update() {
      var left = .9 - frac;
      frac += .06 * left;
      $el[0].style.width = Math.min(frac * 100, 100) + '%';
      if (left > .0001) {
        timeout = setTimeout(update, ms);
      }
    }, ms);

    promise.done(function (val) {
      log('[progress:done]');
      clearTimeout(timeout), timeout = null;
      $el.removeClass('kifi-doing').on('transitionend', function (e) {
        if (e.originalEvent.propertyName === 'clip') {
          $el.off('transitionend');
          deferred.resolve(val);
        }
      }).addClass('kifi-done');
    }, function (reason) {
      log('[progress:fail]');
      clearTimeout(timeout), timeout = null;
      $el.removeClass('kifi-doing').one('transitionend', function () {
        $el.remove();
        deferred.reject(reason);
      }).addClass('kifi-fail');
    });
    return deferred.promise;
  }

  function suggestTags(libraryId, keepId, tags, query, withResults) {
    if (query) {
      api.port.emit('suggest_tags', {q: query, n: 4, libraryId: libraryId, keepId: keepId, tags: tags}, withResults);
    } else {
      withResults([]);
    }
  }

  function showTagSuggestions($view, $dropdown, els, done) {
    $dropdown
      .empty().append(els)
      .position({my: 'left-6 bottom', at: 'left top', of: $dropdown.prev().find('input'), collision: 'fit none', within: $view})
    done();
  }

  function onAddTag($view, libraryId, tag) {
    var deferred = Q.defer();
    var $tags = $(this);
    api.port.emit('tag', {libraryId: libraryId, tag: tag.tag}, function (name) {
      if (name) {
        if (name !== tag.tag) {
          $tags.tokenInput('replace', tag, {tag: name});
        }
        deferred.resolve();
      } else {
        deferred.reject();
      }
    });
    showSaveKeepProgress($view, deferred.promise);
  }

  function onDeleteTag($view, libraryId, tag) {
    var deferred = Q.defer();
    api.port.emit('untag', {libraryId: libraryId, tag: tag.tag}, function (success) {
      deferred[success ? 'resolve' : 'reject']();
    });
    showSaveKeepProgress($view, deferred.promise);
  }

  function createLibrary($view, $btn, trigger, guided) {
    var $name = $view.find('.kifi-keep-box-new-lib-name');
    var $vis = $view.find('.kifi-keep-box-new-lib-visibility');
    var name = $name.val().trim();
    if (!name) {
      showError('Please type a name for your new library');
    } else if (/[\/"]/.test(name)) {
      showError('No slashes or quotes, please');
    } else {
      $name.prop('disabled', true);
      $btn.removeAttr('href');
      var deferred = Q.defer();
      api.port.emit('create_library', {
        name: name,
        visibility: $vis.hasClass('kifi-secret') ? 'secret' : 'published'
      }, function (library) {
        if (library) {
          $box.data('libraryCreated', library);
          $box.data('libraries').push(library);
          keepTo(library, guided).done(function () {
            deferred.resolve(library);
          }, function () {
            // TODO: undo create library?
            deferred.reject();
          });
        } else {
          deferred.reject();
        }
      });
      progress($vis, deferred.promise).done(function (library) {
        showKeep(library, 'libraryNew', trigger, guided, true);
      }, function (reason) {
        $name.prop('disabled', false).focus().select();
        $btn.prop('href', 'javascript:');
        showError('Hrm, maybe try a different name?');
      });
      api.port.emit('track_pane_click', {
        type: 'createLibrary',
        action: 'createdLibrary',
        subaction: trigger === 'enter' ? 'key' : undefined,
        guided: guided
      });
    }

    function showError(text) {
      var $err = $view.find('.kifi-keep-box-new-lib-name-error').off('transitionend');
      clearTimeout($err.data('t'));
      $err.text(text).layout().addClass('kifi-showing');
      $err.data('t', setTimeout(function () {
        $err.one('transitionend', $.fn.text.bind($err, '')).removeClass('kifi-showing');
      }, 2000));
      $name.focus().select();
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
    if (item.matches || item.freeTag) {
      pushWithBoldedMatches(html, item.tag, item.matches || [[0, item.tag.length]]);
    } else {
      html.push(Mustache.escape(item.tag));
    }
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

  function removeThis() {
    $(this).remove();
  }

  function setShortcut(lib) {
    if (lib.system) {
      lib.shortcut = MOD_KEYS.c + '-Shift-' + (lib.visibility === 'secret' ? MOD_KEYS.alt + '-' : '') + 'K';
    }
  }

  function deleteKeepIfSystemLib(lib) {
    if (lib.system && lib.keep) {
      delete lib.keep;
    }
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
}());
