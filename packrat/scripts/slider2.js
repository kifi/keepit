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
        "newComments": o.unreadComments,
        "newMessages": o.unreadMessages,
        // "connected_networks": api.url("images/networks.png")
      }, function(html) {
        if (document.querySelector(".kifi-slider2")) {
          api.log("[showSlider] already there");  // TODO: remove old one? perhaps from previous installation
        } else {
          $slider = $(html).appendTo("html").layout().addClass("kifi-visible kifi-growing")
          .on("transitionend webkitTransitionEnd", function f(e) {
            if (e.target === $slider[0]) {
              $slider.off("transitionend webkitTransitionEnd", f).removeClass("kifi-growing");
            }
          });

          // attach event bindings
          $slider.mouseout(function(e) {
            if (!$pane) {
              if (e.toElement) {
                if ($slider && !$slider[0].contains(e.toElement)) {
                  api.log("[slider.mouseout]");
                  hideSlider("mouseout");
                }
              } else {  // out of window
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
            var el = this.parentNode;
            if (el.classList.contains("kifi-unkept")) {
              keepPage(el, false);
            } else {
              unkeepPage(el);
            }
            this.classList.add("kifi-hoverless");
          }).on("mouseover", ".kifi-slider2-keep-btn", function(e) {
            if (e.target === this) {
              if (o.keepers || o.keeps) {
                var $btn = $(this).showHover({
                  reuse: false,
                  showDelay: 250,
                  hideDelay: 200,
                  recovery: Infinity,
                  create: function(callback) {
                    render("html/metro/keepers.html", {
                      keepers: o.keepers,
                      captionHtml: formatCountHtml(o.kept, o.private, (o.keepers || 0).length, o.keeps)
                    }, function(html) {
                      callback($("<div class=kifi-slider2-tip>").html(html));
                    });
                  }});
              }
            } else {
              this.classList.add("kifi-hoverless");
            }
          }).on("mouseout", ".kifi-slider2-keep-btn", function() {
            this.classList.remove("kifi-hoverless");
          }).on("mouseenter", ".kifi-slider2-lock", function() {
            $(this).showHover({
              reuse: false,
              showDelay: 250,
              hideDelay: 100,
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
          }).on("click", ".kifi-slider2-pane", function() {
            if (this.classList.contains("kifi-slider2-pane-hide")) {
              this.classList.remove("kifi-slider2-pane-hide");
              hidePane();
            } else {
              this.classList.add("kifi-slider2-pane-hide");
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
    btn.classList.remove("kifi-private", "kifi-public");
    btn.classList.add("kifi-unkept");

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
      url: location.href
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

  function formatCountHtml(kept, isPrivate, numFriends, numTotal) {
    var numOthers = (numTotal || 0) - (numFriends || 0) - (kept && !isPrivate ? 1 : 0);
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
