// @require styles/metro/slider2.css
// @require styles/friend_card.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/jquery-bindhover.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/render.js

$.fn.layout = function() {
  return this.each(function() {this.clientHeight});  // forces layout
};
$.fn.scrollToBottom = function() {
  return this.each(function() {
    var cH = this.clientHeight, sH = this.scrollHeight;
    if (cH < sH) {
      var sT = this.scrollTop, d = sH - sT - cH;
      if (d > 0) {
        $(this).animate({scrollTop: "+=" + d}, 40 * Math.log(d));
      }
    }
  });
};
!function() {
  $.fn.scrollable = function() {
    return this.each(function() {
      var data = $(this).data(), el;
      for (el = this; !data.elAbove; el = el.parentNode) {
        data.elAbove = el.previousElementSibling;
      }
      for (el = this; !data.elBelow; el = el.parentNode) {
        data.elBelow = el.nextElementSibling;
      }
      data.elAbove.classList.add("kifi-scrollable-above");
      data.elBelow.classList.add("kifi-scrollable-below");
    }).scroll(onScroll);
  };
  function onScroll() {
    var sT = this.scrollTop, sH = this.scrollHeight, oH = this.offsetHeight, data = $(this).data();
    data.elAbove.classList[sT > 0 ? "add" : "remove"]("kifi-can-scroll");
    data.elBelow.classList[sT < sH - oH ? "add" : "remove"]("kifi-can-scroll");
  }
}();

const CO_KEY = /^Mac/.test(navigator.platform) ? "⌘" : "Ctrl";
var generalPane, noticesPane, commentsPane, threadsPane, threadPane;  // stubs
generalPane = noticesPane = commentsPane = threadsPane = threadPane = {update: $.noop, updateAll: $.noop};

