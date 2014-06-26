// @require styles/keeper/pane.css
// @require scripts/lib/q.min.js
// @require scripts/keeper.js
// @require scripts/listen.js
// @require scripts/html/keeper/pane.js
// @require scripts/html/keeper/pane_top_menu.js
// @require scripts/html/keeper/pane_settings.js
// @require scripts/html/keeper/pane_notices.js
// @require scripts/html/keeper/pane_thread.js

$.fn.scrollToBottom = function () {
  'use strict';
  return this.each(function () {
    var cH = this.clientHeight, sH = this.scrollHeight;
    if (cH < sH) {
      var sT = this.scrollTop, d = sH - sT - cH;
      if (d > 0) {
        $(this).animate({scrollTop: sT + d}, 40 * Math.log(d));
      }
    }
  });
};

var panes = panes || {};  // idempotent for Chrome

var pane = pane || function () {  // idempotent for Chrome
  'use strict';
  var $pane, paneHistory, paneObserver;

  $(document).data('esc').add(function () {
    if ($pane && $pane.data('state') === 'open') {
      hidePane();
      return false;
    }
  });

  api.onEnd.push(function () {
    log('[pane:onEnd]');
    if ($pane) $pane.remove();
    $('html').removeAttr('kifi-with-pane kifi-pane-parent');
  });

  function toPaneName(locator) {
    switch (locator.substr(1, 9)) {
      case 'settings': return 'settings';
      case 'messages': return 'notices';
      case 'messages:': return 'notices';
      case 'messages/': return 'thread';
    }
  }

  var paneIdxs = ['notices', 'thread', 'settings'];
  function toPaneIdx(name) {
    return paneIdxs.indexOf(name);
  }

  function showPane(locator, back, redirected) {
    var locatorCurr = paneHistory && paneHistory[0];
    if (locator === locatorCurr) {
      log('[showPane] already at', locator);
      return;
    }
    var paneState = $pane && $pane.data('state');
    if (paneState && paneState !== 'open') {
      log('[showPane] aborting', locator, back ? 'back' : '', paneState);
      return;
    }
    // TODO: have a state for pane-to-pane transitions and avoid interrupting
    var name = toPaneName(locator);
    var nameCurr = locatorCurr && toPaneName(locatorCurr);
    if (name === nameCurr && panes[name].switchTo) {
      log('[showPane] delegating', name, locator);
      panes[name].switchTo(locator);
      return;
    }
    log('[showPane]', locator, name, back ? 'back' : '', redirected ? 'redirected' : '');
    if (!paneHistory) {
      paneHistory = [locator];
    } else if (back) {
      paneHistory.shift();
      paneHistory[0] = locator;  // in case paneHistory was empty
    } else {
      paneHistory.unshift(locator);
    }
    if ($pane) {
      var left = back || toPaneIdx(name) < toPaneIdx(toPaneName(paneHistory[0]));
      keeper.onPaneChange(locator);
      var $cubby = $pane.find(".kifi-pane-cubby").css("overflow", "hidden").layout();
      var $cart = $cubby.find(".kifi-pane-box-cart").addClass(left ? "kifi-back" : "kifi-forward");
      var $old = $cart.find(".kifi-pane-box");
      $old.triggerHandler('kifi:removing');
      var $new = $(render('html/keeper/pane_' + name, {redirected: redirected}))[left ? 'prependTo' : 'appendTo']($cart).layout();
      $cart.addClass("kifi-animated").layout().addClass("kifi-roll")
      .on("transitionend", function end(e) {
        if (e.target !== this) return;
        if (!left) $cart.removeClass("kifi-animated kifi-back kifi-forward");
        $old.triggerHandler('kifi:remove');
        $old.remove();
        $new.data("shown", true).triggerHandler("kifi:shown");
        $cart.removeClass("kifi-roll kifi-animated kifi-back kifi-forward")
          .off("transitionend", end);
        $cubby.css("overflow", "");
      });
      api.port.emit('pane', {old: $pane[0].dataset.locator, new: locator});
      $pane[0].dataset.locator = locator;
      populatePane($new, name, locator);
    } else {
      $('html').attr('kifi-pane-parent', '');
      $pane = $(render('html/keeper/pane', {
          user: me,
          site: location.hostname,
          redirected: redirected
        }, {
          pane_top_menu: 'pane_top_menu',
          pane: 'pane_' + name
        }))
        .data('state', 'opening');
      $pane[0].dataset.locator = locator;
      api.port.emit('pane', {new: locator});

      var bringSlider = !keeper.showing();
      if (bringSlider) {
        $pane.append(keeper.create(locator)).insertAfter(tile);
      } else {
        keeper.onPaneChange(locator);
        $pane.insertBefore(tile);
        keeper.moveToBottom();
      }
      observePaneAncestry();

      $pane.layout()
      .on('transitionend', function onPaneShown(e) {
        if (e.target !== this) return;
        $pane.off('transitionend', onPaneShown);
        if (!bringSlider) {
          keeper.appendTo($pane);
          $pane.before(tile);
        }
        $pane.data('state', 'open');
        keepLastAndCleanUpIfRemoved();
        $box.data("shown", true).triggerHandler("kifi:shown");
        notifyPageOfResize(true);
      });
      $('html').attr('kifi-with-pane', '');
      var $box = $pane.find(".kifi-pane-box");
      populatePane($box, name, locator);
      setTimeout(attachPaneBindings);
    }
  }

  function attachPaneBindings() {
    $pane
      .hoverfu('.kifi-pane-head-logo', function(configureHover) {
        configureHover({
          mustHoverFor: 700, hideAfter: 2500, click: 'hide',
          position: {my: 'center top+10', at: 'center bottom', of: this, collision: 'none'}
        });
      })
      .hoverfu('.kifi-pane-top-menu-a:not(.kifi-active)', function (configureHover) {
        var btn = this;
        render("html/keeper/titled_tip", {
          dir: "below",
          cssClass: 'kifi-pane-settings-tip',
          title: "Settings",
          html: "Customize your Kifi<br/>experience."
        }, function (html) {
          configureHover(html, {
            mustHoverFor: 700, hideAfter: 3000, click: "hide",
            position: {my: 'right top+10', at: 'right bottom', of: btn, collision: 'none'}
          });
        });
      })
      .on("dragstart", ".kifi-pane-top-menu-a", function (e) {
        e.preventDefault();
      })
      .on("mousedown", ".kifi-pane-top-menu-a", function (e) {
        if (e.originalEvent.isTrusted === false) return;
        e.preventDefault();
        var $a = $(this).addClass('kifi-active');
        var $menu = $a.next('.kifi-pane-top-menu').fadeIn(50)
          .on('mouseover', '.kifi-pane-top-menu-item', enterItem)
          .on('mouseout', '.kifi-pane-top-menu-item', leaveItem)
          .on('kifi:hide', hide);
        document.addEventListener("mousedown", docMouseDown, true);
        // .kifi-hover class needed because :hover does not work during drag
        function enterItem(e) {
          $(e.target).closest('.kifi-pane-top-menu-item').addClass('kifi-hover');
        }
        function leaveItem(e) {
          for (var $item = $(e.target); ($item = $item.closest('.kifi-pane-top-menu-item')).length; $item = $item.parent()) {
            if (!e.relatedTarget || !$item[0].contains(e.relatedTarget)) {
              $item.removeClass('kifi-hover');
            }
          }
        }
        function docMouseDown(e) {
          if (!$menu[0].contains(e.target)) {
            $menu.triggerHandler('kifi:hide');
            if ($a[0] === e.target) {
              e.stopPropagation();
            }
          }
        }
        function hide() {
          document.removeEventListener("mousedown", docMouseDown, true);
          $a.removeClass('kifi-active');
          $menu.fadeOut(50, function () {
            $menu.find(".kifi-hover").removeClass("kifi-hover");
          })
          .off('mouseover', '.kifi-pane-top-menu-item', enterItem)
          .off('mouseout', '.kifi-pane-top-menu-item', leaveItem)
          .off('kifi:hide', hide);
        }
        api.port.emit("get_suppressed", function (suppressed) {
          $menu.find('.kifi-hide-on-site').toggleClass('kifi-checked', !!suppressed);
        });
      })
      .on('mouseup', '.kifi-silence-duration', function (e) {
        if (e.originalEvent.isTrusted === false) return;
        this.classList.add('kifi-checked');
        $(this).siblings('.kifi-checked').removeClass('kifi-checked');
        var min = $(this).data('min');
        api.port.emit('silence', min);
        setTimeout(api.require.bind(api, 'scripts/silenced.js', function () {
          showSilenced(min);
        }), 150);
      })
      .on("mouseup", ".kifi-hide-on-site", function (e) {
        if (e.originalEvent.isTrusted === false) return;
        e.preventDefault();
        var $hide = $(this).toggleClass("kifi-checked");
        var checked = $hide.hasClass("kifi-checked");
        $(tile).toggle(!checked);
        api.port.emit("suppress_on_site", checked);
        setTimeout(function () {
          if (checked) {
            hidePane();
          } else {
            $hide.closest(".kifi-pane-top-menu").triggerHandler("kifi:hide");
          }
        }, 150);
      })
      .on('mouseup', '.kifi-open-settings', function (e) {
        if (e.originalEvent.isTrusted === false) return;
        e.preventDefault();
        $(this).closest('.kifi-pane-top-menu').triggerHandler('kifi:hide');
        api.require('styles/keeper/settings.css', showPane.bind(null, '/settings'));
      })
      .on("mouseup", ".kifi-sign-out", function (e) {
        if (e.originalEvent.isTrusted === false) return;
        e.preventDefault();
        api.port.emit("deauthenticate");
        setTimeout(function () {
          $('<kifi class="kifi-root kifi-signed-out-tooltip"><b>Logged out</b><br/>To log back in to Kifi, click the <img class="kifi-signed-out-icon" src="' + api.url('images/k_gray.png') + '"/> icon above.</kifi>')
            .appendTo('body').delay(6000).fadeOut(1000, function () { $(this).remove(); });
        }, 150);
      })
      .on("mouseup", ".kifi-pane-top-menu-item[data-href]", function (e) {
        if (e.originalEvent.isTrusted === false) return;
        e.preventDefault();
        window.open(this.dataset.href);
        $(this).closest(".kifi-pane-top-menu").triggerHandler("kifi:hide");
      })
      .on("mousedown click keydown keypress keyup", function (e) {
        e.stopPropagation();
      });
  }

  function hidePane(leaveSlider) {
    var state = $pane && $pane.data('state');
    if (state !== 'open') {
      log('[hidePane] ignored, state:', state);
      return;
    }
    log('[hidePane]', leaveSlider ? 'leaving slider' : '');
    if (leaveSlider) {
      $(tile).css({top: '', bottom: '', transform: ''}).insertAfter($pane);
      keeper.onPaneChange();
      // $slider.find('.kifi-keeper-x').css('overflow', '');
    } else {
      $(tile).css('transform', '');
      keeper.discard();
    }
    $pane.find('.kifi-pane-box').triggerHandler('kifi:removing');
    pane.onHide.dispatch();
    $pane
    .data('state', 'closing')
    .off('transitionend') // onPaneShown
    .on('transitionend', function (e) {
      if (e.target === this) {
        cleanUpDom();
      }
    });
    api.port.emit('pane', {old: $pane[0].dataset.locator});
    $('html').removeAttr('kifi-with-pane');
  }

  function observePaneAncestry() {
    if (paneObserver) paneObserver.disconnect();
    paneObserver = new MutationObserver(keepLastAndCleanUpIfRemoved);
    var what = {childList: true};
    for (var node = $pane[0].parentNode; node !== document; node = node.parentNode) {
      paneObserver.observe(node, what);
    }
  }

  function keepLastAndCleanUpIfRemoved() {
    if ($pane && document.contains($pane[0])) {
      if ($pane.data('state') === 'open') {  // do not interrupt transition
        var parent = tile.parentNode;
        var child = parent.lastElementChild, ours = [], covered, zIndex;
        while (child !== tile) {
          if (child.classList.contains('kifi-root')) {
            ours.unshift(child);
          } else if (+window.getComputedStyle(child).zIndex >= (zIndex || (zIndex = +window.getComputedStyle(tile).zIndex))) {
            covered = true;
          }
          child = child.previousElementSibling;
        }
        if (covered) {
          var activeEl = document.activeElement, reactivateEl;
          ours.unshift(tile);
          for (var i = ours.length; i--;) {
            var el = ours[i];
            if (activeEl && el.contains(activeEl)) {
              reactivateEl = activeEl;
              activeEl.blur();  // required in firefox
              activeEl = null;
            }
            var $scroll = $(el).find('.kifi-scroll-inner');
            var tops = $scroll.map(function () {return this.scrollTop}).get();
            parent.insertBefore(el, ours[i + 1]);
            $scroll.each(function (i) {this.scrollTop = tops[i]});
          }
          if (reactivateEl) {
            setTimeout(function () {
              reactivateEl.focus();
            });
          }
        }
      }
    } else {
      cleanUpDom();
    }
  }

  function cleanUpDom() {
    if (paneObserver) {
      paneObserver.disconnect();
      paneObserver = null;
    }
    if ($pane) {
      $pane.find('.kifi-pane-box').triggerHandler('kifi:remove');
      if ($pane.data('state') !== 'closing') {
        api.port.emit('pane', {old: $pane[0].dataset.locator});
      }
      $pane.remove();
      $pane = null;
    }
    paneHistory = null;
    if (document.documentElement.hasAttribute('kifi-pane-parent')) {
      $('html').removeAttr('kifi-pane-parent kifi-with-pane');
      notifyPageOfResize();
    }
  }

  function populatePane($box, name, locator) {
    api.require('scripts/' + name + '.js', function () {
      panes[name].render($box, locator);
    });
  }

  function notifyPageOfResize(preventUnload) {
    if (preventUnload) {
      window.addEventListener('beforeunload', beforeUnload, true);
      setTimeout(window.removeEventListener.bind(window, 'beforeunload', beforeUnload, true), 50);
    }
    window.dispatchEvent(new Event('resize'));
  }

  function beforeUnload(e) {
    log('[beforeUnload]');
    e.preventDefault();
    return ' ';
  }

  // the pane API
  return {
    showing: function () {
      return !!$pane;
    },
    show: function (o) {
      log('[pane.show]', o.locator, o.trigger || '', o.redirected || '', o.compose || '');
      if (o.compose) {
        pane.compose(o.trigger, o.locator, o.to);
      } else {
        showPane(o.locator, false, o.redirected);
      }
    },
    hide: hidePane,
    toggle: function (trigger, locator) {
      locator = locator || '/messages:all';
      if ($pane) {
        if ($pane.data('state') === 'closing') {
          log('[pane.toggle] ignoring, hiding');
        } else if (window.toaster && toaster.showing()) {
          toaster.hide();
          showPane(locator);
        } else if (locator === paneHistory[0]) {
          hidePane(trigger === 'keeper');
        } else {
          showPane(locator);
        }
      } else {
        showPane(locator);
      }
    },
    compose: function (trigger, locator, recipient) {
      log('[pane:compose]', trigger);
      api.require('scripts/compose_toaster.js', function () {
        if (!$pane) {
          showPane(locator || '/messages:all');
        }
        if ($pane.data('state') !== 'closing') {
          if (trigger === 'deepLink' && toaster.showing()) return;  // don't clobber form
          toaster.toggle($pane).done(function (compose) {
            if (compose) {
              if (recipient) {
                compose.prefill(recipient);
              }
              compose.snapSelection() || compose.focus();
            }
          });
        }
      });
    },
    shade: function () {
      if ($pane) {
        $pane.addClass('kifi-shaded');
      }
    },
    unshade: function () {
      if ($pane) {
        $pane.removeClass('kifi-shaded');
      }
    },
    pushState: function(loc) {
      if (paneHistory[0] !== loc) {
        paneHistory.unshift(loc);
      }
    },
    back: function (fallbackLocator) {
      showPane(paneHistory[1] || fallbackLocator, true);
    },
    onHide: new Listeners
  };
}();
