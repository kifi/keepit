// @require styles/metro.css
// #require styles/friend_card.css
// #require styles/comments.css
// @require scripts/lib/jquery-1.8.2.min.js
// #require scripts/lib/jquery-ui-1.9.1.custom.min.js
// @require scripts/lib/jquery-showhover.js
// #require scripts/lib/jquery-tokeninput-1.6.1.min.js
// #require scripts/lib/jquery.timeago.js
// @require scripts/lib/keymaster.min.js
// #require scripts/lib/lodash.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/render.js
// #require scripts/snapshot.js

jQuery.fn.layout = function() {
  return this.each(function() {this.clientHeight});  // forces layout
};

slider2 = function() {
  var $slider, $pane, info, lastShownAt;

  key("esc", function() {
    if ($pane) {
      hidePane();
    }
    if ($slider) {
      hideSlider("esc");
    }
  });

  function showSlider(o, trigger, locator) {
    info = o = info || o;  // ignore o after first call
    api.log("slider info:", o);

    lastShownAt = +new Date;

    render("html/metro/slider2.html", {
        // "logo": api.url('images/kifilogo.png'),
        // "arrow": api.url('images/triangle_down.31x16.png'),
        // "profilepic": o.session.avatarUrl,
        // "name": o.session.name,
        "bgUrl": api.url("images/metro/slider.png"),
        "isKept": o.kept,
        "isPrivate": o.private,
        // "sensitive": o.sensitive,
        // "site": location.hostname,
        // "neverOnSite": o.neverOnSite,
        "numComments": o.numComments,
        "numMessages": o.numMessages,
        "newComments": o.unreadComments,
        "newMessages": o.unreadMessages,
        // "connected_networks": api.url("images/networks.png")
      }, function(html) {
        if ($slider) {
          api.log("[showSlider] already there");
        } else {
          $(".kifi-slider2").remove();  // e.g. from earlier version
          $slider = $(html).appendTo("html").layout().addClass("kifi-visible kifi-growing")
          .on("transitionend webkitTransitionEnd", function f(e) {
            if (e.target.classList.contains("kifi-slider2")) {
              $(e.target).off("transitionend webkitTransitionEnd", f).removeClass("kifi-growing");
            }
          });

          // attach event bindings
          $slider.mouseout(function(e) {
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
              keepPage(el, false);
            } else {
              unkeepPage(el);
            }
            this.classList.add("kifi-hoverless");
          }).on("mouseover", ".kifi-slider2-keep-btn", function(e) {
            if (e.target !== this) {
              this.classList.add("kifi-hoverless");
            }
            if ((e.target === this || e.target.parentNode === this) && (o.keepers || o.keeps) && !$pane) {
              $(this).showHover({
                reuse: false,
                showDelay: 250,
                hideDelay: 800,
                recovery: Infinity,
                create: function(callback) {
                  // TODO: preload friend pictures
                  render("html/metro/keepers.html", {
                    keepers: shuffle(o.keepers.slice(0, 8)),
                    captionHtml: formatCountHtml(o.kept, o.private, (o.keepers || 0).length, o.otherKeeps)
                  }, function(html) {
                    callback($("<div class=kifi-slider2-tip>").html(html));
                  });
                }});
            }
          }).on("mouseout", ".kifi-slider2-keep-btn", function() {
            this.classList.remove("kifi-hoverless");
          }).on("mouseenter", ".kifi-slider2-lock", function() {
            if ($pane) return;
            $(this).showHover({
              reuse: false,
              showDelay: 250,
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
              keepPage(el, true);
            } else {
              togglePrivate(el);
            }
          }).on("click", ".kifi-slider2-pane,.kifi-slider2-x", function() {
            if ($pane) {
              hidePane();
            } else if (!document.documentElement.classList.contains("kifi-pane-parent")) {
              showPane();
            }
          });

          logEvent("slider", "sliderShown", {trigger: trigger, onPageMs: String(lastShownAt - t0), url: location.href});

          if (locator) {
            openDeepLink(o.session, locator);
          } else if (trigger != "tile") {
            idleTimer.start(5000);
          }
        }
      });
  }

  // trigger is for the event log (e.g. "key", "icon"). pass no trigger if just hiding slider temporarily.
  function hideSlider(trigger) {
    idleTimer.kill();
    $slider.addClass("kifi-hidden").removeClass("kifi-visible").on("transitionend webkitTransitionEnd", function(e) {
      if (e.target.classList.contains("kifi-slider2") && e.originalEvent.propertyName == "opacity" && trigger) {
        $(e.target).remove();
      }
    });
    $slider = null;
    if (trigger) {
      logEvent("slider", "sliderClosed", {trigger: trigger, shownForMs: String(new Date - lastShownAt)});
    }
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

  function keepPage(btn, privately) {
    api.log("[keepPage]", document.URL);

    api.port.emit("set_page_icon", true);
    info.kept = true;
    info.private = privately;
    btn.classList.remove("kifi-unkept");
    btn.classList.add(privately ? "kifi-private" : "kifi-public");
    $(".kifi-pane-kept").addClass("kifi-kept");
    updateTile(true);

    logEvent("slider", "keep", {"isPrivate": privately});

    api.port.emit("add_bookmarks", {
      "url": document.URL,
      "title": document.title,
      "private": privately
    }, function(response) {
      api.log("[keepPage] response:", response);
    });
  }

  function unkeepPage(btn) {
    api.log("[unkeepPage]", document.URL);

    api.port.emit("set_page_icon", false);
    info.kept = false;
    delete info.private;
    btn.classList.remove("kifi-private");
    btn.classList.remove("kifi-public");
    btn.classList.add("kifi-unkept");
    $(".kifi-pane-kept").removeClass("kifi-kept");
    updateTile(false);

    logEvent("slider", "unkeep");

    api.port.emit("unkeep", function(o) {
      api.log("[unkeepPage] response:", o);
    });
  }

  function togglePrivate(btn) {
    var priv = !info.private;
    api.log("[setPrivate]", priv);

    info.private = priv;
    btn.classList.remove("kifi-private", "kifi-public");
    btn.classList.add(priv ? "kifi-private" : "kifi-public");

    api.port.emit("set_private", priv, function(resp) {
      api.log("[setPrivate] response:", resp);
    });
  }

  function showPane() {
    api.log("[showPane]");
    idleTimer.kill();
    render("html/metro/pane.html", {
      title: document.title,
      url: location.href,
      kifiLogoUrl: api.url("images/kifi_logo.png"),
      gearUrl: api.url("images/metro/gear.png"),
      kept: info.kept,
      keepers: shuffle(info.keepers.slice(0, 7)),
      keepersCaptionHtml: formatCountHtml(0, 0, (info.keepers || 0).length, info.otherKeeps)
    }, function(html) {
      var $html = $("html").addClass("kifi-pane-parent");
      $pane = $(html).appendTo($html).layout();
      $html.addClass("kifi-with-pane");
    });
  }

  function hidePane() {
    api.log("[hidePane]");
    $pane.on("transitionend webkitTransitionEnd", function(e) {
      if (e.target.classList.contains("kifi-pane")) {
        $(e.target).remove();
        $html.removeClass("kifi-pane-parent");
      }
    });
    $pane = null;
    var $html = $("html").removeClass("kifi-with-pane");
  }

  function formatCountHtml(kept, isPrivate, numFriends, numOthers) {
    // Awful decision tree. Got a better way?
    if (kept) {
      var priv = ""; // isPrivate ? " <span class=kifi-slider2-private>Private</span>" : "";
      if (numFriends) {
        if (numOthers) {
          return "You" + priv + " + " + plural(numFriends, "friend") + " + " + plural(numOthers, "other") + " kept this";
        }
        return "You" + priv + " + " + plural(numFriends, "friend") + " kept this";
      }
      if (numOthers) {
        return "You" + priv + " + " + plural(numOthers, "other") + " kept this";
      }
      return "You kept this" + priv;
    }
    if (numFriends) {
      if (numOthers) {
        return plural(numFriends, "friend") + " + " + plural(numOthers, "other") + " kept this";
      }
      return plural(numFriends, "friend") + " kept this";
    }
    if (numOthers) {
      return plural(numOthers, "other") + " kept this";
    }
    return "No one kept this";
  }

  function plural(n, term) {
    return n + " " + term + (n == 1 ? "" : "s");
  }

  function shuffle(arr) {
    var i = arr.length, j, v;
    while (i > 1) {
      j = Math.random() * i-- | 0;
      v = arr[i], arr[i] = arr[j], arr[j] = v;
    }
    return arr;
  }

  // the slider API
  return {
    show: function(info, trigger, locator) {  // trigger is for the event log (e.g. "auto", "key", "icon")
      showSlider(info, trigger, locator);
    },
    shown: function() {
      return !!lastShownAt;
    },
    toggle: function(info, trigger) {  // trigger is for the event log (e.g. "auto", "key", "icon")
      if (document.querySelector(".kifi-slider2")) {
        hideSlider(trigger);
      } else {
        showSlider(info, trigger);
      }
    }};
}();
