// @require styles/metro.css
// #require styles/friend_card.css
// #require styles/comments.css
// @require scripts/lib/jquery-1.8.2.min.js
// #require scripts/lib/jquery-ui-1.9.1.custom.min.js
// #require scripts/lib/jquery-showhover.js
// #require scripts/lib/jquery-tokeninput-1.6.1.min.js
// #require scripts/lib/jquery.timeago.js
// @require scripts/lib/keymaster.min.js
// #require scripts/lib/lodash.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/render.js
// #require scripts/snapshot.js

// http://stackoverflow.com/questions/12088819/css-transitions-on-new-elements
jQuery.fn.layout = function() {
  return this.each(function() {this.clientHeight});  // forces layout
};

slider2 = function() {
  var $slider, $pane, info, lastShownAt;

  key("esc", function() {
    if (document.querySelector(".kifi-slider2")) {
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
        "isPrivate": o.private//,
        // "sensitive": o.sensitive,
        // "site": location.hostname,
        // "neverOnSite": o.neverOnSite,
        // "numComments": o.numComments,
        // "numMessages": o.numMessages,
        // "connected_networks": api.url("images/networks.png"),
        // "socialConnections": o.friends.length == 0 ? null : {
        //   countText: summaryText(o.friends.length, o.kept),
        //   friends: o.friends}
      }, function(html) {
        if (document.querySelector(".kifi-slider2")) {
          api.log("[showSlider] already there");  // TODO: remove old one? perhaps from previous installation
        } else {
          $slider = $(html).appendTo("html").layout().addClass("kifi-visible");

          // attach event bindings
          $slider.on("mouseleave", function() {
            api.log("[mouseleave]");
            if (!$pane) {
              hideSlider("mouseleave");
            }
          }).on("click", ".kifi-slider2-keep", function() {
            if (this.classList.contains("kifi-unkept")) {
              keepPage(this, false);
            } else {
              unkeepPage(this);
            }
          }).on("click", ".kifi-slider2-lock", function(e) {
            e.stopPropagation();
            var btn = this.parentNode;
            if (btn.classList.contains("kifi-unkept")) {
              keepPage(btn, true);
            } else {
              togglePrivate(btn);
            }
          }).on("mouseover", ".kifi-slider2-lock", function() {
            this.parentNode.classList.add("kifi-lock-hover");
          }).on("mouseout", ".kifi-slider2-lock", function() {
            this.parentNode.classList.remove("kifi-lock-hover");
          }).on("click", ".kifi-slider2-pane", function() {
            if (this.classList.contains("kifi-slider2-pane-hide")) {
              this.classList.remove("kifi-slider2-pane-hide");
              hidePane();
            } else {
              this.classList.add("kifi-slider2-pane-hide");
              showPane();
            }
          });
          // $slider.on("click", ".kifi-slider-x", function() {
          //   hideSlider("x");
          // })

          logEvent("slider", "sliderShown", {trigger: trigger, onPageMs: String(lastShownAt - t0), url: location.href});

          if (locator) {
            openDeepLink(o.session, locator);
          } else if (trigger != "tile") {
            idleTimer.start();
          }
        }
      });
  }

  // trigger is for the event log (e.g. "key", "icon"). pass no trigger if just hiding slider temporarily.
  function hideSlider(trigger) {
    idleTimer.kill();
    $slider.addClass("kifi-hidden").on("transitionend webkitTransitionEnd", function(e) {
      if (trigger && e.originalEvent.propertyName == "opacity") {
        $slider.remove();
        $slider = null;
      }
    });
    if (trigger) {
      logEvent("slider", "sliderClosed", {trigger: trigger, shownForMs: String(new Date - lastShownAt)});
    }
  }

  var idleTimer = {
    start: function() {
      api.log("[idleTimer.start]");
      var t = idleTimer;
      clearTimeout(t.timeout);
      t.timeout = setTimeout(function hideSliderIdle() {
        api.log("[hideSliderIdle]");
        hideSlider("idle");
      }, 400);
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
    btn.classList.remove("kifi-private", "kifi-public");
    btn.classList.add("kifi-unkept");

    logEvent("slider", "unkeep");

    api.port.emit("unkeep", function(o) {
      api.log("[unkeepPage] response:", o);
    });
  }

  function togglePrivate(btn) {
    var priv = !btn.classList.contains("kifi-private");
    api.log("[setPrivate]", priv);

    btn.classList.remove(priv ? "kifi-public" : "kifi-private");
    btn.classList.add(priv ? "kifi-private" : "kifi-public");

    api.port.emit("set_private", priv, function(resp) {
      api.log("[setPrivate] response:", resp);
    });
  }

  function showPane() {
    api.log("[showPane]");
    var $html = $("html").addClass("kifi-pane-parent");
    $pane = $("<div class=kifi-pane>").appendTo($html).layout();
    $html.addClass("kifi-with-pane");
  }

  function hidePane() {
    api.log("[hidePane]");
    $pane.on("transitionend webkitTransitionEnd", function() {
      $pane.remove();
      $pane = null;
      $html.removeClass("kifi-pane-parent");
    });
    var $html = $("html").removeClass("kifi-with-pane");
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
