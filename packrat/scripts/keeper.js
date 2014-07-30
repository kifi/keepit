// @require styles/insulate.css
// @require styles/keeper/tile.css
// @require styles/keeper/keeper.css
// @require styles/friend_card.css
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/underscore.js
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
  var $slider, lastCreatedAt, justKept;

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
    if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey && e.isTrusted !== false) {
      var handlers = $(document).data('esc');
      for (var i = handlers.length; i--;) {
        if (handlers[i](e) === false) {
          return false;
        }
        if (e.defaultPrevented) {
          return;
        }
      }
    }
  }

  document.addEventListener('click', onClick, true);
  function onClick(e) {
    if ($slider && (e.closeKeeper || $slider.data('stickiness') < 2 && !$(e.target).is('.kifi-root,.kifi-root *')) && e.isTrusted !== false) {
      hideSlider('clickout');
    }
  }

  api.onEnd.push(function () {
    log('[keeper:onEnd]');
    $slider && $slider.remove();
    $(tile).remove();
    $('.kifi-root').remove();
    document.removeEventListener('keydown', onKeyDown, true);
    document.removeEventListener('click', onClick, true);
  });

  function createSlider(locator) {
    var kept = tile && tile.dataset.kept;
    var count = +(tile && tile.dataset.count || 0);
    log('[createSlider] kept: %s count: %o', kept || 'no', count);
    lastCreatedAt = Date.now();

    $slider = $(render('html/keeper/keeper', {
      'bgDir': api.url('images/keeper'),
      'isKept': kept,
      'isPrivate': kept === 'private',
      'boxCount': count,
      'boxOpen': /^\/messages(?:$|:)/.test(locator),
      'isTagged': tags.length
    }));

    var data = $slider.data();
    data.stickiness = locator ? 2 : 0;  // >= 1 means stay on mouseout, >= 2 means stay on click elsewhere
    function isSticky() {
      return data.stickiness > 0;
    }

    // attach event bindings
    $slider
    .mouseout(function (e) {
      if (e.originalEvent.isTrusted === false) return;
      if (data.dragTimer) {
        startDrag(data);
      } else if (!data.stickiness && !data.dragStarting && !data.$dragGlass) {
        if (e.relatedTarget) {
          if (!this.parentNode.contains(e.relatedTarget)) {
            log('[slider.mouseout] hiding', e.relatedTarget);
            hideSlider('mouseout');
          } else {
            log('[slider.mouseout] not hiding', this.parentNode, 'does not contain', e.relatedTarget);
          }
        } else {  // out of window
          log('[slider.mouseout] out of window');
          document.addEventListener('mouseover', function f(e) {
            this.removeEventListener('mouseover', f, true);
            log('[document.mouseover]', e.target);
            if ($slider && !$slider[0].contains(e.target)) {
              hideSlider('mouseout');
            }
          }, true);
        }
      }
    }).mousedown(function (e) {
      if (e.which !== 1 || data.stickiness || $(e.target).is('.kifi-tip,.kifi-tip *') || e.originalEvent.isTrusted === false) return;
      e.preventDefault();  // prevents selection and selection scrolling
      data.dragTimer = setTimeout(startDrag.bind(null, data), 900);
      data.mousedownEvent = e.originalEvent;
    }).mouseup(function () {
      if (data.dragTimer || data.dragStarting) {
        log('[mouseup]');
        clearTimeout(data.dragTimer), delete data.dragTimer;
        delete data.dragStarting;
      }
      delete data.mousedownEvent;
    }).on('mousewheel', function (e) {
      e.preventDefault(); // crbug.com/151734
    })
    .on('click', '.kifi-keep-btn', _.debounce(function (e) {
      if (e.target === this && e.originalEvent.isTrusted !== false) {
        keepPage('public', e);
        this.classList.add('kifi-hoverless');
      }
    }, 400, true))
    .on('click', '.kifi-kept-btn', _.debounce(function (e) {
      if (e.target === this && e.originalEvent.isTrusted !== false) {
        unkeepPage();
        this.classList.add('kifi-hoverless');
      }
    }, 400, true))
    .on('mouseover', '.kifi-keep-btn>.kifi-tip,.kifi-kept-btn>.kifi-tip', function () {
      this.parentNode.classList.add('kifi-hoverless');
    })
    /*
    .hoverfu('.kifi-keep-btn,.kifi-kept-btn', function (configureHover) {
      var btn = this;
      api.port.emit('get_keepers', function (o) {
        if (o.keepers.length) {
          render('html/keeper/keepers', setKeepersAndCounts(o.keepers, o.otherKeeps, {
            cssClass: 'kifi-keepers-hover',
            linkKeepers: true,
            kept: o.kept
          }), function (html) {
            configureHover(hoverfuFriends($(html), o.keepers), {
              suppressed: isSticky,
              mustHoverFor: 100,
              canLeaveFor: 800,
              click: 'hide',
              parent: btn
            });
          });
        } else {
          render('html/keeper/titled_tip', {
            title: (o.kept ? 'Unkeep' : 'Keep') + ' (' + CO_KEY + '+Shift+K)',
            html: o.kept ? 'Un-keeping this page will<br/>remove it from your keeps.' :
              'Keeping this page helps you<br/>easily find it later.'
          }, function (html) {
            configureHover(html, {
              suppressed: isSticky,
              mustHoverFor: 700,
              hideAfter: 4000,
              click: 'hide',
              position: {my: 'center bottom-13', at: 'center top', of: btn, collision: 'none'}});
          });
        }
      });
    })
    */
    .on('mouseout', '.kifi-keep-btn,.kifi-kept-btn', function () {
      this.classList.remove('kifi-hoverless');
    })
    /*
    .hoverfu('.kifi-keep-lock,.kifi-kept-lock', function (configureHover) {
      var $a = $(this);
      var $card = $(this).closest('.kifi-keep-card');
      var kept = !$card.hasClass('kifi-unkept');
      var publicly = kept && $card.hasClass('kifi-public');
      var title = !kept ?
        'Keep Privately' : publicly ?
        'Make Private' :
        'Make Public';
      var html = !kept ?
        'Keeping this privately allows you<br/>to find this page easily without<br/>letting anyone know you kept it.' : publicly ?
        'This keep is public. Making it private<br/>allows you to find it easily without<br/>letting anyone know you kept it.' :
        'This keep is private. Making it<br/>public allows your friends to<br/>discover that you kept it.';
      render('html/keeper/titled_tip', {title: title, html: html}, function (html) {
        configureHover(html, {
          suppressed: isSticky,
          mustHoverFor: 700,
          hideAfter: 4000,
          click: 'hide',
          position: {my: 'center bottom-13', at: 'center top', of: $a, collision: 'none'}});
      });
    })
    */
    .on('click', '.kifi-keep-lock', _.debounce(function (e) {
      if (e.target === this && e.originalEvent.isTrusted !== false) {
        keepPage('private', e);
      }
    }, 400, true))
    .on('click', '.kifi-kept-lock', _.debounce(function (e) {
      if (e.target === this && e.originalEvent.isTrusted !== false) {
        toggleKeep($(this).closest('.kifi-keep-card').hasClass('kifi-public') ? 'private' : 'public');
      }
    }, 400, true))
    /*
    .hoverfu('.kifi-keep-tag,.kifi-kept-tag', function (configureHover) {
      var btn = this;
      var kept = this.classList.contains('kifi-kept-tag');
      render('html/keeper/titled_tip', {
        cssClass: 'kifi-tag-tip',
        title: 'Tags', //'Tags (' + CO_KEY + '+Shift+A)', TODO: key binding
        html: 'You can tag a keep to<br/>make it easier to find.'
      }, function (html) {
        configureHover(html, {
          suppressed: isSticky,
          mustHoverFor: 700,
          hideAfter: 4000,
          click: 'hide',
          position: {my: 'right bottom-13', at: 'right top', of: btn, collision: 'none'}
        });
      });
    })
    */
    .on('click', '.kifi-keep-tag,.kifi-kept-tag', _.debounce(function (e) {
      if (e.originalEvent.closedTagbox || e.originalEvent.isTrusted === false) {
        return;
      }
      if (this.classList.contains('kifi-keep-tag')) {
        keepPage('public', e, true);
      }
      api.require('scripts/tagbox.js', function () {
        tagbox.onShow.add(beginStickyTime);
        tagbox.onHide.add(endStickyTime);
        tagbox.toggle($slider, 'click:tagIcon');
      });
    }, 400, true))
    /*
    .hoverfu('.kifi-keeper-x', function (configureHover) {
      configureHover({
        mustHoverFor: 700, hideAfter: 2500, click: 'hide',
        position: {my: 'right bottom-13', at: 'right top', of: this, collision: 'none'}
      });
    })
    */
    .on('click', '.kifi-keeper-x', _.debounce(function (e) {
      if (e.originalEvent.isTrusted !== false) {
        pane.hide(tile.style.display !== 'none' && !tile.hasAttribute('kifi-fullscreen'));
      }
    }, 400, true))
    /*
    .hoverfu('.kifi-dock-btn', function(configureHover) {
      var $a = $(this);
      var tip = {
        s: ['Keeps Library', 'View your keeps<br/>on kifi.com in a new tab.'],
        i: ['Message Box (' + CO_KEY + '+Shift+O)', 'Open your Message Box<br/>and see your messages.'],
        c: ['Compose (' + CO_KEY + '+Shift+S)', 'Send this page to any email<br/>address or Kifi friend.']
      }[this.dataset.tip];
      render('html/keeper/titled_tip', {title: tip[0], html: tip[1]}, function (html) {
        var px = $a.find('.kifi-count').text() > 0 ? 24 : 13;
        configureHover(html, {
          suppressed: isSticky,
          mustHoverFor: 700,
          hideAfter: 4000,
          click: 'hide',
          position: {my: 'center bottom-' + px, at: 'center top', of: $a, collision: 'none'}
        });
      });
    })
    */
    .on('mousedown', '.kifi-dock-btn', function (e) {
      e.preventDefault();
    })
    .on('click', '.kifi-dock-btn', _.debounce(function (e) {
      if (e.originalEvent.isTrusted === false) return;
      $slider.data().stickiness |= 2;
      var btn = this;
      api.require('scripts/pane.js', function () {
        if (btn.dataset.compose) {
          pane.compose('keeper');
        } else {
          pane.toggle('keeper');
        }
      });
    }, 400, true));
  }

  function stopPropagation(e) {
    e.stopPropagation();
  }

  function showSlider(trigger) {
    log('[showSlider]', trigger);

    createSlider();
    $slider.addClass('kifi-hidden kifi-transit')
      .prependTo(tile)
      .layout()
      .on('transitionend', function f(e) {
        if (e.target === this) {
          $(this).off('transitionend', f).removeClass('kifi-transit');
        }
      })
      .removeClass('kifi-hidden');
    $(tile).on('mousedown click keydown keypress keyup', stopPropagation);

    api.port.emit('keeper_shown', withUrls({trigger: trigger, onPageMs: String(lastCreatedAt - tile.dataset.t0)}));
  }

  // trigger is for the event log (e.g. 'key', 'icon')
  function hideSlider(trigger) {
    log('[hideSlider]', trigger);
    $slider
    .off('transitionend')
    .on('transitionend', function (e) {
      if (e.target === this) {
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

        if (justKept && !window.guide) {
          api.port.emit('prefs', function (prefs) {
            if (prefs.showExtMsgIntro) {
              setTimeout(api.require.bind(api, 'scripts/external_messaging_intro.js', api.noop), 1000);
            }
          });
          justKept = false;
        }
      }
    })
    .addClass('kifi-hidden kifi-transit');
    $(tile).off('mousedown click keydown keypress keyup', stopPropagation);
  }

  function startDrag(data) {
    log('[startDrag]');
    clearTimeout(data.dragTimer);
    delete data.dragTimer;
    data.dragStarting = true;
    api.require('scripts/lib/jquery-ui-draggable.min.js', function () {
      if (data.dragStarting) {
        delete data.dragStarting;
        log('[startDrag] installing draggable');
        data.$dragGlass = $('<div class="kifi-drag-glass kifi-root">').mouseup(stopDrag).appendTo(tile.parentNode);
        $(tile).draggable({axis: 'y', containment: 'window', scroll: false, stop: stopDrag})[0]
          .dispatchEvent(new MouseEvent('mousedown', data.mousedownEvent)); // starts drag
      }
      function stopDrag() {
        var r = tile.getBoundingClientRect(), fromBot = window.innerHeight - r.bottom;
        var pos = r.top >= 0 && r.top < fromBot ? {top: r.top} : {bottom: Math.max(0, fromBot)};
        log('[stopDrag] top:', r.top, 'bot:', r.bottom, JSON.stringify(pos));
        $(tile).draggable('destroy');
        data.$dragGlass.remove();
        delete data.$dragGlass;
        tile.dataset.pos = JSON.stringify(pos);
        $(tile).css($.extend({top: 'auto', bottom: 'auto'}, pos));
        api.port.emit('set_keeper_pos', {host: location.hostname, pos: pos});
      }
    });
  }

  function keepPage(how, e, suppressNamePrompt) {
    log('[keepPage]', e, how);
    justKept = true;
    var title = authoredTitle();
    api.port.emit('keep', withUrls({title: title, how: how, guided: e.originalEvent.guided}));
    if (!title && !suppressNamePrompt) {
      beginStickyTime();
      api.require('scripts/keep_name_prompt.js', function () {
        keeper.moveToBottom(function () {
          promptForKeepName($slider, function () {
            endStickyTime();
            keeper.moveBackFromBottom();
          });
        });
      });
    }
  }

  function unkeepPage() {
    log('[unkeepPage]', document.URL);
    justKept = false;
    api.port.emit('unkeep', withUrls({}));
  }

  function toggleKeep(how) {
    log('[toggleKeep]', how);
    api.port.emit('set_private', withUrls({private: how == 'private'}));
  }

  function hoverfuFriends($tip, keepers) {
    return $tip.hoverfu('.kifi-keepers-pic', function (configureHover) {
      var $pic = $(this);
      var friend = keepers.filter(hasId($pic.data('id')))[0];
      render('html/friend_card', {
        friend: friend
      }, function (html) {
        configureHover(html, {
          mustHoverFor: 100, hideAfter: 4000, click: 'toggle', parent: $tip,
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
      });
    });
  }

  function beginStickyTime() {
    if ($slider) {
      $slider.data().stickiness |= 1;
    }
  }

  function endStickyTime() {
    if ($slider) {
      var stickiness = $slider.data().stickiness &= ~1;
      if (!stickiness) {
        document.addEventListener('mousemove', function f(e) {
          this.removeEventListener('mousemove', f, true);
          if ($slider && !$slider.data().stickiness && !$slider[0].contains(e.target) && e.isTrusted !== false) {
            hideSlider('mouseout');
          }
        }, true);
      }
    }
  }

  function setKeepersAndCounts(keepers, numOthers, o) {
    var n = keepers.length;
    o.numFriends = n;
    o.numOthers = numOthers;
    o.numSquares = n === 5 || n === 7 ? n - 1 : Math.min(8, n);
    o.keepers = pick(keepers, n === 5 || n === 7 ? n - 2 : (n > 8 ? 7 : n));
    o.numMore = n - o.keepers.length;
    if (n <= 3) {
      o.keepers[n-1].big = true;
      if (n === 2) {
        o.keepers[0].big = true;
      }
    }
    return o;
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
      if ($slider) {
        var $card = $slider.find('.kifi-keep-card');
        if ('kept' in o) {
          $card.removeClass('kifi-unkept kifi-private kifi-public').addClass('kifi-' + (o.kept || 'unkept'));
        }
        if (o.fail && !$card.hasClass('kifi-shake')) {
          $card.one('animationName' in tile.style ? 'animationend' : 'webkitAnimationEnd', $.fn.removeClass.bind($card, 'kifi-shake'))
          .addClass('kifi-shake');
        }
      }
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
    show: function (trigger) {  // trigger is for event log (e.g. 'tile')
      if ($slider) {
        log('[show] ignored, already showing');
      } else {
        log('[show]', trigger);
        $(tile).hoverfu('destroy');
        showSlider(trigger);
      }
    },
    create: function(locator) {
      createSlider(locator);
      return $slider;
    },
    discard: function() {
      $slider.off();
      $slider.find('.kifi-keep-btn,.kifi-kept-btn,.kifi-keep-lock,.kifi-kept-lock,.kifi-keep-tag,.kifi-kept-tag,.kifi-keeper-x,.kifi-dock-btn').hoverfu('destroy');
      $slider = null;
    },
    appendTo: function(parent) {
      $slider.appendTo(parent);
    },
    moveToBottom: function (callback) {
      var dy;
      if (tile.style.display !== 'none' && (dy = window.innerHeight - tile.getBoundingClientRect().bottom)) {
        var $tile = $(tile);
        if (callback) {
          $tile.on('transitionend', function end(e) {
            if (e.target === this) {
              $tile.off('transitionend', end);
              callback();
            }
          })
        }
        $tile.css('transform', 'translate(0,' + dy + 'px)');
      } else if (callback) {
        callback();
      }
    },
    moveBackFromBottom: function () {
      if (tile && tile.style.display !== 'none') {
        $(tile).css('transform', '');
      }
    },
    engage: function (trigger, type) {
      if (lastCreatedAt) return;
      var $tile = $(tile);
      if (type === 'keepers') {
        api.port.emit('get_keepers', function (o) {
          if (o.keepers.length && !lastCreatedAt) {
            $tile.hoverfu(function (configureHover) {
              // TODO: preload friend pictures
              render('html/keeper/keepers', setKeepersAndCounts(o.keepers, o.otherKeeps, {
                cssClass: 'kifi-keepers-promo' + ($tile.find('.kifi-count').length ? ' kifi-above-count' : '')
              }), function (html) {
                if (lastCreatedAt) return;
                var $promo = $(html).on('transitionend', function unhoverfu(e) {
                  if (e.target === this && !this.classList.contains('kifi-showing') && e.originalEvent.propertyName === 'opacity') {
                    $promo.off('transitionend', unhoverfu);
                    $tile.hoverfu('destroy');
                  }
                });
                configureHover($promo, {insertBefore: $tile, mustHoverFor: 0, canLeaveFor: 1e9});
              });
            }).hoverfu('show');
            setTimeout($.fn.hoverfu.bind($tile, 'hide'), 3000);
          }
        });
      }
    },
    onPaneChange: function (locator) {
      $slider.find('.kifi-at').removeClass('kifi-at');
      if (locator) {
        $slider.find('.kifi-dock-' + locator.split(/[\/:]/)[1]).addClass('kifi-at');
        $slider.data().stickiness |= 2;
      } else {  // dislodge from pane and prepare for x transition
        $slider.prependTo(tile).layout();
        $slider.data().stickiness &= ~2;
      }
    }};
}();
