// @require styles/insulate.css
// @require styles/keeper/tile.css
// @require styles/keeper/keeper.css
// @require styles/friend_card.css
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/keeper.js

$.fn.layout = function () {
  'use strict';
  return this.each(function () { /*jshint expr:true */
    this.clientHeight;  // forces layout
  });
};

var CO_KEY = /^Mac/.test(navigator.platform) ? '⌘' : 'Ctrl';

var keeper = keeper || function () {  // idempotent for Chrome
  'use strict';
  var $slider, lastShownAt;

  // We detect and handle the Esc key during keydown capture phase to try to beat page.
  // Subsequently loaded code should attach/detach Esc key handlers using
  // $(document).data('esc').add(handler) and .remove(handler).
  $(document).data('esc', function(arr) {
    arr.add = arr.push;
    arr.remove = function(f) {
      for (var i; ~(i = this.indexOf(f));) {
        this.splice(i, 1);
      }
    };
    return arr;
  }([]));
  document.addEventListener('keydown', onKeyDown, true);
  function onKeyDown(e) {
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
      var handlers = $(document).data('esc');
      for (var i = handlers.length; i--;) {
        if (handlers[i](e) === false) {
          return false;
        }
        if (e.defaultPrevented) {
          return;
        }
      }
      if ($slider) {
        hideSlider('esc');
        e.preventDefault();
        e.stopPropagation();
      }
    }
  }

  document.addEventListener('click', onClick, true);
  function onClick(e) {
    var target = e.target;
    if ($slider && !(paneShowing() || $slider[0].contains(target) || isInsideTagbox(target))) {
      hideSlider('clickout');
    }
  }

  api.onEnd.push(function () {
    log('[keeper:onEnd]')();
    $slider && $slider.remove();
    $(tile).remove();
    $('.kifi-root').remove();
    document.removeEventListener('keydown', onKeyDown, true);
    document.removeEventListener('click', onClick, true);
  });

  function paneShowing() {
    return window.pane && pane.showing();
  }

  function createSlider(locator) {
    var kept = tile && tile.dataset.kept;
    var count = +(tile && tile.dataset.count || 0);
    log('[createSlider] kept: %s count: %o', kept || 'no', count)();

    $slider = $(render('html/keeper/keeper', {
      'bgDir': api.url('images/keeper'),
      'isKept': kept,
      'isPrivate': kept === 'private',
      'boxCount': count,
      'boxOpen': /^\/box(?:#|$)/.test(locator),
      'isTagged': tags.length
    }));
      // TODO: unindent below
      // attach event bindings
      var data = $slider.data();
      $slider.mouseout(function (e) {
        if (data.dragTimer) {
          startDrag(data);
        } else if (!paneShowing() && !data.dragStarting && !data.$dragGlass) {
          if (e.relatedTarget) {
            if (!this.contains(e.relatedTarget)) {
              log('[slider.mouseout] hiding')();
              hideSlider('mouseout');
            }
          } else {  // out of window
            log('[slider.mouseout] out of window')();
            document.addEventListener('mouseover', function f(e) {
              this.removeEventListener('mouseover', f, true);
              log('[document.mouseover]', e.target)();
              if ($slider && !$slider[0].contains(e.target)) {
                hideSlider('mouseout');
              }
            }, true);
          }
        }
      }).mousedown(function (e) {
        if (e.which !== 1 || paneShowing() || $(e.target).closest('.kifi-tip').length) return;
        e.preventDefault();  // prevents selection and selection scrolling
        data.dragTimer = setTimeout(startDrag.bind(null, data), 900);
        data.mousedownEvent = e.originalEvent;
      }).mouseup(function () {
        if (data.dragTimer || data.dragStarting) {
          log('[mouseup]')();
          clearTimeout(data.dragTimer), delete data.dragTimer;
          delete data.dragStarting;
        }
        delete data.mousedownEvent;
      }).on('mousewheel', function (e) {
        e.preventDefault(); // crbug.com/151734
      }).on('click', '.kifi-keep-btn', function (e) {
        if (e.target === this) {
          keepPage('public');
          this.classList.add('kifi-hoverless');
        }
      }).on('click', '.kifi-kept-btn', function (e) {
        if (e.target === this) {
          unkeepPage();
          this.classList.add('kifi-hoverless');
        }
      }).on('mouseover', '.kifi-keep-card', function () {
        if ($slider.hasClass('kifi-auto')) {
          growSlider('kifi-auto', 'kifi-wide');
        }
      }).on('mouseover', '.kifi-keep-btn>.kifi-tip,.kifi-kept-btn>.kifi-tip', function () {
        this.parentNode.classList.add('kifi-hoverless');
      }).hoverfu('.kifi-keep-btn,.kifi-kept-btn', function (configureHover) {
        var btn = this;
        api.port.emit('get_keepers', function (o) {
          if (o.keepers.length) {
            render('html/keeper/keepers', {
              tipClass: 'kifi-keepers-tip',
              keepers: pick(o.keepers, 8),
              linkKeepers: true,
              captionHtml: formatCountHtml(o.kept, o.keepers.length, o.otherKeeps),
              includeTri: true
            }, function (html) {
              configureHover(hoverfuFriends($(html), o.keepers), {
                mustHoverFor: 700,
                canLeaveFor: 800,
                hideAfter: 4000,
                click: 'hide',
                parent: btn,
                position: {my: 'center bottom-13', at: 'center top', of: btn, collision: 'fit', using: function (pos, o) {
                  var dw = o.element.width - o.target.width;
                  if (dw > 0) {
                    pos.left = 'auto';
                    pos.right = 0;
                    o.element.element.find('.kifi-tip-tri')
                      .css({left: 'auto', right: Math.round(.5 * o.target.width)});
                  } else {
                    pos.left -= o.target.left;
                  }
                  pos.top = 'auto';
                  pos.bottom = '100%';
                  o.element.element.css(pos);
                }}
              });
            });
          } else {
            render('html/keeper/titled_tip', {
              title: (o.kept ? 'Unkeep' : 'Keep') + ' (' + CO_KEY + '+Shift+K)',
              html: o.kept ? 'Un-keeping this page will<br>remove it from your keeps.' :
                'Keeping this page helps you<br>easily find it later.'
            }, function (html) {
              configureHover(html, {
                mustHoverFor: 700,
                hideAfter: 4000,
                click: 'hide',
                position: {my: 'center bottom-13', at: 'center top', of: btn, collision: 'none'}});
            });
          }
        });
      }).on('mouseout', '.kifi-keep-btn,.kifi-kept-btn', function () {
        this.classList.remove('kifi-hoverless');
      }).hoverfu('.kifi-keep-lock,.kifi-kept-lock', function (configureHover) {
        var $a = $(this);
        var $card = $(this).closest('.kifi-keep-card');
        var kept = !$card.hasClass('kifi-unkept');
        var publicly = kept && $card.hasClass('kifi-public');
        var title = !kept ?
          'Keep Privately' : publicly ?
          'Make Private' :
          'Make Public';
        var html = !kept ?
          'Keeping this privately allows you<br>to find this page easily without<br>letting anyone know you kept it.' : publicly ?
          'This keep is public. Making it private<br>allows you to find it easily without<br>letting anyone know you kept it.' :
          'This keep is private. Making it<br>public allows your friends to<br>discover that you kept it.';
        render('html/keeper/titled_tip', {title: title, html: html}, function (html) {
          configureHover(html, {
            mustHoverFor: 700,
            hideAfter: 4000,
            click: 'hide',
            position: {my: 'center bottom-13', at: 'center top', of: $a, collision: 'none'}});
        });
      }).on('click', '.kifi-keep-lock', function (e) {
        if (e.target === this) keepPage('private');
      }).on('click', '.kifi-kept-lock', function (e) {
        if (e.target === this) toggleKeep($(this).closest('.kifi-keep-card').hasClass('kifi-public') ? 'private' : 'public');
      }).hoverfu('.kifi-keep-tag,.kifi-kept-tag', function (configureHover) {
        var btn = this;
        var kept = this.classList.contains('kifi-kept-tag');
        render('html/keeper/titled_tip', {
          cssClass: 'kifi-tag-tip',
          title: 'Tags', //'Tags (' + CO_KEY + '+Shift+A)', TODO: key binding
          html: 'You can tag a keep to<br>make it easier to find.'
        }, function (html) {
          configureHover(html, {
            mustHoverFor: 700,
            hideAfter: 4000,
            click: 'hide',
            position: {my: 'right bottom-13', at: 'right top', of: btn, collision: 'none'}
          });
        });
      }).on('click', '.kifi-keep-tag,.kifi-kept-tag', function (e) {
        if (e.originalEvent.tagboxClosed) {
          log('[tagbox:closed] ignore click event')();
          return;
        }

        if (this.classList.contains('kifi-keep-tag')) {
          keepPage('public');
        }

        api.require('scripts/tagbox.js', function () {
          log('require:tagbox')();
          tagbox.toggle($slider, 'click:tagIcon');
        });
      }).hoverfu('.kifi-keeper-x', function (configureHover) {
        configureHover({
          mustHoverFor: 700, hideAfter: 2500, click: 'hide',
          position: {my: 'right bottom-13', at: 'right top', of: this, collision: 'none'}
        });
      }).on('click', '.kifi-keeper-x', function () {
        pane.hide(true);
      }).hoverfu('.kifi-dock-btn', function(configureHover) {
        var $a = $(this);
        var tip = {
          n: ['Notifications (' + CO_KEY + '+Shift+O)', 'View all of your notifications.<br>Any new ones are highlighted.'],
          m: ['Private Messages (' + CO_KEY + '+Shift+M)', 'Send this page to friends<br>and start a discussion.'],
          i: ['Message Box (' + CO_KEY + '+Shift+M)', 'View all of your messages.<br>New ones are highlighted.'],
          c: ['Compose (' + CO_KEY + '+Shift+S)', 'Send this page to friends<br>and start a discussion.']
        }[this.dataset.tip];
        render('html/keeper/titled_tip', {title: tip[0], html: tip[1]}, function (html) {
          var px = $a.find('.kifi-count').text() > 0 ? 24 : 13;
          configureHover(html, {
            mustHoverFor: 700,
            hideAfter: 4000,
            click: 'hide',
            position: {my: 'center bottom-' + px, at: 'center top', of: $a, collision: 'none'}
          });
        });
      }).on('mousedown', '.kifi-dock-btn', function (e) {
        e.preventDefault();
      }).on('click', '.kifi-dock-btn', function () {
        var locator = this.dataset.loc;
        api.require('scripts/pane.js', function () {
          if (locator) {
            pane.toggle('keeper', locator);
          } else {
            pane.compose('keeper');
          }
        });
      });
  }

  function showSlider(trigger) {
    log('[showSlider]', trigger)();
    lastShownAt = Date.now();

    createSlider();
    $slider.prependTo(tile);

    api.port.emit('log_event', ['slider', 'sliderShown', withUrls({trigger: trigger, onPageMs: String(lastShownAt - tile.dataset.t0)})]);
    api.port.emit('keeper_shown');
  }

  function growSlider(fromClass, toClass) {
    $slider.addClass(fromClass).layout().addClass(toClass + ' kifi-growing').removeClass(fromClass)
    .on('transitionend', function f(e) {
      if (e.target === this) {
        $(this).off('transitionend', f).removeClass('kifi-growing');
      }
    });
  }

  function isTagboxActive() {
    var tagbox = window.tagbox;
    return tagbox && tagbox.active;
  }

  function isInsideTagbox(el) {
    var tagbox = window.tagbox;
    return tagbox && tagbox.contains(el);
  }

  // trigger is for the event log (e.g. 'key', 'icon')
  function hideSlider(trigger) {
    log('[hideSlider]', trigger)();
    if (trigger !== 'clickout' && isTagboxActive()) {
      log('[hideSlider] tagbox is active. cancel hide')();
      return;
    }
    idleTimer.kill();
    $slider.addClass('kifi-hiding')
    .off('transitionend')
    .on('transitionend', function (e) {
      if (e.target === this && e.originalEvent.propertyName === 'opacity') {
        var css = JSON.parse(tile.dataset.pos || 0);
        if (css && !tile.style.top && !tile.style.bottom) {
          var y = css.top >= 0 ? window.innerHeight - css.top - 54 : (css.bottom || 0);
          css.transition = 'none';
          css.transform = 'translate(0,' + y + 'px)';
          $(tile).css(css)
          .layout().css({transition: '', 'transition-duration': Math.min(1, 32 * Math.log(y)) + 'ms'})
          .find('.kifi-count').css('zoom', 1); // webkit repaint workaround
          tile['kifi:position']();
          $(tile).on('transitionend', function end() {
            $(this).off('transitionend', end).css('transition-duration', '');
          });
        }
        $slider.remove(), $slider = null;
      }
    });
  }

  function startDrag(data) {
    log('[startDrag]')();
    clearTimeout(data.dragTimer);
    delete data.dragTimer;
    data.dragStarting = true;
    api.require('scripts/lib/jquery-ui-draggable.min.js', function () {
      if (data.dragStarting) {
        delete data.dragStarting;
        log('[startDrag] installing draggable')();
        data.$dragGlass = $('<div class=kifi-drag-glass>').mouseup(stopDrag).appendTo(tile.parentNode);
        $(tile).draggable({axis: 'y', containment: 'window', scroll: false, stop: stopDrag})[0]
          .dispatchEvent(data.mousedownEvent); // starts drag
      }
      function stopDrag() {
        var r = tile.getBoundingClientRect(), fromBot = window.innerHeight - r.bottom;
        var pos = r.top >= 0 && r.top < fromBot ? {top: r.top} : {bottom: Math.max(0, fromBot)};
        log('[stopDrag] top:', r.top, 'bot:', r.bottom, JSON.stringify(pos))();
        $(tile).draggable('destroy');
        data.$dragGlass.remove();
        delete data.$dragGlass;
        tile.dataset.pos = JSON.stringify(pos);
        $(tile).css($.extend({top: 'auto', bottom: 'auto'}, pos));
        api.port.emit('set_keeper_pos', {host: location.hostname, pos: pos});
      }
    });
  }

  var idleTimer = {
    start: function (ms) {
      log('[idleTimer.start]', ms, 'ms')();
      clearTimeout(this.timeout), this.timeout = setTimeout(hideSlider.bind(null, 'idle'), ms);
      $slider.on('mouseenter.idle', $.proxy(this, 'kill'));
    },
    kill: function () {
      if (this.timeout) {
        log('[idleTimer.kill]')();
        clearTimeout(this.timeout), delete this.timeout;
        $slider && $slider.off('.idle');
      }
    }};

  function keepPage(how) {
    log('[keepPage]', how)();
    updateKeptDom(how);
    api.port.emit('keep', withUrls({title: document.title, how: how}));
  }

  function unkeepPage() {
    log('[unkeepPage]', document.URL)();
    updateKeptDom('');
    api.port.emit('unkeep', withUrls({}));
  }

  function toggleKeep(how) {
    log('[toggleKeep]', how)();
    updateKeptDom(how);
    api.port.emit('set_private', withUrls({private: how == 'private'}));
  }

  function updateKeptDom(how) {
    if ($slider) {
      $slider.find('.kifi-keep-card').removeClass('kifi-unkept kifi-private kifi-public').addClass('kifi-' + (how || 'unkept'));
    }
  }

  function hoverfuFriends($tip, keepers) {
    return $tip.hoverfu('.kifi-keepers-keeper', function (configureHover) {
      var $pic = $(this);
      var friend = keepers.filter(hasId($pic.data('id')))[0];
      render('html/friend_card', {
        friend: friend,
        iconsUrl: api.url('images/social_icons.png'),
        includeTri: true
      }, function (html) {
        var $card = $(html);
        configureHover($card, {
          mustHoverFor: 100, canLeaveFor: 600, hideAfter: 4000, click: 'toggle', parent: $pic,
          position: {my: 'center bottom-13', at: 'center top', of: $pic, collision: 'fit', using: function (pos, o) {
            var xTC = o.target.left + .5 * o.target.width;
            var xEC = o.element.left + .5 * o.element.width;
            if (xTC - xEC >= 1) {
              var pad = 6;
              pos.left -= pad;
              o.element.element.find('.kifi-kcard-tri').css('left', xTC - (o.element.left - pad));
            }
            o.element.element.css(pos);
          }}});
        api.port.emit('get_networks', friend.id, function (networks) {
          for (nw in networks) {
            $card.find('.kifi-kcard-nw-' + nw)
              .toggleClass('kifi-on', networks[nw].connected)
              .attr('href', networks[nw].profileUrl || null);
          }
        });
      });
    });
  }

  function formatCountHtml(kept, numFriends, numOthers) {
    return [
        kept ? 'You' : null,
        numFriends ? plural(numFriends, 'friend') : null,
        numOthers ? plural(numOthers, 'other') : null]
      .filter(function (v) {return v})
      .join(' + ');
  }

  function plural(n, term) {
    return n + ' ' + term + (n == 1 ? '' : 's');
  }

  function pick(arr, n) {
    if (!arr) return;
    if (n == null || n > arr.length) {
      n = arr.length;
    }
    arr = arr.slice();
    for (var i = 0, j, v, N = arr.length; i < n; i++) {
      j = i + Math.random() * (N - i) | 0;
      v = arr[i], arr[i] = arr[j], arr[j] = v;
    }
    arr.length = n;
    return arr;
  }

  function hasId(id) {
    return function (o) {return o.id == id};
  }

  api.port.on({
    kept: function (o) {
      updateKeptDom(o.kept);
    },
    count: function (n) {
      if (!$slider) return;
      $slider.find('.kifi-count')
        .text(n || '')
        .css('display', n ? '' : 'none');
    },
    tagged: function (o) {
      if ($slider) {
        $slider.find('.kifi-keep-card').toggleClass('kifi-tagged', o.tagged ? true : false);
      }
    }
  });

  // the keeper API
  return {
    showing: function() {
      return !!$slider;
    },
    show: function (trigger) {  // trigger is for the event log (e.g. 'tile', 'auto', 'scroll')
      $(tile).hoverfu('destroy');
      if ($slider) {
        log('[show] already showing')();
      } else {
        log('[show]', trigger)();
        if (trigger === 'tile') {
          showSlider(trigger);
          growSlider('', 'kifi-wide');
        } else if (!lastShownAt) { // auto-show only if not already shown
          showSlider(trigger);
          growSlider('kifi-tiny', 'kifi-auto');
          idleTimer.start(5000);
        }
      }
    },
    create: function(locator) {
      createSlider(locator);
      return $slider.addClass('kifi-wide');
    },
    discard: function() {
      $slider = null;
    },
    appendTo: function(parent) {
      $slider.appendTo(parent);
    },
    showKeepers: function (keepers, otherKeeps) {
      if (lastShownAt) return;
      var $tile = $(tile).hoverfu(function (configureHover) {
        // TODO: preload friend pictures
        render('html/keeper/keepers', {
          tipClass: 'kifi-keepers-promo',
          keepers: pick(keepers, 8),
          captionHtml: formatCountHtml(0, keepers.length, otherKeeps)
        }, function (html) {
          var $tip = $(html).on('transitionend', function unhoverfu(e) {
            if (e.target === this && !this.classList.contains('kifi-showing') && e.originalEvent.propertyName === 'opacity') {
              $tip.off('transitionend', unhoverfu);
              $tile.hoverfu('destroy');
            }
          });
          var px = $tile.find('.kifi-count').length ? 23 : 16;
          configureHover($tip, {
            position: {my: 'right bottom-' + px, at: 'right top', of: $tile.find('.kifi-tile-card'), collision: 'none'},
            insertBefore: $tile, mustHoverFor: 0, canLeaveFor: 1e9});
        });
      });
      $tile.hoverfu('show');
      setTimeout($.fn.hoverfu.bind($tile, 'hide'), 3000);
    },
    onPaneChange: function (locator) {
      $slider.find('.kifi-at').removeClass('kifi-at');
      if (locator) {
        $slider.find('.kifi-dock-' + locator.split('/')[1]).addClass('kifi-at');
        idleTimer.kill();
      } else {  // dislodge from pane and prepare for x transition
        $slider.prependTo(tile).layout();
      }
    }};
}();