slider2 = function() {
  var $slider, $pane, lastShownAt;

  document.addEventListener("keydown", onKeyDown, true);
  function onKeyDown(e) {
    if (e.keyCode == 27 && !e.metaKey && !e.ctrlKey && !e.shiftKey) {  // esc
      var escHandler = $(document).data("esc");
      if (escHandler) {
        escHandler();
      } else if ($pane) {
        hidePane();
      } else if ($slider) {
        hideSlider("esc");
      }
    }
  }

  api.onEnd.push(function() {
    api.log("[slider2:onEnd]");
    $pane && $pane.remove();
    $slider && $slider.remove();
    $(tile).remove();
    $("html").removeClass("kifi-with-pane kifi-pane-parent");
    document.removeEventListener("keydown", onKeyDown, true);
  });

  function createSlider(callback, locator) {
    var kept = tile && tile.dataset.kept;
    var counts = JSON.parse(tile && tile.dataset.counts || '{"n":0,"c":0,"m":0}');
    api.log("[createSlider] kept: %s counts: %o", kept || "no", counts);

    render("html/metro/slider2.html", {
      "bgDir": api.url("images/keeper"),
      "isKept": kept,
      "isPrivate": kept == "private",
      "noticesCount": counts.n,
      "commentCount": counts.c,
      "messageCount": counts.m,
      "atNotices": "/notices" == locator,
      "atComments": "/comments" == locator,
      "atMessages": /^\/messages/.test(locator),
      "atGeneral": "/general" == locator
    }, function(html) {
      // attach event bindings
      $slider = $(html);
      var data = $slider.data();
      $slider.mouseout(function(e) {
        if (data.dragTimer) {
          startDrag(data);
        } else if (!$pane && !data.dragStarting && !data.$dragGlass) {
          if (e.relatedTarget) {
            if ($slider && !$slider[0].contains(e.relatedTarget)) {
              api.log("[slider.mouseout]");
              hideSlider("mouseout");
            }
          } else {  // out of window
            api.log("[slider.mouseout] out of window");
            document.documentElement.addEventListener("mouseover", function f(e) {
              this.removeEventListener("mouseover", f, true);
              api.log("[document.mouseover]", e.target);
              if ($slider && !$slider[0].contains(e.target)) {
                hideSlider("mouseout");
              }
            }, true);
          }
        }
      }).mousedown(function(e) {
        if (e.which != 1 || $pane) return;
        e.preventDefault();  // prevents selection and selection scrolling
        data.dragTimer = setTimeout(startDrag.bind(null, data), 900);
        data.mousedownEvent = e.originalEvent;
      }).mouseup(function() {
        if (data.dragTimer || data.dragStarting) {
          api.log("[mouseup]");
          clearTimeout(data.dragTimer), delete data.dragTimer;
          delete data.dragStarting;
        }
        delete data.mousedownEvent;
      }).on("click", ".kifi-slider2-keep-btn", function(e) {
        if (e.target !== this) return;
        var el = this.parentNode;
        if (el.classList.contains("kifi-unkept")) {
          keepPage(el, "public");
        } else {
          unkeepPage(el);
        }
        this.classList.add("kifi-hoverless");
      }).on("mouseover", ".kifi-slider2-keep-btn>.kifi-slider2-tip", function() {
        this.parentNode.classList.add("kifi-hoverless");
      }).bindHover(".kifi-slider2-keep-btn", function(configureHover) {
        var btn = this;
        api.port.emit("get_keepers", function(o) {
          if (o.keepers.length) {
            render("html/metro/keepers.html", {
              link: true,
              keepers: pick(o.keepers, 8),
              captionHtml: formatCountHtml(o.kept, o.keepers.length, o.otherKeeps)
            }, function(html) {
              configureHover($(html).data("keepers", o.keepers), {
                showDelay: 700,
                hideDelay: 800,
                click: "hide",
                position: positionIt});
            });
          } else {
            render("html/keeper/titled_tip.html", {
              title: (o.kept ? "Unkeep" : "Keep") + " (" + CO_KEY + "+Shift+K)",
              html: o.kept ? "Un-keeping this page will<br>remove it from your keeps." :
                "Keeping this page helps you<br>easily find it later."
            }, function(html) {
              configureHover(html, {
                showDelay: 700,
                click: "hide",
                position: positionIt});
            });
          }
          function positionIt(w) {  // centered, or right-aligned if that would go off edge of page
            if (!$slider) return;
            var r1 = btn.getBoundingClientRect(), r2 = $slider[0].getBoundingClientRect();
            this.style.right = Math.max((r1.width - w) / 2, r1.right - r2.right + 6) + "px";
          }
        });
      }).bindHover(".kifi-slider2-keeper", function(configureHover) {
        var $a = $(this);
        var friend = $a.closest(".kifi-slider2-tip").data("keepers").filter(hasId($a.data("id")))[0];
        if (!friend) return;
        render("html/friend_card.html", {
          networkIds: friend.networkIds,
          name: friend.firstName + " " + friend.lastName,
          id: friend.id,
          iconsUrl: api.url("images/social_icons.png")
        }, function(html) {
          configureHover(html, {showDelay: 100, hideDelay: 600, click: "toggle"});
        });
      }).on("mouseout", ".kifi-slider2-keep-btn", function() {
        this.classList.remove("kifi-hoverless");
      }).on("hover:hide", ".kifi-slider2-keep-btn", function() {
        document.documentElement.addEventListener("mousemove", function f(e) {
          this.removeEventListener("mousemove", f, true);
          if ($slider && !$slider[0].contains(e.target)) {
            hideSlider("mouseout");
          }
        }, true);
      }).bindHover(".kifi-slider2-lock", function(configureHover) {
        var kept = !this.parentNode.classList.contains("kifi-unkept");
        var publicly = kept && this.parentNode.classList.contains("kifi-public");
        var title = !kept ?
          "Keep Privately" : publicly ?
          "Make Private" :
          "Make Public";
        var html = !kept ?
          "Keeping this privately allows you<br>to find this page easily without<br>letting anyone know you kept it." : publicly ?
          "This keep is public. Making it private<br>allows you to find it easily without<br>letting anyone know you kept it." :
          "This keep is private. Making it<br>public allows your friends to<br>discover that you kept it.";
        render("html/keeper/titled_tip.html", {title: title, html: html}, function(html) {
          configureHover(html, {
            showDelay: 700,
            click: "hide",
            position: function(w) {
              this.style.left = 8 - w / 2 + "px";
            }});
        });
      }).on("click", ".kifi-slider2-lock", function(e) {
        if (e.target !== this) return;
        var el = this.parentNode;
        if (el.classList.contains("kifi-unkept")) {
          keepPage(el, "private");
        } else {
          togglePrivate(el);
        }
      }).bindHover(".kifi-slider2-x", function(configureHover) {
        this.style.overflow = "visible";
        configureHover({showDelay: 700, click: "hide"});
      }).on("click", ".kifi-slider2-x", function() {
        if ($pane) {
          hidePane(true);
        }
      }).bindHover(".kifi-slider2-dock-btn", function(configureHover) {
        var tip = {
          n: ["Notifications", "View all of your notifications.<br>Any new ones are highlighted."],
          c: ["Comments", "View and post comments<br>about this page."],
          m: ["Messages (" + CO_KEY + "+Shift+M)", "Send this page to friends<br>and start a discussion."],
          g: ["More Options (" + CO_KEY + "+Shift+O)", "Take notes about this page,<br>keep to a collection, read it<br>later and more."]
        }[this.dataset.loc.substr(1,1)];
        render("html/keeper/titled_tip.html", {title: tip[0], html: tip[1]}, function(html) {
          configureHover(html, {
            showDelay: 700,
            click: "hide",
            position: function(w) {
              this.style.left = 21 - w / 2 + "px";
            }});
        });
      }).on("click", ".kifi-slider2-dock-btn", function() {
        var locator = this.dataset.loc;
        if ($pane) {
          if (locator == paneHistory[0]) {
            hidePane(true);
          } else {
            showPane(locator);
          }
        } else if (!$("html").hasClass("kifi-pane-parent")) { // ensure it's finished hiding
          idleTimer.kill();
          showPane(locator);
        }
      });
      $(tile).addClass("kifi-with-slider");
      callback();
    });
  }

  function showSlider(trigger, callback) {
    api.log("[showSlider]", trigger);

    lastShownAt = +new Date;
    $slider = $();  // creation in progress (prevents multiple)

    createSlider(function() {
      $slider.appendTo(tile).layout().addClass("kifi-wide kifi-growing")
      .on("transitionend webkitTransitionEnd", function f(e) {
        if (e.target.classList.contains("kifi-slider2")) {
          $(e.target).off("transitionend webkitTransitionEnd", f).removeClass("kifi-growing");
        }
      });

      logEvent("slider", "sliderShown", {trigger: trigger, onPageMs: String(lastShownAt - t0), url: document.URL});
      api.port.emit("keeper_shown");

      callback && callback();
    });
  }

  // trigger is for the event log (e.g. "key", "icon")
  function hideSlider(trigger) {
    idleTimer.kill();
    var sliderEl = $slider[0];
    $slider.addClass("kifi-hiding").on("transitionend webkitTransitionEnd", function(e) {
      if (e.target === sliderEl && e.originalEvent.propertyName == "opacity") {
        tile.classList.remove("kifi-with-slider");
        var css = JSON.parse(tile.dataset.pos || 0);
        if (css && !tile.style.top && !tile.style.bottom) {
          var y = css.top >= 0 ? window.innerHeight - css.top - 54 : (css.bottom || 0);
          css.transition = "none";
          css.transform = "translate(0," + y + "px)";
          $(tile).css(css)
          .layout().css({transition: "", "transition-duration": Math.min(1, 32 * Math.log(y)) + "ms"});
          tile["kifi:position"]();
          $(tile).on("transitionend webkitTransitionEnd", function end() {
            $(this).off("transitionend webkitTransitionEnd", end).css("transition-duration", "");
          });
        }
        $slider.remove(), $slider = null;
      }
    });
    logEvent("slider", "sliderClosed", {trigger: trigger, shownForMs: String(new Date - lastShownAt)});
  }

  function startDrag(data) {
    api.log("[startDrag]");
    clearTimeout(data.dragTimer);
    delete data.dragTimer;
    data.dragStarting = true;
    api.require("scripts/lib/jquery-ui-1.9.1.custom.min.js", function() {
      if (data.dragStarting) {
        delete data.dragStarting;
        api.log("[startDrag] installing draggable");
        data.$dragGlass = $("<div class=kifi-slider2-drag-glass>").appendTo("html");
        $(tile).draggable({axis: "y", containment: "window", scroll: false, stop: function stopDrag() {
          var r = tile.getBoundingClientRect(), fromBot = window.innerHeight - r.bottom;
          var pos = r.top >= 0 && r.top < fromBot ? {top: r.top} : {bottom: Math.max(0, fromBot)};
          api.log("[stopDrag] top:", r.top, "bot:", r.bottom, JSON.stringify(pos));
          $(tile).draggable("destroy");
          data.$dragGlass.remove();
          delete data.$dragGlass;
          tile.dataset.pos = JSON.stringify(pos);
          $(tile).css($.extend({top: "auto", bottom: "auto"}, pos));
          api.port.emit("set_keeper_pos", {host: location.hostname, pos: pos});
        }})[0].dispatchEvent(data.mousedownEvent); // starts drag
      }
    });
  }

  var idleTimer = {
    start: function(ms) {
      idleTimer.ms = ms = ms > 0 ? ms : idleTimer.ms;
      api.log("[idleTimer.start]", ms, "ms");
      var t = idleTimer;
      clearTimeout(t.timeout);
      t.timeout = setTimeout(function hideSliderIdle() {
        api.log("[hideSliderIdle]");
        hideSlider("idle");
      }, ms);
      $slider
        .off("mouseenter", t.clear).on("mouseenter", t.clear)
        .off("mouseleave", t.start).on("mouseleave", t.start);
      delete t.dead;
    },
    clear: function() {
      api.log("[idleTimer.clear]");
      var t = idleTimer;
      clearTimeout(t.timeout);
      delete t.timeout;
    },
    kill: function() {
      var t = idleTimer;
      if (t.dead) return;
      api.log("[idleTimer.kill]");
      clearTimeout(t.timeout);
      delete t.timeout;
      $slider
        .off("mouseenter", t.clear)
        .off("mouseleave", t.start);
      t.dead = true;
    }};

  function keepPage(el, how) {
    api.log("[keepPage]", how);
    updateKeptDom(el, how);
    api.port.emit("keep", {url: document.URL, title: document.title, how: how});
    logEvent("slider", "keep", {isPrivate: how == "private"});
  }

  function unkeepPage(el) {
    api.log("[unkeepPage]", document.URL);
    updateKeptDom(el, "");
    api.port.emit("unkeep");
    logEvent("slider", "unkeep");
  }

  function togglePrivate(el) {
    var priv = el.classList.contains("kifi-public");
    api.log("[togglePrivate]", priv);
    updateKeptDom(el, priv ? "private" : "public");
    api.port.emit("set_private", priv);
  }

  function updateKeptDom(el, how) {
    $(el).removeClass("kifi-unkept kifi-private kifi-public").addClass("kifi-" + (how || "unkept"));
    if ($pane) $pane.find(".kifi-pane-kept").toggleClass("kifi-kept", !!how);
  }

  function toPaneName(locator) {
    var name = locator.match(/[a-z]+\/?/)[0];
    return {messages: "threads", "messages/": "thread", "comments/": "comments"}[name] || name;
  }

  const createTemplateParams = {
    general: function(cb) {
      cb({title: document.title, url: document.URL});
    },
    thread: function(cb, locator, recipients) {
      var id = locator.split("/")[2];  // can be id of any message (assumed to be parent if recipients provided)
      if (recipients) {
        respond(recipients, locator);
      } else {
        api.log("[createTemplateParams] getting thread for recipients");
        api.port.emit("thread", {id: id, respond: true}, function(th) {
          respond(th.messages[0].recipients, "/messages/" + th.id);
        });
      }
      function respond(r, canonicalLocator) {
        cb({recipients: r, numRecipients: r.length > 1 ? r.length : null}, canonicalLocator);
      }
    }};

  function showPane(locator, back, paramsArg) {
    api.log("[showPane]", locator, back ? "back" : "");
    var pane = toPaneName(locator);
    (createTemplateParams[pane] || function(cb) {cb()})(function(params, canonicalLocator) {
      var loc = canonicalLocator || locator;
      if (loc !== (paneHistory && paneHistory[0])) {
        showPane2(loc, back, pane, params);
      }
    }, locator, paramsArg);
  }

  function showPane2(locator, back, pane, params) {  // only called by showPane
    api.log("[showPane2]", locator, pane);
    if ($pane) {
      $slider.find(".kifi-at").removeClass("kifi-at").end()
        .find(".kifi-slider2-" + locator.split("/")[1]).addClass("kifi-at");
      render("html/metro/pane_" + pane + ".html", params, function(html) {
        var $cubby = $pane.find(".kifi-pane-cubby").css("overflow", "hidden");
        var $cart = $cubby.find(".kifi-pane-box-cart").addClass(back ? "kifi-back" : "kifi-forward");
        var $old = $cart.find(".kifi-pane-box");
        var $new = $(html)[back ? "prependTo" : "appendTo"]($cart).layout();
        $cart.addClass("kifi-animated").layout().addClass("kifi-roll")
        .on("transitionend webkitTransitionEnd", function end(e) {
          if (e.target !== this) return;
          if (!back) $cart.removeClass("kifi-animated kifi-back kifi-forward");
          $old.triggerHandler("kifi:remove");
          $old.remove();
          $new.data("shown", true).triggerHandler("kifi:shown");
          $cart.removeClass("kifi-roll kifi-animated kifi-back kifi-forward")
            .off("transitionend webkitTransitionEnd", end);
          $cubby.css("overflow", "");
        });
        if (back) {
          paneHistory.shift();
          paneHistory[0] = locator;  // usually unnecessary (already the same)
        } else {
          paneHistory.unshift(locator);
        }
        populatePane[pane]($new, locator);
      });
    } else {
      paneHistory = [locator];
      var bringSlider = !$slider;
      if (bringSlider) {
        createSlider(function() {
          $slider.addClass("kifi-wide");
        }, locator);
      } else {
        $slider.find(".kifi-slider2-" + locator.split("/")[1]).addClass("kifi-at");
      }
      api.require("styles/metro/pane.css", function() {
        render("html/metro/pane.html", $.extend(params, {
          site: location.hostname,
          kifiLogoUrl: api.url("images/kifi_logo.png"),
          gearUrl: api.url("images/metro/gear.png")
        }), {
          pane: "pane_" + pane + ".html"
        },
        function(html) {
          var $html = $("html").addClass("kifi-pane-parent");
          $pane = $(html);
          if (bringSlider) {
            $pane.append($slider).appendTo($html);
          } else {
            $pane.appendTo($html);
            $slider.detach()
            .css("transform", "translate(0,-" + (window.innerHeight - tile.getBoundingClientRect().bottom) + "px)")
            .insertAfter($pane).layout()
            .css("transform", "");
            $(tile).hide();
          }
          $pane.layout()
          .on("transitionend webkitTransitionEnd", function onPaneShown(e) {
            $pane.off("transitionend webkitTransitionEnd", onPaneShown);
            if (!bringSlider) {
              $slider.appendTo($pane);
            }
            $box.data("shown", true).triggerHandler("kifi:shown");
          })
          .bindHover(".kifi-pane-head-logo", {showDelay: 700, click: "hide"})
          .bindHover(".kifi-pane-head-feedback", function(configureHover) {
            render("html/keeper/titled_tip.html", {
              dir: "below",
              title: "Give Us Feedback",
              html: "Tell us your ideas for KiFi<br>or report an issue."
            }, function(html) {
              configureHover(html, {showDelay: 700, click: "hide"});
            });
          })
          .on("click", ".kifi-pane-head-feedback", function(e) {
            e.preventDefault();
            var width = 700;
            var height = 400;
            var left = (screen.width - width) / 2;
            var top = (screen.height - height) / 2;
            window.open(
              "https://www.kifi.com/feedback/form",
              "kifi-feedback",
              "width="+width+",height="+height+",resizable,top="+top+",left="+left);
          })
          .bindHover(".kifi-pane-head-settings:not(.kifi-active)", function(configureHover) {
            render("html/keeper/titled_tip.html", {
              dir: "below",
              title: "Settings",
              html: "Customize your KiFi<br>experience."
            }, function(html) {
              configureHover(html, {showDelay: 700, click: "hide"});
            });
          })
          .on("mousedown", ".kifi-pane-head-settings", function(e) {
            e.preventDefault();
            var $sett = $(this).addClass("kifi-active");
            var $menu = $sett.next(".kifi-pane-head-settings-menu").fadeIn(50);
            var $hide = $menu.find(".kifi-pane-settings-hide")
              .on("mouseenter", enterItem)
              .on("mouseleave", leaveItem);
            document.addEventListener("mousedown", docMouseDown, true);
            $menu.on("kifi:hide", hide);
            // .kifi-hover class needed because :hover does not work during drag
            function enterItem() { $(this).addClass("kifi-hover"); }
            function leaveItem() { $(this).removeClass("kifi-hover"); }
            function docMouseDown(e) {
              if (!$menu[0].contains(e.target)) {
                $menu.triggerHandler("kifi:hide");
                if ($sett[0] === e.target) {
                  e.stopPropagation();
                }
              }
            }
            function hide() {
              document.removeEventListener("mousedown", docMouseDown, true);
              $sett.removeClass("kifi-active");
              $hide.off("mouseenter", enterItem)
                  .off("mouseleave", leaveItem);
              $menu.off("kifi:hide", hide).fadeOut(50, function() {
                $menu.find(".kifi-hover").removeClass("kifi-hover");
              });
            }
            api.port.emit("get_suppressed", function(suppressed) {
              $hide.toggleClass("kifi-checked", !!suppressed);
            });
          })
          .on("mouseup", ".kifi-pane-settings-hide", function(e) {
            e.preventDefault();
            var $hide = $(this).toggleClass("kifi-checked");
            var checked = $hide.hasClass("kifi-checked");
            $(tile).toggle(!checked);
            api.port.emit("suppress_on_site", checked);
            setTimeout(function() {
              if (checked) {
                hidePane();
              } else {
                $hide.closest(".kifi-pane-head-settings-menu").triggerHandler("kifi:hide");
              }
            }, 150);
          })
          .on("keydown", ".kifi-pane-search", function(e) {
            var q;
            if (e.which == 13 && (q = this.value.trim())) {
              window.open("https://www.google.com/search?q=" + encodeURIComponent(q).replace(/%20/g, "+"));
            }
          })
          .on("click", ".kifi-pane-back", function() {
            showPane(paneHistory[1] || this.dataset.loc || "/general", true);
          })
          .on("click", ".kifi-pane-action", function() {
            var $n = $pane.find(".kifi-not-done"), d = $n.data();
            clearTimeout(d.t);
            $n.remove().removeClass("kifi-showing").appendTo($pane).layout().addClass("kifi-showing");
            d.t = setTimeout($n.removeClass.bind($n, "kifi-showing"), 1000);
          })
          .on("kifi:show-pane", function(e, loc, paramsArg) {
            showPane(loc, false, paramsArg);
          })
          .on("mousedown click keydown keypress keyup", function(e) {
            e.stopPropagation();
          });
          $html.addClass("kifi-with-pane");
          var $box = $pane.find(".kifi-pane-box");
          populatePane[pane]($box, locator);
        });
      });
    }
  }

  function hidePane(leaveSlider) {
    api.log("[hidePane]");
    $(tile).show();
    if (leaveSlider) {
      $(tile).css({top: "", bottom: "", transform: ""}).insertAfter($pane);
      $slider.appendTo(tile).layout();
      $slider.find(".kifi-at").removeClass("kifi-at");
      $slider.find(".kifi-slider2-x").css("overflow", "");
    } else {
      $slider = null;
    }
    $pane
    .off("transitionend webkitTransitionEnd") // onPaneShown
    .on("transitionend webkitTransitionEnd", function(e) {
      if (e.target.classList.contains("kifi-pane")) {
        var $pane = $(e.target);
        $pane.find(".kifi-pane-box").triggerHandler("kifi:remove");
        $pane.remove();
        $html.removeClass("kifi-pane-parent");
      }
    });
    $pane = paneHistory = null;
    var $html = $("html").removeClass("kifi-with-pane");
  }

  const populatePane = {
    general: function($box) {
      api.port.emit("get_keepers", function(o) {
        api.require("scripts/general.js", function() {
          generalPane.render($box, {
            kept: o.kept,
            keepers: pick(o.keepers, 7),
            keepersCaptionHtml: formatCountHtml(0, o.keepers.length, o.otherKeeps)});
        });
      });
    },
    notices: function($box) {
      api.port.emit("notifications", function(o) {
        api.require("scripts/notices.js", function() {
          noticesPane.render($box.find(".kifi-pane-tall"), o.notifications, o.timeLastSeen, o.numNotVisited);
        });
      });
    },
    comments: function($box) {
      api.port.emit("comments", function(comments) {
        api.port.emit("session", function(session) {
          api.require("scripts/comments.js", function() {
            commentsPane.render($box.find(".kifi-pane-tall"), comments, session);
          });
        });
      });
    },
    threads: function($box) {
      api.port.emit("threads", function(o) {
        api.require("scripts/threads.js", function() {
          threadsPane.render($box.find(".kifi-pane-tall"), o);
          o.threads.forEach(function(th) {
            api.port.emit("thread", {id: th.id});  // preloading
          });
        });
      });
    },
    thread: function($box, locator) {
      var $tall = $box.find(".kifi-pane-tall").css("margin-top", $box.find(".kifi-thread-who").outerHeight());
      var threadId = locator.split("/")[2];
      api.log("[populatePane] getting thread for messages", threadId);
      api.require("scripts/thread.js", function() {
        api.port.emit("thread", {id: threadId, respond: true}, function(th) {
          api.port.emit("session", function(session) {
            threadPane.render($tall, th.id, th.messages, session);
          });
        });
      });
    }
  };

  function formatCountHtml(kept, numFriends, numOthers) {
    return [
        kept ? "You" : null,
        numFriends ? plural(numFriends, "friend") : null,
        numOthers ? plural(numOthers, "other") : null]
      .filter(function(v) {return v})
      .join(" + ");
  }

  function plural(n, term) {
    return n + " " + term + (n == 1 ? "" : "s");
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
    return function(o) {return o.id == id};
  }

  api.port.on({
    kept: function(o) {
      if ($slider) updateKeptDom($slider.find(".kifi-slider2-keep"), o.kept);
    },
    new_notification: function(n) {
      noticesPane.update([n]);
    },
    missed_notifications: function(arr) {
      noticesPane.update(arr);
    },
    notifications_visited: function(o) {
      noticesPane.update(o);
    },
    all_notifications_visited: function(o) {
      noticesPane.update(o);
    },
    comment: function(o) {
      commentsPane.update(o.comment, o.userId);
    },
    thread_info: function(o) {
      threadsPane.update(o.thread, o.read);
    },
    threads: function(o) {
      threadsPane.updateAll(o.threads, o.readTimes, o.userId);
    },
    message: function(o) {
      threadsPane.update(o.thread, o.read);
      threadPane.update(o.thread.id, o.message, o.userId);
    },
    thread: function(o) {
      threadPane.updateAll(o.id, o.messages, o.userId);
    },
    counts: function(o) {
      if (!$slider) return;
      var $btns = $slider.find(".kifi-slider2-dock-btn");
      [[".kifi-slider2-notices", o.n],
       [".kifi-slider2-comments", o.c],
       [".kifi-slider2-messages", o.m]].forEach(function(a) {
        $btns.filter(a[0]).find(".kifi-count")
          .text(a[1] || "")
          .css("display", a[1] ? "" : "none");
      });
    }});

  // the keeper API
  return {
    show: function(trigger) {  // trigger is for the event log (e.g. "tile", "auto", "scroll")
      if ($slider) {
        api.log("[show] already showing");
      } else {
        api.log("[show]", trigger);
        if (trigger == "tile") {
          showSlider(trigger);
        } else if (!lastShownAt) { // auto-show only if not already shown
          showSlider(trigger, function() {
            idleTimer.start(5000);
          });
        }
      }
    },
    showPane: function(trigger, locator) {
      api.log("[showPane]", trigger, locator);
      showPane(locator);
    },
    togglePane: function(trigger, locator) {
      if ($pane && (!locator || paneHistory[0] == locator)) {
        api.log("[togglePane] hiding", locator || "");
        hidePane();
      } else {
        api.log("[togglePane] showing", locator || "");
        showPane(locator || "/general");
      }
    },
    showKeepers: function(keepers, otherKeeps) {
      if (lastShownAt) return;
      var $tile = $(tile).bindHover(function(configureHover) {
        // TODO: preload friend pictures
        render("html/metro/keepers.html", {
          keepers: pick(keepers, 8),
          captionHtml: formatCountHtml(0, keepers.length, otherKeeps)
        }, function(html) {
          configureHover(html, {showDelay: 0, hideDelay: 1e9, click: "hide"});
        });
      });
      $tile.triggerHandler("mouseover.bindHover");
      setTimeout($tile.triggerHandler.bind($tile, "mousedown.bindHover"), 2500);
    }};
}();
