// @require styles/metro/slider2.css
// @require styles/friend_card.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/jquery-showhover.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/render.js

jQuery.fn.layout = function() {
  return this.each(function() {this.clientHeight});  // forces layout
};

const noPane = {update: $.noop};
var generalPane, commentsPane = noPane, threadsPane = noPane, threadPane = noPane;  // set when api.require'd
slider2 = function() {
  var $tile = $("#kifi-tile"), $slider, $pane, lastShownAt;

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
    $tile.remove();
    $("html").removeClass("kifi-with-pane kifi-pane-parent");
    document.removeEventListener("keydown", onKeyDown, true);
  });

  function createSlider(callback) {
    var kept = $tile[0].dataset.kept;
    var counts = JSON.parse($tile[0].dataset.counts);
    api.log("[createSlider] kept:", kept || "no", "counts:", counts);

    render("html/metro/slider2.html", {
      "bgUrl": api.url("images/metro/slider.png"),
      "isKept": kept,
      "isPrivate": kept == "private",
      "noticesCount": -counts.n,
      "commentsUnread": counts.c < 0,
      "commentCount": Math.abs(counts.c),
      "messagesUnread": counts.m < 0,
      "messageCount": Math.abs(counts.m)
    }, function(html) {
      // attach event bindings
      $slider = $(html)
      .mouseout(function(e) {
        if (!$pane) {
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
      }).on("click", ".kifi-slider2-keep-btn", function(e) {
        if (e.target !== this) return;
        $(this).showHover("destroy");
        var el = this.parentNode;
        if (el.classList.contains("kifi-unkept")) {
          keepPage(el, "public");
        } else {
          unkeepPage(el);
        }
        this.classList.add("kifi-hoverless");
      }).on("mouseover", ".kifi-slider2-keep-btn", function(e) {
        if (e.target !== this) {
          this.classList.add("kifi-hoverless");
        }
        if ((e.target === this || e.target.parentNode === this) && !$pane) {
          var btn = this;
          api.port.emit("get_keepers", function(o) {
            if ((o.keepers.length || o.otherKeeps) && !$pane) {
              $(btn).showHover({
                reuse: false,
                showDelay: 250,
                hideDelay: 800,
                fadesOut: true,
                recovery: Infinity,
                create: function(callback) {
                  render("html/metro/keepers.html", {
                    link: true,
                    keepers: pick(o.keepers, 8),
                    anyKeepers: o.keepers.length,
                    captionHtml: formatCountHtml(o.kept, o.keepers.length, o.otherKeeps)
                  }, function(html) {
                    callback($("<div class=kifi-slider2-tip>").html(html).data("keepers", o.keepers));
                  });
                }});
            }
          });
        }
      }).on("mouseenter", ".kifi-slider2-keeper", function() {
        var $a = $(this).showHover({
          hideDelay: 600,
          fadesOut: true,
          create: function(callback) {
            var i = $a.prevAll(".kifi-slider2-keeper").length;
            var friend = ($a.closest(".kifi-slider2-tip").data("keepers") || [])[i];
            if (!friend) return;
            render("html/friend_card.html", {
              name: friend.firstName + " " + friend.lastName,
              facebookId: friend.facebookId,
              iconsUrl: api.url("images/social_icons.png")
            }, callback);
            api.port.emit("get_num_mutual_keeps", {id: friend.id}, function gotNumMutualKeeps(o) {
              $a.find(".kifi-kcard-mutual").text(plural(o.n, "mutual keep"));
            });
          }});
      }).on("mouseout", ".kifi-slider2-keep-btn", function() {
        this.classList.remove("kifi-hoverless");
      }).on("hover:hide", ".kifi-slider2-keep-btn", function() {
        document.documentElement.addEventListener("mousemove", function f(e) {
          this.removeEventListener("mousemove", f, true);
          if ($slider && !$slider[0].contains(e.target)) {
            hideSlider("mouseout");
          }
        }, true);
      }).on("mouseenter", ".kifi-slider2-lock", function(e) {
        if ($pane || e.target !== this) return;
        $(this).showHover({
          reuse: false,
          showDelay: 250,
          fadesOut: true,
          recovery: Infinity,
          create: function(callback) {
            var html = this.parentNode.classList.contains("kifi-unkept") ?
              "keep privately<br>(so only you can see it)" :
              this.parentNode.classList.contains("kifi-public") ? "make private" : "make public";
            callback($("<div class=kifi-slider2-tip>").html(html), function(w) {this.style.left = 8 - w / 2 + "px"});
          }});
      }).on("click", ".kifi-slider2-lock", function(e) {
        if (e.target !== this) return;
        $(this).showHover("destroy");
        var el = this.parentNode;
        if (el.classList.contains("kifi-unkept")) {
          keepPage(el, "private");
        } else {
          togglePrivate(el);
        }
      }).on("click", ".kifi-slider2-x", function() {
        if ($pane) {
          hidePane(true);
        }
      }).on("click", ".kifi-slider2-dock-btn", function() {
        var pane = $(this).data("pane");
        if ($pane) {
          if (pane == $pane.data("pane")) {
            hidePane(true);
          } else {
            showPane(pane);
          }
        } else if (!$("html").hasClass("kifi-pane-parent")) { // ensure it's finished hiding
          idleTimer.kill();
          showPane(pane);
        }
      });
      $tile.addClass("kifi-behind-slider");
      callback();
    });
  }

  function showSlider(trigger, callback) {
    api.log("[showSlider]", trigger);

    lastShownAt = +new Date;

    createSlider(function() {
      $slider.appendTo("html").layout().addClass("kifi-wide kifi-growing")
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
    $slider.addClass("kifi-hiding").on("transitionend webkitTransitionEnd", function(e) {
      if (e.target.classList.contains("kifi-slider2") && e.originalEvent.propertyName == "opacity") {
        $(e.target).remove();
        $tile.removeClass("kifi-behind-slider");
      }
    });
    $slider = null;
    logEvent("slider", "sliderClosed", {trigger: trigger, shownForMs: String(new Date - lastShownAt)});
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

  function openDeepLink(locator) {
    var loc = locator.split("/");
    if (loc[1] == "messages") {
      if (loc[2]) {  // loc[2] can be id of any message (not necessarily a parent)
        api.log("[openDeepLink] requiring thread for recipients");
        api.port.emit("thread", {id: loc[2], respond: true}, function(th) {
          showPane("thread", false, th.messages[0].recipients, th.id);
        });
      } else {
        showPane("threads");
      }
    } else {
      showPane(loc[1]);  // comments, general
    }
  }

  const createPaneTemplateParams = {
    general: function() {
      return {
        title: document.title,
        url: location.href};
    },
    thread: function(recipients) {
      return {
        recipients: recipients,
        numRecipients: recipients.length > 1 ? recipients.length : null};
    }
  };

  function showPane(pane, back, paramsArg, populateArg) {
    api.log("[showPane]", pane, back ? "back" : "");
    var params = (createPaneTemplateParams[pane] || Object)(paramsArg);
    if ($pane) {
      render("html/metro/pane_" + pane + ".html", params, function(html) {
        back = back || pane == "general";
        var $cubby = $pane.find(".kifi-pane-cubby"), w = $cubby[0].offsetWidth, d = w + 6;
        var $boxes = $("<div class=kifi-pane-boxes>").css({
          width: w + d,
          transform: "translate(" + (back ? -d : 0) + "px,0)"}).appendTo($cubby.css("overflow", "hidden"));
        var $old = $cubby.find(".kifi-pane-box").css({left: back ? d : 0, width: w}).appendTo($boxes);
        var $new = $(html).css({left: back ? 0 : d, width: w}).appendTo($boxes);
        $boxes.layout().css("transform", "translate(" + (back ? 0 : -d) + "px,0)")
        .on("transitionend webkitTransitionEnd", function() {
          $old.triggerHandler("kifi:remove");
          $old.remove();
          $new.detach().css({left: "", width: ""}).appendTo($cubby).data("shown", true).triggerHandler("kifi:shown");
          $boxes.remove();
          $cubby.css("overflow", "");
        });
        $pane.data("pane", pane);
        populatePane[pane]($new, populateArg);
      });
    } else {
      var bringSlider = !$slider;
      if (bringSlider) {
        createSlider(function() {
          $slider.addClass("kifi-wide");
        });
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
          $pane = $(html).data("pane", pane).append(bringSlider ? $slider : null).appendTo($html).layout()
          .on("transitionend webkitTransitionEnd", function f(e) {
            $pane.off("transitionend webkitTransitionEnd", f);
            $box.data("shown", true).triggerHandler("kifi:shown");
            if (!bringSlider) $pane.append($slider);
          })
          .on("keydown", ".kifi-pane-search", function(e) {
            var q;
            if (e.which == 13 && (q = this.value.trim())) {
              window.open("https://www.google.com/search?q=" + encodeURIComponent(q).replace(/%20/g, "+"));
            }
          })
          .on("click", ".kifi-pane-back", function() {
            showPane($(this).data("pane") || "general", true);
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
            $tile.toggle(!checked);
            api.port.emit("suppress_on_site", checked);
            setTimeout(function() {
              if (checked) {
                hidePane();
              } else {
                $hide.closest(".kifi-pane-head-settings-menu").triggerHandler("kifi:hide");
              }
            }, 150);
          })
          .on("click", ".kifi-pane-action", function() {
            var $n = $pane.find(".kifi-not-done"), d = $n.data();
            clearTimeout(d.t);
            $n.remove().removeClass("kifi-showing").appendTo($pane).layout().addClass("kifi-showing");
            d.t = setTimeout($n.removeClass.bind($n, "kifi-showing"), 1000);
          })
          .on("kifi:show-pane", function(e, pane, paramsArg, populateArg) {
            showPane(pane, false, paramsArg, populateArg);
          })
          .on("mousedown click keydown keypress keyup", function(e) {
            e.stopPropagation();
          });
          $html.addClass("kifi-with-pane");
          var $box = $pane.find(".kifi-pane-box");
          populatePane[pane]($box, populateArg);
        });
      });
    }
  }

  function hidePane(leaveSlider) {
    api.log("[hidePane]");
    if (leaveSlider) {
      $slider.appendTo("html").layout();
    } else {
      $slider = null;
      $tile.removeClass("kifi-behind-slider");
    }
    $pane.on("transitionend webkitTransitionEnd", function(e) {
      if (e.target.classList.contains("kifi-pane")) {
        var $pane = $(e.target);
        $pane.find(".kifi-pane-box").triggerHandler("kifi:remove");
        $pane.remove();
        $html.removeClass("kifi-pane-parent");
      }
    });
    $pane = null;
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
      api.port.emit("session", function(session) {
        api.require("scripts/notices.js", function() {
          renderNotices($box.find(".kifi-pane-tall"));
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
    thread: function($box, threadId) {
      var $tall = $box.find(".kifi-pane-tall").css("margin-top", $box.find(".kifi-thread-who").outerHeight());
      api.log("[populatePane] requiring thread for messages");
      api.port.emit("thread", {id: threadId, respond: true}, function(th) {
        api.port.emit("session", function(session) {
          api.require("scripts/thread.js", function() {
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

  api.port.on({
    kept: function(o) {
      if ($slider) updateKeptDom($slider.find(".kifi-slider2-keep"), o.kept);
    },
    comment: function(comment) {
      api.port.emit("session", function(session) {
        commentsPane.update(comment, session.userId);
      });
    },
    thread_info: function(o) {
      threadsPane.update(o.thread, o.read);
    },
    message: function(o) {
      api.port.emit("session", function(session) {
        threadsPane.update(o.thread, o.read);
        threadPane.update(o.thread, o.message, session.userId);
      });
    },
    counts: function(o) {
      if (!$slider) return;
      var $btns = $slider.find(".kifi-slider2-dock-btn");
      [[".kifi-slider2-notices", o.n],
       [".kifi-slider2-comments", o.c],
       [".kifi-slider2-threads", o.m]].forEach(function(a) {
        $btns.filter(a[0]).find(".kifi-count")
          .toggleClass("kifi-unread", a[1] < 0)
          .text(Math.abs(a[1]) || "")
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
      openDeepLink(locator);
    },
    togglePane: function(trigger) {
      if ($pane) {
        api.log("[togglePane] hiding");
        hidePane();
      } else {
        api.log("[togglePane] showing");
        showPane("general");
      }
    },
    showKeepers: function(keepers, otherKeeps) {
      if (lastShownAt) return;
      $tile.showHover({
        reuse: false,
        showDelay: 0,
        hideDelay: 1e9,
        fadesOut: true,
        recovery: Infinity,
        create: function(callback) {
          // TODO: preload friend pictures
          render("html/metro/keepers.html", {
            keepers: pick(keepers, 8),
            anyKeepers: keepers.length,
            captionHtml: formatCountHtml(0, keepers.length, otherKeeps)
          }, function(html) {
            callback($("<div class=kifi-slider2-tip>").html(html));
          });
        }});
      setTimeout($tile.triggerHandler.bind($tile, "click.showHover"), 2000);
    }};
}();
