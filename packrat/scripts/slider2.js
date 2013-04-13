// @require styles/metro/slider2.css
// @require styles/friend_card.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/jquery-showhover.js
// @require scripts/lib/keymaster.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/render.js

jQuery.fn.layout = function() {
  return this.each(function() {this.clientHeight});  // forces layout
};

var commentsPane = 0, threadsPane = 0, threadPane = 0;  // set when api.require'd
slider2 = function() {
  var $tile = $("#kifi-tile"), $slider, $pane, lastShownAt, info;

  key("esc", function() {
    if ($pane) {
      hidePane();
    } else if ($slider) {
      hideSlider("esc");
    }
  });

  function showSlider(o, trigger, locator) {
    info = o = info || o;  // ignore o after first call (may be out of date) TODO: trust cached state from main.js
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
        "numComments": o.counts.numComments,
        "numMessages": o.counts.numMessages,
        "newComments": o.counts.unreadComments,
        "newMessages": o.counts.unreadMessages,
        "newNotices": o.counts.unreadNotices,
        // "connected_networks": api.url("images/networks.png")
      }, function(html) {
        if ($slider) {
          api.log("[showSlider] already there");
        } else {
          $(".kifi-slider2").remove();  // e.g. from earlier version
          $tile.addClass("kifi-behind-slider");
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
            if ((e.target === this || e.target.parentNode === this) && (o.keepers.length || o.keeps) && !$pane) {
              $(this).showHover({
                reuse: false,
                showDelay: 250,
                hideDelay: 800,
                fadesOut: true,
                recovery: Infinity,
                create: function(callback) {
                  var keepers = pick(o.keepers, 8);
                  render("html/metro/keepers.html", {
                    link: true,
                    keepers: keepers,
                    anyKeepers: keepers.length,
                    captionHtml: formatCountHtml(o.kept, o.keepers.length, o.otherKeeps)
                  }, function(html) {
                    callback($("<div class=kifi-slider2-tip>").html(html).data("keepers", keepers));
                  });
                }});
            }
          }).on("mouseenter", ".kifi-slider2-keeper", function() {
            var $a = $(this).showHover({
              hideDelay: 600,
              fadesOut: true,
              create: function(callback) {
                var i = $a.prevAll(".kifi-slider2-keeper").length;
                var friend = $a.closest(".kifi-slider2-tip").data("keepers")[i];
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
              keepPage(el, true);
            } else {
              togglePrivate(el);
            }
          }).on("click", ".kifi-slider2-x", function() {
            if ($pane) {
              hidePane();
            }
          }).on("click", ".kifi-slider2-dock-btn", function() {
            var pane = $(this).data("pane");
            if ($pane) {
              if (pane == $pane.data("pane")) {
                hidePane();
              } else {
                showPane(pane);
              }
            } else if (!document.documentElement.classList.contains("kifi-pane-parent")) { // ensure it's finished hiding
              idleTimer.kill();
              showPane(pane);
            }
          });

          logEvent("slider", "sliderShown", {trigger: trigger, onPageMs: String(lastShownAt - t0), url: location.href});

          if (locator) {
            openDeepLink(locator);
          } else if (trigger != "tile") {
            idleTimer.start(5000);
          }
        }
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

  function keepPage(btn, privately) {
    api.log("[keepPage]", document.URL);

    api.port.emit("set_page_icon", true);
    info.kept = true;
    info.private = privately;
    btn.classList.remove("kifi-unkept");
    btn.classList.add(privately ? "kifi-private" : "kifi-public");
    $(".kifi-pane-kept").addClass("kifi-kept");
    $tile.addClass("kifi-kept");

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
    $tile.removeClass("kifi-kept");

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

  function openDeepLink(locator) {
    var loc = locator.split("/");
    switch (loc[1]) {
      case "messages":
        if (loc[2]) {
          requireData("thread/" + loc[2], function(th) {
            showPane("thread", false, th.messages[0].recipients, loc[2]);
          });
        } else {
          showPane("threads");
        }
        break;
      case "comments":
        showPane("comments");
        break;
    }
  }

  const createPaneTemplateParams = {
    general: function() {
      return {
        title: document.title,
        url: location.href,
        kept: info.kept,
        keepers: pick(info.keepers, 7),
        keepersCaptionHtml: formatCountHtml(0, info.keepers.length, info.otherKeeps)};
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
          $old.trigger("kifi:remove").remove();
          $new.detach().css({left: "", width: ""}).appendTo($cubby);
          $boxes.remove();
          $cubby.css("overflow", "");
        });
        $pane.data("pane", pane);
        populatePane[pane]($new, populateArg);
      });
    } else {
      api.require("styles/metro/pane.css", function() {
        render("html/metro/pane.html", $.extend(params, {
          kifiLogoUrl: api.url("images/kifi_logo.png"),
          gearUrl: api.url("images/metro/gear.png")
        }), {
          pane: "pane_" + pane + ".html"
        },
        function(html) {
          var $html = $("html").addClass("kifi-pane-parent");
          $pane = $(html).data("pane", pane).appendTo($html).layout()
          .on("keydown", ".kifi-pane-search", function(e) {
            var q;
            if (e.which == 13 && (q = this.value.trim())) {
              window.open("https://www.google.com/search?q=" + encodeURIComponent(q).replace(/%20/g, "+"));
            }
          })
          .on("click", ".kifi-pane-back", function() {
            showPane($(this).data("pane") || "general", true);
          })
          .on("kifi:show-pane", function(e, pane, paramsArg, populateArg) {
            showPane(pane, false, paramsArg, populateArg);
          })
          .on("mousedown click keydown keypress keyup", function(e) {
            e.stopPropagation();
          });
          $html.addClass("kifi-with-pane");
          populatePane[pane]($pane.find(".kifi-pane-box"), populateArg);
        });
      });
    }
  }

  function hidePane() {
    api.log("[hidePane]");
    $pane.on("transitionend webkitTransitionEnd", function(e) {
      if (e.target.classList.contains("kifi-pane")) {
        $(e.target).find(".kifi-pane-box").trigger("kifi:remove").end().remove();
        $html.removeClass("kifi-pane-parent");
      }
    });
    $pane = null;
    var $html = $("html").removeClass("kifi-with-pane");
  }

  const populatePane = {
    notices: function($box) {
      api.port.emit("session", function (session) {
        api.require("scripts/notices.js", function() {
          renderNotices($box.find(".kifi-pane-tall"));
          api.port.emit("set_last_notify_read_time");
        });
      });
    },
    comments: function($box) {
      requireData("comments", function(comments) {
        api.port.emit("session", function(session) {
          api.require("scripts/comments.js", function() {
            commentsPane.render($box.find(".kifi-pane-tall"), comments, session.userId, ~session.experiments.indexOf("admin"));
            var lastCom = comments[comments.length - 1];
            api.port.emit("set_comment_read", {id: lastCom.id, time: lastCom.createdAt});
          });
        });
      });
    },
    threads: function($box) {
      requireData("threads", function(threads) {
        api.require("scripts/threads.js", function() {
          threadsPane.render($box.find(".kifi-pane-tall"), threads);
          threads.forEach(function(th) {
            requireData("thread/" + th.id, api.noop);  // preloading
          });
        });
      });
    },
    thread: function($box, threadId) {
      var $tall = $box.find(".kifi-pane-tall").css("margin-top", $box.find(".kifi-thread-who").outerHeight());
      requireData("thread/" + threadId, function(th) {
        api.port.emit("session", function(session) {
          api.require("scripts/thread.js", function() {
            threadPane.render($tall, th.id, th.messages, session.userId);
            var lastMsg = th.messages[th.messages.length - 1];
            api.port.emit("set_message_read", {threadId: th.id, messageId: lastMsg.id, time: lastMsg.createdAt});
          });
        });
      });
    },
    general: $.noop
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

  const dataCallbacks = {};
  function requireData(key, callback) {
    var kArr = key.split("/");
    var arr = dataCallbacks[key] = dataCallbacks[key] || [];
    arr.push([kArr[1], callback]);

    api.port.emit.apply(api.port, kArr);
  }

  function receiveData(type, prop, data, respond) {  // prop might be omitted
    api.log("[receiveData]", arguments);
    if (arguments.length < 4) {
      data = prop, prop = null;
    }
    var arg = data[prop], key = prop ? type + "/" + arg : type;
    for (var i = 0, callbacks = dataCallbacks[key] || 0; i < callbacks.length; i++) {
      var cb = callbacks[i];
      if (cb[0] == arg) {
        cb[1](data);
        callbacks.splice(i--, 1);
      }
    }
    if (!callbacks.length) {
      delete dataCallbacks[key];
    }
  }

  api.port.on({
    comments: receiveData.bind(null, "comments"),
    threads: receiveData.bind(null, "threads"),
    thread: receiveData.bind(null, "thread", "id"),
    comment: function(comment) {
      api.port.emit("session", function(session) {
        (commentsPane.update || api.noop)(comment, session.userId);
      });
    },
    message: function(o) {
      api.port.emit("session", function(session) {
        (threadsPane.update || api.noop)(o.thread);
        (threadPane.update || api.noop)(o.thread, o.message, session.userId);
      });
    },
    counts: function(o) {
      var $btns = $slider.find(".kifi-slider2-dock-btn");
      [[".kifi-slider2-notices", o.unreadNotices],
       [".kifi-slider2-comments", o.unreadComments, o.numComments],
       [".kifi-slider2-threads", o.unreadMessages, o.numMessages]].forEach(function(a) {
        $btns.filter(a[0]).find(".kifi-count")
          .toggleClass("kifi-unread", !!a[1])
          .text(a[1] || a[2] || "")
          .css("display", a[1] || a[2] ? "" : "none");
      });
    }});

  // the slider API
  return {
    show: function(info, trigger, locator) {  // trigger is for the event log (e.g. "auto", "key", "icon")
      showSlider(info, trigger, locator);
    },
    shown: function() {
      return !!lastShownAt;
    },
    toggle: function(info, trigger) {  // trigger is for the event log (e.g. "auto", "key", "icon")
      if ($pane) {
        hidePane();
      } else if ($slider) {
        hideSlider(trigger);
      } else {
        showSlider(info, trigger);
      }
    },
    showKeepersFor: function(info, el, ms) {
      if (lastShownAt) return;
      var $el = $(el).showHover({
        reuse: false,
        showDelay: 0,
        hideDelay: 1e9,
        fadesOut: true,
        recovery: Infinity,
        create: function(callback) {
          var keepers = pick(info.keepers, 8);
          // TODO: preload friend pictures
          render("html/metro/keepers.html", {
            keepers: keepers,
            anyKeepers: keepers.length,
            captionHtml: formatCountHtml(info.kept, info.keepers.length, info.otherKeeps)
          }, function(html) {
            callback($("<div class=kifi-slider2-tip>").html(html));
          });
        }});
      setTimeout(function() {
        $el.triggerHandler("click.showHover");
      }, ms);
    }};
}();
