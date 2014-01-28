// @require styles/keeper/pane.css
// @require scripts/lib/q.min.js
// @require scripts/keeper.js
// @require scripts/listen.js
// @require scripts/html/keeper/pane.js
// @require scripts/html/keeper/pane_top_menu.js
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
  var $pane, paneHistory;

  $(document).data('esc').add(function (e) {
    if ($pane) {
      hidePane();
      return false;
    }
  });

  api.onEnd.push(function () {
    log('[pane:onEnd]')();
    $pane && $pane.remove();
    $('html').removeAttr('kifi-with-pane kifi-pane-parent');
  });

  function toPaneName(locator) {
    return /^\/messages\//.test(locator) ? 'thread' : 'notices';
  }

  var paneIdxs = ['notices', 'thread'];
  function toPaneIdx(name) {
    return paneIdxs.indexOf(name);
  }

  var createPaneParams = {
    thread: function (cb, locator, participants) {
      if (participants) {
        respond(participants);
      } else {
        var id = locator.substr(10);
        log("[createPaneParams.thread] need participants", id)();
        api.port.emit("participants", id, respond);
      }
      function respond(p) {
        cb({participants: p, numParticipants: p.length > 1 ? p.length : null});
      }
    }};

  function showPane(locator, back, paramsArg, redirected) {
    log('[showPane]', locator, back ? 'back' : '')();
    var deferred = Q.defer();
    var locatorCurr = paneHistory && paneHistory[0];
    var nameCurr = locatorCurr && toPaneName(locatorCurr);
    var name = toPaneName(locator);
    if (locator === locatorCurr) {
      deferred.resolve();
    } else if (name === nameCurr && panes[name].switchTo) {
      panes[name].switchTo(locator);
      deferred.resolve();
    } else {
      if (!paneHistory) {
        paneHistory = [locator];
      } else if (back) {
        paneHistory.shift();
        paneHistory[0] = locator;  // in case paneHistory was empty
      } else {
        paneHistory.unshift(locator);
      }
      (createPaneParams[name] || function (cb) {cb({})})(function (params) {
        if (paneHistory[0] === locator) {
          params.redirected = redirected;
          showPaneContinued(locator, back, name, params);
          deferred.resolve();
        }
      }, locator, paramsArg);
      deferred.resolve();
    }
    return deferred.promise;
  }

  function showPaneContinued(locator, back, name, params) {  // only called by showPane
    log("[showPaneContinued]", locator, name)();
    if ($pane) {
      var left = back || toPaneIdx(name) < toPaneIdx(toPaneName(paneHistory[0]));
      keeper.onPaneChange(locator);
      var $cubby = $pane.find(".kifi-pane-cubby").css("overflow", "hidden").layout();
      var $cart = $cubby.find(".kifi-pane-box-cart").addClass(left ? "kifi-back" : "kifi-forward");
      var $old = $cart.find(".kifi-pane-box");
      var $new = $(render('html/keeper/pane_' + name, params))[left ? "prependTo" : "appendTo"]($cart).layout();
      $cart.addClass("kifi-animated").layout().addClass("kifi-roll")
      .on("transitionend", function end(e) {
        if (e.target !== this) return;
        if (!left) $cart.removeClass("kifi-animated kifi-back kifi-forward");
        $old.triggerHandler("kifi:remove");
        $old.remove();
        $new.data("shown", true).triggerHandler("kifi:shown");
        $cart.removeClass("kifi-roll kifi-animated kifi-back kifi-forward")
          .off("transitionend", end);
        $cubby.css("overflow", "");
      });
      api.port.emit("pane", {old: $pane[0].dataset.locator, new: locator});
      $pane[0].dataset.locator = locator;
      populatePane($new, name, locator);
    } else {
      $('html').attr('kifi-pane-parent', '');
      $pane = $(render('html/keeper/pane',
        $.extend(params, {
          site: location.hostname,
          user: me
        }), {
          pane_top_menu: 'pane_top_menu',
          pane: 'pane_' + name
        }));
      $pane[0].dataset.locator = locator;
      api.port.emit("pane", {new: locator});
      var bringSlider = !keeper.showing();
      if (bringSlider) {
        $pane.append(keeper.create(locator)).appendTo(tile.parentNode);
      } else {
        keeper.onPaneChange(locator);
        $pane.insertBefore(tile);
        keeper.moveToBottom();
      }
      $pane.layout()
      .on("transitionend", function onPaneShown(e) {
        if (e.target !== this) return;
        $pane.off("transitionend", onPaneShown);
        if (bringSlider) {
          tile.style.display = "block"; // in case sensitive
        } else {
          keeper.appendTo($pane);
          $pane.before(tile);
        }
        $box.data("shown", true).triggerHandler("kifi:shown");
        notifyPageOfResize(true);
      })
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
      .on("mouseup", ".kifi-sign-out", function (e) {
        if (e.originalEvent.isTrusted === false) return;
        e.preventDefault();
        api.port.emit("deauthenticate");
        setTimeout(function () {
          $('<kifi class="kifi-root kifi-signed-out-tooltip"><b>Logged out</b><br/>To log back in to Kifi, click the <img class="kifi-signed-out-icon" src="' + api.url('images/k_gray.png') + '"/> button above.</kifi>')
            .appendTo('body').delay(6000).fadeOut(1000, function () { $(this).remove(); });
        }, 150);
      })
      .on("mouseup", ".kifi-pane-top-menu-item[data-href]", function (e) {
        if (e.originalEvent.isTrusted === false) return;
        e.preventDefault();
        window.open(this.dataset.href);
        $(this).closest(".kifi-pane-top-menu").triggerHandler("kifi:hide");
      })
      .on("kifi:show-pane", function (e, loc, paramsArg) {
        showPane(loc, false, paramsArg);
      })
      .on("mousedown click keydown keypress keyup", function (e) {
        e.stopPropagation();
      });
      $('html').attr('kifi-with-pane', '');
      var $box = $pane.find(".kifi-pane-box");
      populatePane($box, name, locator);
    }
  }

  function hidePane(leaveSlider) {
    log('[hidePane]', leaveSlider ? 'leaving slider' : '')();
    if (leaveSlider) {
      $(tile).css({top: '', bottom: '', transform: ''}).insertAfter($pane);
      keeper.onPaneChange();
      // $slider.find('.kifi-keeper-x').css('overflow', '');
    } else {
      $(tile).css('transform', '');
      keeper.discard();
    }
    pane.onHide.dispatch();
    $pane
    .off('transitionend') // onPaneShown
    .on("transitionend", function (e) {
      if (e.target === this) {
        var $pane = $(this);
        $pane.find('.kifi-pane-box').triggerHandler('kifi:remove');
        $pane.remove();
        $('html').removeAttr('kifi-pane-parent');
        notifyPageOfResize();
      }
    });
    api.port.emit('pane', {old: $pane[0].dataset.locator});
    $pane = paneHistory = null;
    $('html').removeAttr('kifi-with-pane');
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
    log('[beforeUnload]')();
    e.preventDefault();
    return ' ';
  }

  // the pane API
  return {
    showing: function () {
      return !!$pane;
    },
    show: function (o) {
      log('[pane.show]', o.locator, o.trigger || '', o.paramsArg || '', o.redirected || '', o.composeTo || '')();
      if (o.composeTo) {
        pane.compose(o.trigger, o.composeTo);
      } else {
        showPane(o.locator, false, o.paramsArg, o.redirected);
      }
    },
    hide: function (leaveSlider) {
      if ($pane) {
        hidePane(leaveSlider);
      }
    },
    toggle: function (trigger, locator) {
      locator = locator || '/messages:all';
      if ($pane) {
        if (window.toaster && toaster.showing()) {
          toaster.hide();
          showPane(locator);
        } else if (locator === paneHistory[0]) {
          hidePane(trigger === 'keeper');
        } else {
          showPane(locator);
        }
      } else if ($('html').attr('kifi-pane-parent') == null) { // ensure it's finished hiding
        showPane(locator);
      }
    },
    compose: function(trigger, recipient) {
      log('[pane:compose]', trigger)();
      api.require('scripts/compose_toaster.js', function () {
        if ($pane) {
          withPane();
        } else {
          showPane('/messages:all').then(withPane);
        }
        function withPane() {
          if (recipient && toaster.showing()) return;  // don't clobber form
          toaster.toggle($pane).done(function (compose) {
            if (compose) {
              if (recipient) {
                compose.prefill(recipient);
              }
              compose.focus();
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
    getLocator: function () {
      return $pane && $pane[0].dataset.locator || null;
    },
    getThreadId: function () {
      var locator = this.getLocator();
      return locator && locator.split('/')[2];
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
