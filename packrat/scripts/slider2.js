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

slider2 = function() {
  var $slider, following, isKept, lastShownAt;

  key("esc", function() {
    if (document.querySelector(".kifi-slider2")) {
      slideOut("esc");
    }
  });

  function showSlider(trigger, locator) {
    /*api.port.emit("get_slider_info",*/!function(o) {
      api.log("slider info:", o);

      isKept = o.kept;
      following = o.following;
      lastShownAt = +new Date;

      render("html/metro/slider2.html", {
          // "logo": api.url('images/kifilogo.png'),
          // "arrow": api.url('images/triangle_down.31x16.png'),
          // "profilepic": o.session.avatarUrl,
          // "name": o.session.name,
          "bgUrl": api.url("images/metro/slider.png"),
          "isKept": o.kept,
          "private": o.private,
          "sensitive": o.sensitive,
          "site": location.hostname,
          "neverOnSite": o.neverOnSite,
          "numComments": o.numComments,
          "numMessages": o.numMessages,
          // "connected_networks": api.url("images/networks.png"),
          "socialConnections": o.friends.length == 0 ? null : {
            countText: summaryText(o.friends.length, o.kept),
            friends: o.friends}
        }, {
          // "main_hover": "main_hover.html",
          // "footer": "footer.html"
        }, function(html) {
          if (document.querySelector(".kifi-slider2")) {
            api.log("[showSlider] already there");  // TODO: remove old one? perhaps from previous installation
          } else {
            $slider = $(html).appendTo("html");
            setTimeout(function() { $slider.addClass("kifi-visible") });

            // attach event bindings
            $slider.on("mouseleave", function() {
              api.log("[mouseleave]");
              slideOut("mouseleave");
            });
            // $slider.on("click", ".kifi-slider-x", function() {
            //   slideOut("x");
            // })

            logEvent("slider", "sliderShown", {trigger: trigger, onPageMs: String(lastShownAt - t0), url: location.href});

            if (locator) {
              openDeepLink(o.session, locator);
            } else if (trigger != "tile") {
              idleTimer.start();
            }
          }
        });
    }({kept: false, friends: []});
  }

  // trigger is for the event log (e.g. "key", "icon"). pass no trigger if just hiding slider temporarily.
  function slideOut(trigger) {
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
      t.timeout = setTimeout(function slideOutIdle() {
        api.log("[slideOutIdle]");
        slideOut("idle");
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

  // the slider API
  return {
    show: function(trigger, locator) {  // trigger is for the event log (e.g. "auto", "key", "icon")
      showSlider(trigger, locator);
    },
    shown: function() {
      return !!lastShownAt;
    },
    toggle: function(trigger) {  // trigger is for the event log (e.g. "auto", "key", "icon")
      if (document.querySelector(".kifi-slider2")) {
        slideOut(trigger);
      } else {
        showSlider(trigger);
      }
    }};
}();
