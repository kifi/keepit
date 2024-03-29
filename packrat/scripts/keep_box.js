// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/kifi_mustache_tags.js
// @require scripts/html/keeper/keep_box.js
// @require scripts/html/keeper/keep_box_keep.js
// @require scripts/html/keeper/name_parts.js
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
// @require scripts/formatting.js
// @require scripts/listen.js
// @require scripts/title_from_url.js
// @require scripts/send_chooser.js
// @require scripts/progress.js

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
        k.formatting.formatLibraryResult({ showHintText: true}, lib);
      });
      show($parent, trigger, guided, data.libraries, data.organizations, data.me, data.experiments);
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
    },
    createLibrary: function (keep) {
      var deferred = Q.defer();
      var $slider = $('.kifi-root.kifi-keeper');
      api.port.emit('keeps_and_libraries_and_organizations_and_me_and_experiments', function (data) {
        show($slider, '', false, [], data.organizations, data.me, data.experiments);
        var el = document.createElement('div');
        chooseLibrary(el, null, false, keep.id);
        deferred.resolve();
      });

      return deferred.promise;
    }
  };

  function decorateLocationRecentLibraryCount(libraries, me, organizations) {
    // Add a 'recentLibCount' property to each Location with the
    // number of recently kept libraries belonging to that space.
    me.recentLibCount = 0;
    organizations.forEach(function (o) {
      o.recentLibCount = 0;
    });
    libraries
    .filter(propertyIs('recent', true))
    .forEach(function (library) {
      var location;
      if (library.orgAvatar) {
        location = organizations.filter(propertyIs('avatarPath', library.orgAvatar))[0]; // TODO(carlos): it's probably a bad idea to key on the avatar
      } else {
        location = me;
      }

      if (location) {
        location.recentLibCount = location.recentLibCount +  1;
      }
    });

    // Sort descending to put the most recently kept org on top
    organizations.sort(function byRecentLibCountDescending(a, b) {
      return b.recentLibCount - a.recentLibCount;
    });
  }

  function show($parent, trigger, guided, libraries, organizations, me, experiments) {
    log('[keepBox:show]', trigger, guided ? 'guided' : '');
    var params = partitionLibs(libraries);
    params.guided = guided; // We hide create library in guided mode

    decorateLocationRecentLibraryCount(libraries, me, organizations);

    $box = $(k.render('html/keeper/keep_box', params, {
      view: 'keep_box_libs',
      name_parts: 'name_parts',
      keep_box_lib: 'keep_box_lib',
      keep_box_libs_list: 'keep_box_libs_list'
    }))
    .on('mousedown', function (e) {
      var $target = $(e.target);
      if (e.which === 1 && !$target.is('input[type=text],div[contenteditable],div[contenteditable] *')) {
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
    .data('libraries', libraries)
    .data('organizations', organizations)
    .data('me', me)
    .data('experiments', experiments);

    addLibrariesBindings($box.find('.kifi-keep-box-view-libs'));

    $(document).data('esc').add(hide);

    api.port.emit('track_pane_view', {type: 'libraryChooser', subsource: trigger, guided: guided});

    $box.layout()
    .on('transitionend', onShown)
    .removeClass('kifi-down');
  }

  function onShown(e) {
    if (e.target === this && e.originalEvent.propertyName === 'opacity') {
      log('[keepBox:onShown]');
      $box.off('transitionend', onShown);
      $box.find('input[type=text]').first().focus();
      makeScrollable($box);
      var deferred = Q.defer();
      $box.data({imagePromise: deferred.promise, imagePromisedAt: Date.now()});
      setTimeout(function () {
        findPageImages($box.data(), deferred);
      }, 10);
    }
  }

  var hideInterval;
  function hide(e, trigger) {
    clearInterval(hideInterval), hideInterval = null;
    trigger = trigger || (e && e.keyCode === 27 ? 'esc' : undefined);
    var doneWithKeeper = /^(?:x|esc|clickout|timer|enter|button|silence|history)$/.test(trigger);
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
    k.keepBox.onHide.dispatch(doneWithKeeper);

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

  function getClassSelector($element) {
    // add a dot in the beginning and replace spaces with dots
    return $element.attr('class').replace(/(^.)|\s/g, '.$1');
  }

  function swipeTo($new, back) {
    var $vp = $box.find('.kifi-keep-box-viewport');
    var $cart = $vp.find('.kifi-keep-box-cart');
    var $old = $cart.find('.kifi-keep-box-view').first();
    $old.triggerHandler('kifi-hide');

    var vpHeightOld = $vp[0].offsetHeight;
    $vp.css('height', vpHeightOld);

    $cart.addClass(back ? 'kifi-back' : 'kifi-forward');
    if ($old.parent().find(getClassSelector($new)).length > 0) {
      return;
    }
    $new[back ? 'prependTo' : 'appendTo']($cart);
    makeScrollable($new);

    var isLibList = $new.hasClass('kifi-keep-box-view-libs');
    $box.find('.kifi-keep-box-back').toggleClass('kifi-hidden', isLibList);
    var $title = $box.find('.kifi-keep-box-title').first().on('transitionend', removeThis);
    $title.clone().text($new.data('boxTitle')).css('opacity', 0).insertAfter($title).layout().css('opacity', '');

    var heightDelta = $new[0].offsetHeight - $old[0].offsetHeight;

    $cart.addClass('kifi-animated').layout().addClass('kifi-roll')
    .on('transitionend', function end(e) {
      if (e.target === this && e.originalEvent.propertyName === 'transform') {
        if (!back) {
          $cart.removeClass('kifi-animated kifi-back kifi-forward');
        }
        $old.remove();
        $cart.removeClass('kifi-roll kifi-animated kifi-back kifi-forward')
          .off('transitionend', end);
        $vp.css('height', '');
        $new.find('input[type=text],div[contenteditable]').last().focus().select();
        $new.triggerHandler('kifi-show');
      }
    });

    $vp.css('height', vpHeightOld + heightDelta);
  }

  function navBack(trigger, guided) {
    var $view, $oldView = $box.find('.kifi-keep-box-view');
    var data = $box.data(), lib = data.libraryCreated, keep = data.recipientKeep;
    if (keep) {
      hide();
    } else if (lib) {
      data.libraries = data.libraries.filter(idIsNot(lib.id));
      delete data.libraryCreated;
      api.port.emit('delete_library', lib.id);
      $view = $(k.render('html/keeper/keep_box_new_lib', {
        name: name,
        organizations: $box.data('organizations'),
        me: $box.data('me'),
        visibility: 'published'
      }));
      addCreateLibraryBindings($view);
      swipeTo($view, true);
      selectDefaultLocationAndPrivacy($view);
    } else {
      $view = $(k.render('html/keeper/keep_box_libs', partitionLibs(data.libraries), {
        name_parts: 'name_parts',
        keep_box_lib: 'keep_box_lib',
        keep_box_libs_list: 'keep_box_libs_list'
      }));
      addLibrariesBindings($view);
      swipeTo($view, true);
    }

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
    (inLibs[0] || recentLibs[0] || otherLibs[0] || {}).highlighted = true;
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
              libs.forEach(k.formatting.formatLibraryResult.bind(null, {showHintText: true}));
              $box.data('filter_libraries', libs);
              (libs[0] || {}).highlighted = true;
              showLibs($(k.render('html/keeper/keep_box_libs_list', {query: q, libs: libs.map(annotateNameParts)}, {
                name_parts: 'name_parts',
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
            name_parts: 'name_parts',
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
            highlightItem($items[index], '.kifi-keep-box-view-libs');
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
        highlightItem(this, '.kifi-keep-box-view-libs');
      }
    })
    .on('mousemove', '.kifi-keep-box-lib', $.proxy(function (data) {
      if (!data.mouseMoved) {
        data.mouseMoved = true;
        highlightItem(this, '.kifi-keep-box-view-libs');
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

  function addKeepBindings($view, libraryId, keepId, note, autoClose, sendChooser) {
    var debouncedSaveKeepTitleIfChanged = _.debounce(saveKeepTitleIfChanged, 1500);
    var debouncedSaveKeepImageIfChanged = _.debounce(saveKeepImageIfChanged, 2400);
    var debouncedSaveKeepNoteIfChanged = _.debounce(saveKeepNoteIfChanged, 2400);
    $view
    .on('input', '.kifi-keep-box-keep-title', $.proxy(debouncedSaveKeepTitleIfChanged, this, $view))
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
    .on('input', '.kifi-keep-box-keep-note', $.proxy(debouncedSaveKeepNoteIfChanged, null, $view))
    .on('click', '.kifi-keep-box-done', function (e) {
      if (e.which === 1) {
        hide(e, 'button');
      }
    })
    .on('keydown', function (e) {
      if ((e.keyCode === 13 || e.keyCode === 108) && !e.shiftKey && !e.altKey && !sendChooser.enterToSend === (e.metaKey || e.ctrlKey)  && !e.isDefaultPrevented()) { // enter, numpad enter
        hide(e, 'enter');
        e.preventDefault();
      } else if (e.keyCode === 8 && !e.isDefaultPrevented() && !e.target.contentEditable &&
        (e.target.type !== 'text' || !e.target.selectionStart && !e.target.selectionEnd)) {
        navBack('key', e.originalEvent.guided);
        e.preventDefault();
      }
    })
    .on('kifi-show', function () {
      k.keepNote.moveCursorToEnd($note);
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
        var abort = function () {
          $box.off('mouseover keydown', abort);
          $view.off('kifi-hide', abort);
          clearInterval(hideInterval), hideInterval = null;
          $timer.remove();
        };
        $box.on('mouseover keydown', abort);
        $view.on('kifi-hide', abort);
      }
    })
    .on('kifi-hide', function () {
      saveKeepTitleIfChanged($view);
      saveKeepImageIfChanged($view);
      saveKeepNoteIfChanged($view);
      k.keepNote.done($note);
    });

    var $note = $view.find('.kifi-keep-box-keep-note');
    k.keepNote.init($note, $view, keepId, note);

    api.require('scripts/repair_inputs.js', function () {
      $view.repairInputs();
    });
  }

  function selectDefaultLocationAndPrivacy($view) {
    var me = $box.data('me');
    var organizations = $box.data('organizations');
    var showOrgAsDefault = (organizations.length > 0 && organizations[0].recentLibCount >= me.recentLibCount);

    if (showOrgAsDefault) {
       // select the first (most recently kept-to) org
      $view.find('li:nth-child(2) [name="kifi-location"]').click();
      $view.find('[name="kifi-visibility"][value="organization"]').click();
    } else {
      // select the personal location
      $view.find('li:first-child [name="kifi-location"]').click();
      $view.find('[name="kifi-visibility"][value="published"]').click();
    }

  }

  function addCreateLibraryBindings($view) {
    $view
    .on('keydown', function (e) {
      if ((e.keyCode === 13 || e.keyCode === 108) && !e.isDefaultPrevented() && e.originalEvent.isTrusted !== false) { // enter, numpad enter
        createLibrary($view, 'enter', e.originalEvent.guided);
        e.preventDefault();
      } else if (e.keyCode === 8 && !e.isDefaultPrevented() && (e.target.type !== 'text' || !e.target.selectionStart && !e.target.selectionEnd)) {
        if ($box.data('recipientKeep')) {
          hide();
        } else {
          navBack('key', e.originalEvent.guided);
        }
        e.preventDefault();
      }
    })
    .on('blur', '.kifi-keep-box-new-lib-name', function (e) {
      this.value = this.value.trim();
    })
    .on('change', '[name="kifi-location"]', setLocation)
    .on('keydown', '[name="kifi-location"]', function (e) {
      if (e.keyCode === 32 && !e.isDefaultPrevented() && e.originalEvent.isTrusted !== false) {
        setLocation.call(this, e);
      }
    })
    .on('change', '[name="kifi-visibility"]', setVisibility)
    .on('keydown', '[name="kifi-visibility"]', function (e) {
      if (e.keyCode === 32 && !e.isDefaultPrevented() && e.originalEvent.isTrusted !== false) {
        setVisibility.call(this, e);
      }
    })
    .on('click', '.kifi-keep-box-new-lib-visibility-item-disabled.kifi-keep-box-new-lib-visibility-item-no-orgs', function () {
      window.open('https://www.kifi.com/teams');
    })
    .on('click', '.kifi-keep-box-new-lib-visibility-item-disabled.kifi-keep-box-new-lib-visibility-item-link-to-create-team', function () {
      window.open('https://www.kifi.com/teams/new');
    });

    var experiments = $box.data('experiments');
    var organizations = $box.data('organizations');

    $view.hoverfu('.kifi-keep-box-new-lib-visibility-item-disabled', function (configureHover) {
      var $this = $(this);
      var title;
      var message;

      $this
      .removeClass('kifi-keep-box-new-lib-visibility-item-no-orgs')
      .removeClass('kifi-keep-box-new-lib-visibility-item-link-to-create-team');

      if (organizations.length === 0) {
        $this.addClass('kifi-keep-box-new-lib-visibility-item-linked');

        title = 'Kifi for teams';
        message = 'Curious? Click to form a team<br />and create team visible libraries.';
        $this.addClass('kifi-keep-box-new-lib-visibility-item-link-to-create-team');
      } else if ($this.find('[disabled][value="published"]').length === 1) {
        var teamId = $box.find('[name="kifi-location"]:checked').val();
        var team = $box.data('organizations').filter(idIs(teamId)).pop();
        var teamName = (team && team.name) || 'Your team';

        title = 'Not applicable';
        message = teamName + ' has disabled creation of<br />publicly visible libraries.';
      } else if ($this.find('[disabled][value="organization"]').length === 1) {
        $this
        .addClass('kifi-keep-box-new-lib-visibility-item-linked')
        .addClass('kifi-keep-box-new-lib-visibility-item-link-to-create-team');

        title = 'Select or create a team';
        message = 'Click to form a team and<br />create team visible libraries.';
      } else {
        return;
      }

      k.render('html/keeper/titled_tip', {
        dir: 'above',
        cssClass: 'kifi-keep-box-tip',
        title: title,
        html: k.formatting.jsonDom(message)
      }, {
        'kifi_mustache_tags': 'kifi_mustache_tags'
      }, function (html) {
        configureHover(html, {
          mustHoverFor: 300, hideAfter: 0,
          position: {my: 'center bottom-4px', at: 'top', of: $this, collision: 'none'}
        });
      });
    });

    var $submit = $view.find('.kifi-keep-box-new-lib-create')
    .on('click', function (e) {
      if (e.which === 1 && this.href) {
        createLibrary($view, 'mouse', e.originalEvent.guided);
      }
    });
  }

  function toggleVisibilityItem($visibilityItem, value) {
    value = !!value;
    if (typeof $visibilityItem === 'string') {
      $visibilityItem = $box.find('[name="kifi-visibility"][value="' + $visibilityItem + '"]');
    }
    $visibilityItem
    .prop('disabled', value)
    .closest('.kifi-keep-box-new-lib-visibility-item')
    .toggleClass('kifi-keep-box-new-lib-visibility-item-disabled', value);
  }

  function setLocation(e) {
    e.preventDefault();
    var newLocation = e.target.value;
    var organizations = $box.data('organizations');
    var matches = organizations.filter(idIs(newLocation));
    var isOrganization = (matches.length !== 0);
    var canPublish = !isOrganization || matches[0].viewer.permissions.indexOf('publish_libraries') !== -1;

    var visibility = $box.find('[name="kifi-visibility"]:checked').val();
    var previousLocationId = $box.find('.kifi-selected [name="kifi-location"]').val();
    var previousLocationIsOrg = !!organizations.filter(idIs(previousLocationId)).length;

    var el = $(this).closest('.kifi-keep-box-new-lib-location-item')[0];
    selectItem(el, '.kifi-keep-box-new-lib-locations');

    if (isOrganization) {
      if (visibility === 'published' && !previousLocationIsOrg) {
        var $orgVisiblility = $box.find('[name="kifi-visibility"][value="organization"]');
        toggleVisibilityItem($orgVisiblility, false);
        $orgVisiblility.focus().click();
      }
      $box.find('.kifi-organization-name').empty().append(document.createTextNode(matches[0].name));
    } else if ($box.find('[name="kifi-visibility"][value="organization"]:checked').length === 1) {
      $box.find('[name="kifi-visibility"][value="secret"]').focus().click();
    }
    toggleVisibilityItem('organization', !isOrganization);

    if (!canPublish && $box.find('[name="kifi-visibility"][value="published"]:checked').length === 1) {
      $box.find('[name="kifi-visibility"][value="organization"]').focus().click();
    }
    toggleVisibilityItem('published', !canPublish);

    api.port.emit('track_pane_click', {
      type: 'createLibrary',
      action: 'changedLocation',
      subaction: newLocation,
      guided: e.originalEvent.guided
    });
  }

  function setVisibility(e) {
    e.preventDefault();
    var newVisibility = e.target.value;

    // Show the selected description and hide the rest
    $box.find('.kifi-keep-box-new-lib-visibility-description').css('display', function () {
      var $this = $(this);
      if (!$this.hasClass('kifi-' + newVisibility)) {
        return 'none';
      } else {
        return 'block';
      }
    });

    api.port.emit('track_pane_click', {
      type: 'createLibrary',
      action: 'changedVisibility',
      subaction: newVisibility,
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

  function highlightItem(el, container) {
    $(el).closest(container).find('.kifi-highlighted').removeClass('kifi-highlighted');
    el.classList.add('kifi-highlighted');
  }

  function selectItem(el, container) {
    $(el).closest(container).find('.kifi-selected').removeClass('kifi-selected');
    el.classList.add('kifi-selected');
  }

  function chooseLibrary(el, trigger, guided, keepId) {
    var libraryId = el && el.dataset.id;
    if (libraryId) {
      var library = $box.data('libraries').find(idIs(libraryId)) || $box.data('filter_libraries').find(idIs(libraryId));
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
        k.progress.show(el, tryKeepTo(library, guided)).done(function (keep) {
          showKeep(library, subsource, trigger, guided, true);
        });
      }
    } else {
      var name = $(el).find('.kifi-keep-box-lib-1').text();
      var $view = $(k.render('html/keeper/keep_box_new_lib', {
        name: name,
        organizations: $box.data('organizations'),
        me: $box.data('me'),
        keepId: keepId
      }));
      $box.data('recipientKeep', keepId);
      addCreateLibraryBindings($view);
      swipeTo($view);
      selectDefaultLocationAndPrivacy($view);
      api.port.emit('track_pane_click', {type: 'libraryChooser', action: 'choseCreateLibrary', guided: guided});
      api.port.emit('track_pane_view', {type: 'createLibrary', subsource: trigger === 'enter' ? 'key' : undefined, guided: guided});
    }
  }

  function tryKeepTo(library, guided) {
    if (library.isOpenCollaborationCandidate) {
      // we need to join open collaboration libraries before we can keep to them
      return joinAndKeepTo(library, guided);
    } else {
      return keepTo(library, guided);
    }
  }

  function joinAndKeepTo(library, guided) {
    var deferred = Q.defer();

    api.port.emit('follow_library', library.id, function () {
      keepTo(library, guided)
      .then(function (keep) {
        library.isOpenCollaborationCandidate = false;
        deferred.resolve(keep);
      })
      ['catch'](deferred.reject);
    });

    return deferred.promise;
  }

  function keepTo(library, guided) {
    var data = {
      libraryId: library.id,
      guided: guided
    };
    log('[keep]', data);
    var deferred = Q.defer();

    api.port.emit('keep', withTitles(withUrls(data)), function (keep) {
      if (keep) {
        library.keep = keep;
        deferred.resolve(keep);
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
    k.progress.show($kept, deferred.promise).done(function () {
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
    var images = [];  // for this keep
    if (!justKept && library.keep.image) {
      var img = new Image;
      img.src = library.keep.image;
      images.push(img);
    }

    var requireDeferred = Q.defer();
    api.require('scripts/keep_note.js', function () {
      requireDeferred.resolve();
    });

    var data = $box.data();
    Q.allSettled([data.imagePromise, requireDeferred.promise]).done(proceed, proceed);
    function proceed() {
      images.push.apply(images, data.images);
      showKeep2(library, subsource, trigger, guided, justKept, justKept && !data.tip, images);
    }

    if (data.imagePromise.isPending()) {
      var promiseAgeMs = Date.now() - data.imagePromisedAt;
      if (promiseAgeMs < 1000 && images.length === 0) {   // let image search run for at least 1s
        setTimeout(function () { data.imagesNeeded = true; }, 1000 - promiseAgeMs);
      } else {
        data.imagesNeeded = true;
      }
    }
  }

  function showKeep2(library, subsource, trigger, guided, justKept, autoClose, images) {
    var keep = library.keep;
    var title = keep.title || formatTitleFromUrl(document.URL);
    var showImage = justKept ? images.length : keep.image;
    var canvases = showImage ? [newKeepCanvas(images[0])] : [];   // TODO: show spinner while this image is loading
    var $view = $(k.render('html/keeper/keep_box_keep', {
      library: library,
      title: title,
      site: document.location.hostname,
      hasImages: images.length > 0,
      autoClose: autoClose
    }, {
      name_parts: 'name_parts',
      keep_box_lib: 'keep_box_lib'
    }));
    $view.find('.kifi-keep-box-keep-image-cart').append(canvases[0] || newNoImage());
    if (images.length === 0) {
      $view.find('.kifi-keep-box-keep-image-picker').addClass('kifi-empty');
    }

    var sendChooser = k.sendChooser($view.find('.kifi-keep-box-send-chooser'));

    var imageIdx = showImage ? 0 : -1;
    $view.data({
      library: library,
      imageIdx: imageIdx,
      images: images,
      canvases: canvases,
      saved: {
        title: keep.title,
        imageIdx: keep.image ? imageIdx : -1,
        note: keep.note
      },
      saving: {}
    });
    if (justKept && showImage) {
      saveKeepImageIfChanged($view);
    }

    addKeepBindings($view, library.id, keep.id, keep.note || '', autoClose, sendChooser);

    api.port.emit('track_pane_view', {
      type: 'keepDetails',
      subsource: subsource,
      key: {key: 'shortcut', enter: 'enter'}[trigger],
      guided: guided
    });

    swipeTo($view);
  }

  // Finds the best images (IMG elements) possible before a deadline. To signal the deadline,
  // set $box.data('imagesNeeded'). $box.data('imagePromise') will be fulfilled with the best image
  // found or fail if no suitable images are found. Additional images might be appended to the array
  // after the promise is fulfilled.
  function findPageImages(data, deferred) {
    var numLoading = 0, numOgImages = 0;
    var candidatesByUrl = {};  // url => {img, score}

    // 1. og:image <meta> elements
    var head = document.head;
    Array.prototype.forEach.call(head ? head.querySelectorAll('meta[property="og:image"]') : [], function (el, i) {
      var url = el.content;
      if (imageUrlQualifies(url) && !candidatesByUrl[url]) {
        var img = new Image;
        listenToImg(url, img, null, numOgImages++);
        img.src = url;
      }
    });

    // 2. <img> elements
    Array.prototype.forEach.call(document.getElementsByTagName('img'), function (img) {
      var url = getSrc(img);
      if (imageUrlQualifies(url) && !candidatesByUrl[url] && !isKifiEl(img)) {
        if (img.complete) {
          var score = scoreImage(img, img);
          if (score > 0) {
            candidatesByUrl[url] = {img: img, score: score};
          }
        } else {
          listenToImg(url, img, img);
        }
      }
    });

    // 3. background images
    var bgImgUrls = Array.prototype.reduce.call(
      document.querySelectorAll('[style*="url("]'),
      appendBgImagesInline.bind(null, resolveUriRelativeToThis.bind(document.baseURI)), []);
    Array.prototype.reduce.call(document.styleSheets, appendBgImagesInStylesheet, bgImgUrls);
    bgImgUrls.forEach(function (urlAndEl) {
      var url = urlAndEl[0];
      var el = urlAndEl[1];
      if (numLoading < 100 && imageUrlQualifies(url) && !candidatesByUrl[url] && !isKifiEl(el)) {
        var img = new Image;
        listenToImg(url, img, el);
        img.src = url;
      }
    });

    if (numLoading === 0 || data.imagesNeeded) {
      rankAndResolve();
    }

    function listenToImg(url, img, elemInDoc, ogIdx) {
      $(img).on('load error', $.proxy(onLoadEnd, img, url, elemInDoc, ogIdx));
      candidatesByUrl[url] = {img: img};
      numLoading++;
    }

    function onLoadEnd(url, elemInDoc, ogIdx, e) {
      $(this).off('load error', onLoadEnd);
      var score = scoreImage(this, elemInDoc, ogIdx);
      if (score > 0) {
        if (deferred.promise.isPending()) {
          candidatesByUrl[url].score = score;
        } else if (deferred.promise.isFulfilled()) {
          data.images.push(this);
        }
      } else if (candidatesByUrl) {
        delete candidatesByUrl[url];  // free image memory
      }
      if ((--numLoading === 0 || data.imagesNeeded) && deferred.promise.isPending()) {
        rankAndResolve();
      }
    }

    function rankAndResolve() {
      var candidates = [];
      for (var url in candidatesByUrl) {
        var cand = candidatesByUrl[url];
        if (cand.score > 0) {
          candidates.push(cand);
        }
      }
      if (candidates.length) {
        candidates.sort(function byScore(a, b) { return b.score - a.score; });
        data.images = candidates.map(function (cand) { return cand.img; });
        deferred.fulfill(data.images[0]);
      } else {
        deferred.reject();
      }
      candidatesByUrl = null;  // free image memory
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

  function appendBgImagesInline(resolveUri, arr, el) {
    var uris = parseCssUris(el.style.backgroundImage).filter(bgUriLooksInteresting);
    if (uris.length) {
      arr.push.apply(arr, uris.map(resolveUri).map(pairWith(el)));
    }
    return arr;
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
      var sel = rule.selectorText;
      if (sel && sel.indexOf('kifi') < 0) {
        var uris = parseCssUris(bi).filter(bgUriLooksInteresting);
        if (uris.length) {
          try {
            var el = document.querySelector(sel);
            if (el) {
              arr.push.apply(arr, uris.map(resolveUri).map(pairWith(el)));
            }
          } catch (e) {
          }
        }
      }
    }
    return arr;
  }

  function parseCssUris(val) {  // e.g. 'background: #ccc url( "foo.png" )' -> ['foo.png']
    return (val.match(/url\(\s*([^"'].*?|".*?"|'.*?')\s*\)/g) || []).map(parseUriFromCssUrl);
  }

  function parseUriFromCssUrl(cssUrl) {  // e.g. 'url( "foo.png" )' -> 'foo.png'
    var arg = cssUrl.slice(4, -1).trim();
    var c0 = arg[0];
    return ~'"\''.indexOf(c0) && c0 === arg.slice(-1) ? arg.slice(1, -1) : arg;
  }

  function isKifiEl(el) {
    return el[matches]('.kifi-root,.kifi-root *');
  }

  function imageUrlQualifies(url) {
    return /^https?:\/\//.test(url) && !/\.svg($|\?)/.test(url);
  }

  function bgUriLooksInteresting(uri) {
    return !/^data:|sprite|icon|\.svg($|\?)/i.test(uri);
  }

  function resolveUriRelativeToThis(uri) {
    return new URL(uri, this).href;
  }

  function pairWith(y) {
    return function (x) { return [x, y]; };
  }

  function scoreImage(img, elemInDoc, ogIdx) {
    var nW = img.naturalWidth || 0;
    var nH = img.naturalHeight || 0;
    if (nW < 64 || nH < 64 || nW * nH < 12000) {  // TODO: exempt SVG images when supported
      return 0;
    }
    var r = elemInDoc ? elemInDoc.getBoundingClientRect() : {top: ogIdx * 10, left: 0, width: nW, height: nH};
    var w = Math.min(r.width, 600);
    var h = Math.min(r.height, 600);
    var A = w * h;
    if (w < 64 || h < 64 || A < 12000) {
      return 0;
    }
    return A * Math.pow(Math.max(r.top, r.left) + 1, -1.5);
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
      k.progress.emptyAndShow($view, deferred.promise);
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
      k.progress.emptyAndShow($view, deferred.promise);
    }
  }

  function saveKeepNoteIfChanged($view) {
    if (!$box) return;  // already removed, no data
    var noteEl = this || $view.find('.kifi-keep-box-keep-note')[0];
    var data = $view.data();
    var val = k.keepNote.toText(noteEl);
    if (val !== data['note' in data.saving ? 'saving' : 'saved'].note) {
      var deferred = Q.defer();
      data.saving.note = val;
      api.port.emit('save_keep_note', {libraryId: data.library.id, note: val}, function (success) {
        if (data.saving.note === val) {
          delete data.saving.note;
        }
        if (success) {
          data.saved.note = val;
          deferred.resolve();
        } else {
          deferred.reject();
        }
      });
      k.progress.emptyAndShow($view, deferred.promise);
    }
  }

  function createLibrary($view, trigger, guided) {
    var $btn = $view.find('.kifi-keep-box-new-lib-create');
    var $name = $view.find('.kifi-keep-box-new-lib-name');
    var $keepId = $view.find('.kifi-keep-box-new-lib-keep-id');
    var $vis = $view.find('.kifi-keep-box-new-lib-visibility');
    var name = $name.val().trim();
    var keepId = $keepId.val();
    if (!name) {
      showError('Please type a name for your new library');
    } else if (/[\/"]/.test(name)) {
      showError('No slashes or quotes, please');
    } else {
      $name.prop('disabled', true);
      $btn.removeAttr('href');
      var deferred = Q.defer();
      var promise = deferred.promise;
      var space = {};
      var selectedLocationId = $view.find('[type="radio"][name="kifi-location"]:checked').val();
      var organizations = $box.data('organizations');
      var matches = organizations.filter(idIs(selectedLocationId));
      var isOrganization = (matches.length !== 0);
      space[isOrganization ? 'org' : 'user'] = selectedLocationId;

      api.port.emit('create_library', {
        name: name,
        visibility: $view.find('[type="radio"][name="kifi-visibility"]:checked').val(),
        space: space
      }, deferred.resolve, deferred.reject);

      if (keepId) {
        promise = promise.then(function (library) {
          var deferred = Q.defer();
          if (library) {
            $box.data('libraryCreated', library);

            api.port.emit('update_keepscussion_recipients', {
              keepId: keepId,
              newLibraries: [ library.id ]
            }, deferred.resolve.bind(library), deferred.reject);
          } else {
            deferred.reject();
          }
          return deferred.promise;
        })
        .then(function () {
          k.keepBox.hide();
        });
      } else {
        promise = promise.then(function (library) {
          if (library) {
            $box.data('libraryCreated', library);
            $box.data('libraries').push(library);
            tryKeepTo(library, guided).done(function () {
              showKeep(library, 'libraryNew', trigger, guided, true);
              return Q.resolve();
            }, function () {
              // TODO: undo create library?
              return Q.reject();
            });
          } else {
            return Q.reject();
          }
        })
      }

      promise = promise.catch(function () {
        $name.prop('disabled', false).focus().select();
        $btn.prop('href', 'javascript:');
        showError('Hrm, maybe try a different name?');
      });

      k.progress.show($vis, promise);
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

  function annotateNameParts(lib) {
    lib.nameParts = lib.nameParts.map(function (part, i) {
      return {
        highlight: i % 2,
        part: part
      };
    });
    return lib;
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

  function propertyIs(prop, value) {
    return function (o) {return o[prop] === value};
  }

  function removeThis() {
    $(this).remove();
  }


}());
