// @require styles/metro/tile.css
// @require styles/metro/slider2.css
// @require styles/friend_card.css
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery-bindhover.js
// @require scripts/lib/mustache.js
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
        $(this).animate({scrollTop: sT + d}, 40 * Math.log(d));
      }
    }
  });
};

const CO_KEY = /^Mac/.test(navigator.platform) ? "⌘" : "Ctrl";
const panes = {};

var slider2 = function() {
  var $slider, $pane, paneHistory, lastShownAt;

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
      } else {
        return;
      }
      e.preventDefault();
      e.stopPropagation();
    }
  }

  api.onEnd.push(function() {
    log("[slider2:onEnd]")();
    $pane && $pane.remove();
    $slider && $slider.remove();
    $(tile).remove();
    $("html").removeClass("kifi-with-pane kifi-pane-parent");
    document.removeEventListener("keydown", onKeyDown, true);
  });

  function createSlider(callback, locator) {
    var kept = tile && tile.dataset.kept;
    var counts = JSON.parse(tile && tile.dataset.counts || '{"n":0,"m":0}');
    log("[createSlider] kept: %s counts: %o", kept || "no", counts)();

    render("html/metro/slider2", {
      "bgDir": api.url("images/keeper"),
      "isKept": kept,
      "isPrivate": kept == "private",
      "noticesCount": Math.max(0, counts.n - counts.m),
      "messageCount": counts.m,
      "atNotices": "/notices" == locator,
      "atMessages": /^\/messages/.test(locator)
    }, function(html) {
      // attach event bindings
      $slider = $(html);
      var data = $slider.data();
      $slider.mouseout(function(e) {
        if (data.dragTimer) {
          startDrag(data);
        } else if (!$pane && !data.dragStarting && !data.$dragGlass) {
          if (e.relatedTarget) {
            if (!this.contains(e.relatedTarget)) {
              log("[slider.mouseout] hiding")();
              hideSlider("mouseout");
            }
          } else {  // out of window
            log("[slider.mouseout] out of window")();
            document.documentElement.addEventListener("mouseover", function f(e) {
              this.removeEventListener("mouseover", f, true);
              log("[document.mouseover]", e.target)();
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
          log("[mouseup]")();
          clearTimeout(data.dragTimer), delete data.dragTimer;
          delete data.dragStarting;
        }
        delete data.mousedownEvent;
      }).on("mousewheel", function(e) {
        e.preventDefault(); // crbug.com/151734
      }).on("click", ".kifi-slider2-keep-btn", function(e) {
        if (e.target !== this) return;
        keepPage("public");
        this.classList.add("kifi-hoverless");
      }).on("click", ".kifi-slider2-kept-btn", function(e) {
        if (e.target !== this) return;
        unkeepPage();
        this.classList.add("kifi-hoverless");
      }).on("mouseover", ".kifi-slider2-keep-card", function() {
        if ($slider.hasClass("kifi-auto")) {
          growSlider("kifi-auto", "kifi-wide");
        }
      }).on("mouseover", ".kifi-slider2-keep-btn>.kifi-slider2-tip,.kifi-slider2-kept-btn>.kifi-slider2-tip", function() {
        this.parentNode.classList.add("kifi-hoverless");
      }).bindHover(".kifi-slider2-keep-btn,.kifi-slider2-kept-btn", function(configureHover) {
        var btn = this;
        api.port.emit("get_keepers", function(o) {
          if (o.keepers.length) {
            render("html/metro/keepers", {
              link: true,
              keepers: pick(o.keepers, 8),
              captionHtml: formatCountHtml(o.kept, o.keepers.length, o.otherKeeps)
            }, function(html) {
              configureHover($(html).data("keepers", o.keepers), {
                mustHoverFor: 700,
                canLeaveFor: 800,
                hideAfter: 4000,
                click: "hide",
                position: positionIt});
            });
          } else {
            render("html/keeper/titled_tip", {
              title: (o.kept ? "Unkeep" : "Keep") + " (" + CO_KEY + "+Shift+K)",
              html: o.kept ? "Un-keeping this page will<br>remove it from your keeps." :
                "Keeping this page helps you<br>easily find it later."
            }, function(html) {
              configureHover(html, {
                mustHoverFor: 700,
                hideAfter: 4000,
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
        render("html/friend_card", {
          name: friend.firstName + " " + friend.lastName,
          id: friend.id,
          iconsUrl: api.url("images/social_icons.png")
        }, function(html) {
          var $el = $(html);
          configureHover($el, {mustHoverFor: 100, canLeaveFor: 600, hideAfter: 4000, click: "toggle"});
          api.port.emit("get_networks", friend.id, function(networks) {
            for (nw in networks) {
              $el.find('.kifi-kcard-nw-' + nw)
                .toggleClass('kifi-on', networks[nw].connected)
                .attr('href', networks[nw].profileUrl || null);
            }
          });
        });
      }).on("mouseout", ".kifi-slider2-keep-btn,.kifi-slider2-kept-btn", function() {
        this.classList.remove("kifi-hoverless");
      }).bindHover(".kifi-slider2-keep-lock,.kifi-slider2-kept-lock", function(configureHover) {
        var $card = $(this).closest(".kifi-slider2-keep-card");
        var kept = !$card.hasClass("kifi-unkept");
        var publicly = kept && $card.hasClass("kifi-public");
        var title = !kept ?
          "Keep Privately" : publicly ?
          "Make Private" :
          "Make Public";
        var html = !kept ?
          "Keeping this privately allows you<br>to find this page easily without<br>letting anyone know you kept it." : publicly ?
          "This keep is public. Making it private<br>allows you to find it easily without<br>letting anyone know you kept it." :
          "This keep is private. Making it<br>public allows your friends to<br>discover that you kept it.";
        render("html/keeper/titled_tip", {title: title, html: html}, function(html) {
          configureHover(html, {
            mustHoverFor: 700,
            hideAfter: 4000,
            click: "hide",
            position: function(w) {
              this.style.left = 8 - w / 2 + "px";
            }});
        });
      }).on("click", ".kifi-slider2-keep-lock", function(e) {
        if (e.target === this) keepPage("private");
      }).on("click", ".kifi-slider2-kept-lock", function(e) {
        if (e.target === this) toggleKeep($(this).closest(".kifi-slider2-keep-card").hasClass("kifi-public") ? "private" : "public");
      }).bindHover(".kifi-slider2-x", function(configureHover) {
        this.style.overflow = "visible";
        configureHover({mustHoverFor: 700, hideAfter: 2500, click: "hide"});
      }).on("click", ".kifi-slider2-x", function() {
        if ($pane) {
          hidePane(true);
        }
      }).bindHover(".kifi-slider2-dock-btn", function(configureHover) {
        var tip = {
          n: ["Notifications", "View all of your notifications.<br>Any new ones are highlighted."],
          m: ["Private Messages (" + CO_KEY + "+Shift+M)", "Send this page to friends<br>and start a discussion."]
        }[this.dataset.loc.substr(1,1)];
        render("html/keeper/titled_tip", {title: tip[0], html: tip[1]}, function(html) {
          configureHover(html, {
            mustHoverFor: 700,
            hideAfter: 4000,
            click: "hide",
            position: function(w) {
              this.style.left = 21 - w / 2 + "px";
            }});
        });
      }).on("mousedown", ".kifi-slider2-dock-btn", function(e) {
        e.preventDefault();
      }).on("click", ".kifi-slider2-dock-btn", function() {
        var locator = this.dataset.loc;
        if ($pane) {
          if (locator == paneHistory[0]) {
            hidePane(true);
          } else {
            showPane(locator);
          }
        } else if (!$("html").hasClass("kifi-pane-parent")) { // ensure it's finished hiding
          showPane(locator);
        }
      });
      callback();
    });
  }

  function showSlider(trigger, callback) {
    log("[showSlider]", trigger)();

    lastShownAt = Date.now();
    $slider = $();  // creation in progress (prevents multiple)

    createSlider(function() {
      $slider.prependTo(tile);

      logEvent("slider", "sliderShown", withUrls({trigger: trigger, onPageMs: String(lastShownAt - tile.dataset.t0)}));
      api.port.emit("keeper_shown");

      callback && callback();
    });
  }

  function growSlider(fromClass, toClass) {
    $slider.addClass(fromClass).layout().addClass(toClass + " kifi-growing").removeClass(fromClass)
    .on("transitionend", function f(e) {
      if (e.target === this) {
        $(this).off("transitionend", f).removeClass("kifi-growing");
      }
    });
  }

  // trigger is for the event log (e.g. "key", "icon")
  function hideSlider(trigger) {
    log("[hideSlider]", trigger)();
    idleTimer.kill();
    $slider.addClass("kifi-hiding")
    .off("transitionend")
    .on("transitionend", function(e) {
      if (e.target === this && e.originalEvent.propertyName == "opacity") {
        var css = JSON.parse(tile.dataset.pos || 0);
        if (css && !tile.style.top && !tile.style.bottom) {
          var y = css.top >= 0 ? window.innerHeight - css.top - 54 : (css.bottom || 0);
          css.transition = "none";
          css.transform = "translate(0," + y + "px)";
          $(tile).css(css)
          .layout().css({transition: "", "transition-duration": Math.min(1, 32 * Math.log(y)) + "ms"})
          .find(".kifi-count").css("zoom", 1); // webkit repaint workaround
          tile["kifi:position"]();
          $(tile).on("transitionend", function end() {
            $(this).off("transitionend", end).css("transition-duration", "");
          });
        }
        $slider.remove(), $slider = null;
      }
    });
    logEvent("slider", "sliderClosed", {trigger: trigger, shownForMs: String(new Date - lastShownAt)});
  }

  function startDrag(data) {
    log("[startDrag]")();
    clearTimeout(data.dragTimer);
    delete data.dragTimer;
    data.dragStarting = true;
    api.require("scripts/lib/jquery-ui-draggable.min.js", function() {
      if (data.dragStarting) {
        delete data.dragStarting;
        log("[startDrag] installing draggable")();
        data.$dragGlass = $("<div class=kifi-slider2-drag-glass>").mouseup(stopDrag).appendTo(tile.parentNode);
        $(tile).draggable({axis: "y", containment: "window", scroll: false, stop: stopDrag})[0]
          .dispatchEvent(data.mousedownEvent); // starts drag
      }
      function stopDrag() {
        var r = tile.getBoundingClientRect(), fromBot = window.innerHeight - r.bottom;
        var pos = r.top >= 0 && r.top < fromBot ? {top: r.top} : {bottom: Math.max(0, fromBot)};
        log("[stopDrag] top:", r.top, "bot:", r.bottom, JSON.stringify(pos))();
        $(tile).draggable("destroy");
        data.$dragGlass.remove();
        delete data.$dragGlass;
        tile.dataset.pos = JSON.stringify(pos);
        $(tile).css($.extend({top: "auto", bottom: "auto"}, pos));
        api.port.emit("set_keeper_pos", {host: location.hostname, pos: pos});
      }
    });
  }

  var idleTimer = {
    start: function(ms) {
      log("[idleTimer.start]", ms, "ms")();
      clearTimeout(this.timeout), this.timeout = setTimeout(hideSlider.bind(null, "idle"), ms);
      $slider.on("mouseenter.idle", $.proxy(this, "kill"));
    },
    kill: function() {
      if (this.timeout) {
        log("[idleTimer.kill]")();
        clearTimeout(this.timeout), delete this.timeout;
        $slider && $slider.off(".idle");
      }
    }};

  function keepPage(how) {
    log("[keepPage]", how)();
    updateKeptDom(how);
    api.port.emit("keep", withUrls({title: document.title, how: how}));
    logEvent("slider", "keep", {isPrivate: how == "private"});
  }

  function unkeepPage() {
    log("[unkeepPage]", document.URL)();
    updateKeptDom("");
    api.port.emit("unkeep", withUrls({}));
    logEvent("slider", "unkeep");
  }

  function toggleKeep(how) {
    log("[toggleKeep]", how)();
    updateKeptDom(how);
    api.port.emit("set_private", withUrls({private: how == "private"}));
  }

  function updateKeptDom(how) {
    if ($slider) $slider.find(".kifi-slider2-keep-card").removeClass("kifi-unkept kifi-private kifi-public").addClass("kifi-" + (how || "unkept"));
    if ($pane) $pane.find(".kifi-pane-kept").toggleClass("kifi-kept", !!how);
  }

  function toPaneName(locator) {
    var name = locator.match(/[a-z]+\/?/)[0];
    return {messages: "threads", "messages/": "thread"}[name] || name;
  }

  const paneIdxs = ["notices","threads","thread"];
  function toPaneIdx(name) {
    return paneIdxs.indexOf(name);
  }

  const createTemplateParams = {
    thread: function(cb, locator, participants) {
      var id = locator.split("/")[2];
      if (participants) {
        respond(participants, locator);
      } else {
        log("[createTemplateParams] getting thread for participants")();
        api.port.emit("thread", {id: id, respond: true}, function(th) {
          respond(th.participants, "/messages/" + th.id);
        });
      }
      function respond(p, canonicalLocator) {
        cb({participants: p, numParticipants: p.length > 1 ? p.length : null}, canonicalLocator);
      }
    }};

  function showPane(locator, back, paramsArg) {
    log("[showPane]", locator, back ? "back" : "")();
    var pane = toPaneName(locator);
    (createTemplateParams[pane] || function(cb) {cb({backButton: paneHistory && paneHistory[back ? 2 : 0]})})(function(params, canonicalLocator) {
      var loc = canonicalLocator || locator;
      if (loc !== (paneHistory && paneHistory[0])) {
        showPane2(loc, back, pane, params);
      }
    }, locator, paramsArg);
  }

  function showPane2(locator, back, pane, params) {  // only called by showPane
    log("[showPane2]", locator, pane)();
    if ($pane) {
      var left = back || toPaneIdx(pane) < toPaneIdx(toPaneName(paneHistory[0]));
      $slider.find(".kifi-at").removeClass("kifi-at").end()
        .find(".kifi-slider2-" + locator.split("/")[1]).addClass("kifi-at");
      render("html/metro/pane_" + pane, params, function(html) {
        var $cubby = $pane.find(".kifi-pane-cubby").css("overflow", "hidden");
        var $cart = $cubby.find(".kifi-pane-box-cart").addClass(left ? "kifi-back" : "kifi-forward");
        var $old = $cart.find(".kifi-pane-box");
        var $new = $(html)[left ? "prependTo" : "appendTo"]($cart).layout();
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
          window.dispatchEvent(new Event("resize"));  // for other page scripts
        });
        if (back) {
          paneHistory.shift();
          paneHistory[0] = locator;  // usually unnecessary (already the same)
        } else {
          paneHistory.unshift(locator);
        }
        api.port.emit("pane", {old: $pane[0].dataset.locator, new: locator});
        $pane[0].dataset.locator = locator;
        populatePane($new, pane, locator);
      });
    } else {
      paneHistory = [locator];
      var bringSlider = !$slider;
      if (bringSlider) {
        createSlider(function() {
          $slider.addClass("kifi-wide");
        }, locator);
      } else {
        idleTimer.kill();
        $slider.find(".kifi-slider2-" + locator.split("/")[1]).addClass("kifi-at");
      }
      api.port.emit("session", function(session) {
        api.require("styles/metro/pane.css", function() {
          render("html/metro/pane", $.extend(params, {
            site: location.hostname,
            kifiLogoUrl: api.url("images/kifi_logo.png"),
            session: session
          }), {
            pane: "pane_" + pane
          },
          function(html) {
            $("html").addClass("kifi-pane-parent");
            $pane = $(html);
            $pane[0].dataset.locator = locator;
            api.port.emit("pane", {new: locator});
            if (bringSlider) {
              $pane.append($slider).appendTo(tile.parentNode);
            } else {
              $pane.insertBefore(tile);
              $(tile).css("transform", "translate(0," + (window.innerHeight - tile.getBoundingClientRect().bottom) + "px)");
            }
            $pane.layout()
            .on("transitionend", function onPaneShown(e) {
              if (e.target !== this) return;
              $pane.off("transitionend", onPaneShown);
              if (!bringSlider) {
                $pane.before(tile);
                $slider.appendTo($pane);
              }
              $box.data("shown", true).triggerHandler("kifi:shown");
            })
            .bindHover(".kifi-pane-head-logo", {mustHoverFor: 700, hideAfter: 2500, click: "hide"})
            .bindHover(".kifi-pane-head-feedback", function(configureHover) {
              render("html/keeper/titled_tip", {
                dir: "below",
                title: "Give Us Feedback",
                html: "Tell us your ideas for Kifi<br>or report an issue."
              }, function(html) {
                configureHover(html, {mustHoverFor: 700, hideAfter: 4000, click: "hide"});
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
              render("html/keeper/titled_tip", {
                dir: "below",
                title: "Settings",
                html: "Customize your Kifi<br>experience."
              }, function(html) {
                configureHover(html, {mustHoverFor: 700, hideAfter: 3000, click: "hide"});
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
            .on("mouseup", ".kifi-pane-settings-sign-out", function(e) {
              e.preventDefault();
              api.port.emit("deauthenticate");
              $(tile).hide();
              setTimeout(function() {
                hidePane();
                $('<div class="kifi-signed-out-tooltip"><b>Logged out</b><br>To log back in to Kifi, click the <img class="kifi-signed-out-icon" src="' + api.url('images/keep.faint.png') + '"> button above.</div>')
                  .appendTo('body').delay(6000).fadeOut(1000, function() { $(this).remove(); });
              }, 150);
              return;
            })
            .on("keydown", ".kifi-pane-search", function(e) {
              var q, el = this;
              if (e.which == 13 && (q = el.value.trim())) {
                api.port.emit("session", function(session) {
                  var uri = session ? "https://www.kifi.com/find?q=" : "https://www.google.com/search?q=";
                  window.open(uri + encodeURIComponent(q).replace(/%20/g, "+"));
                  el.value = "";
                });
              }
            })
            .on("click", ".kifi-pane-back", function() {
              var loc = paneHistory[1] || this.dataset.loc;
              if (loc) {
                showPane(loc, true);
              }
            })
            .on("kifi:show-pane", function(e, loc, paramsArg) {
              showPane(loc, false, paramsArg);
            })
            .on("mousedown click keydown keypress keyup", function(e) {
              e.stopPropagation();
            });
            $("html").addClass("kifi-with-pane");
            var $box = $pane.find(".kifi-pane-box");
            populatePane($box, pane, locator);
          });
        });
      });
    }
  }

  function hidePane(leaveSlider) {
    log("[hidePane]")();
    if (leaveSlider) {
      $(tile).css({top: "", bottom: "", transform: ""}).insertAfter($pane);
      $slider.prependTo(tile).layout();
      $slider.find(".kifi-at").removeClass("kifi-at");
      $slider.find(".kifi-slider2-x").css("overflow", "");
    } else {
      $(tile).css("transform", "");
      $slider = null;
    }
    $pane
    .off("transitionend") // onPaneShown
    .on("transitionend", function(e) {
      if (e.target === this) {
        var $pane = $(this);
        $pane.find(".kifi-pane-box").triggerHandler("kifi:remove");
        $pane.remove();
        $("html").removeClass("kifi-pane-parent");
        window.dispatchEvent(new Event("resize"));  // for other page scripts
      }
    });
    api.port.emit("pane", {old: $pane[0].dataset.locator});
    $pane = paneHistory = null;
    $("html").removeClass("kifi-with-pane");
  }

  function populatePane($box, name, locator) {
    var $tall = $box.find(".kifi-pane-tall");
    if (name == "thread") {
      $tall.css("margin-top", $box.find(".kifi-thread-who").outerHeight());
    }
    api.require("scripts/" + name + ".js", function() {
      panes[name].render($tall, locator);
    });
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
      updateKeptDom(o.kept);
    },
    counts: function(o) {
      if (!$slider) return;
      var $btns = $slider.find(".kifi-slider2-dock-btn");
      [[".kifi-slider2-notices", Math.max(0, o.n - o.m)],
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
        log("[show] already showing")();
      } else {
        log("[show]", trigger)();
        if (trigger == "tile") {
          showSlider(trigger, growSlider.bind(null, "", "kifi-wide"));
        } else if (!lastShownAt) { // auto-show only if not already shown
          showSlider(trigger, function() {
            growSlider("kifi-tiny", "kifi-auto");
            idleTimer.start(5000);
          });
        }
      }
    },
    showPane: function(trigger, locator) {
      log("[showPane]", trigger, locator)();
      showPane(locator);
    },
    togglePane: function(trigger, locator) {
      if ($pane && (!locator || paneHistory[0] == locator)) {
        log("[togglePane] hiding", locator || "")();
        hidePane();
      } else {
        log("[togglePane] showing", locator || "")();
        showPane(locator || "/notices");
      }
    },
    hidePane: function(leaveSlider) {
      if ($pane) {
        hidePane(leaveSlider);
      }
    },
    showKeepers: function(keepers, otherKeeps) {
      if (lastShownAt) return;
      var $tile = $(tile).bindHover(function(configureHover) {
        // TODO: preload friend pictures
        render("html/metro/keepers", {
          keepers: pick(keepers, 8),
          captionHtml: formatCountHtml(0, keepers.length, otherKeeps)
        }, function(html) {
          configureHover(html, {mustHoverFor: 0, canLeaveFor: 1e9, click: "hide"});
        });
      }).on("transitionend", function unbindHover(e) {
        if (!$tile.hasClass("kifi-hover-showing") && e.target.classList.contains("kifi-slider2-tip") && e.originalEvent.propertyName == "opacity") {
          $tile.off("transitionend", unbindHover).bindHover("destroy");
        }
      });
      $tile.triggerHandler("mouseover.bindHover");
      setTimeout($tile.triggerHandler.bind($tile, "mousedown.bindHover"), 3000);
    }};
}();
