// @require styles/slider.css
// @require styles/friend_card.css
// @require styles/comments.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/jquery-ui-1.9.1.custom.min.js
// @require scripts/lib/jquery-showhover.js
// @require scripts/lib/jquery-tokeninput-1.6.1.min.js
// @require scripts/lib/jquery.timeago.js
// @require scripts/lib/keymaster.min.js
// @require scripts/lib/lodash.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/render.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/snapshot.js

slider = function() {
  var following, isKept, lastShownAt;

  key("esc", function() {
    if (document.querySelector(".kifi-slider")) {
      slideOut("esc");
    }
  });

  $.extend(jQuery.easing, {
    easeQuickSnapBounce: function(x,t,b,c,d,s) {
      if (s == null) s = 1.3;
      return c*((t=t/d-1)*t*((s+1)*t + s) + 1) + b;
    },
    easeCircle: function(x,t,b,c,d) {
      return (t/=d/2) < 1 ?
        -c/2 * (Math.sqrt(1 - t*t) - 1) + b :
        c/2 * (Math.sqrt(1 - (t-=2)*t) + 1) + b;
    },
    easeInOutBack: function(x,t,b,c,d,s) {
      if (s == null) s = 1.3;
      return (t/=d/2) < 1 ?
        c/2*(t*t*(((s*=(1.525))+1)*t - s)) + b :
        c/2*((t-=2)*t*(((s*=(1.525))+1)*t + s) + 2) + b;
    }
  });

  function summaryText(numFriends, isKept) {
    if (isKept) {
      if (numFriends > 0) {
        return "You and " + (numFriends == 1 ? "a friend" : (numFriends + " of your friends")) + " kept this.";
      }
      return "You kept this!";
    }
    if (numFriends > 0) {
      return ([,"One","Two","Three","Four"][numFriends] || numFriends) + " of your friends kept this.";
    }
    return "To quickly find this page later...";
  }

  function keepPage(shouldSlideOut) {
    api.log("[keepPage]", document.URL);

    api.port.emit("set_page_icon", true);
    isKept = true;
    if (shouldSlideOut) slideOutKept();

    var isPrivate = $(".kifi-keep-private").is(":checked");

    logEvent("slider", "keep", {"isPrivate": isPrivate});

    api.port.emit("add_bookmarks", {
      "url": document.URL,
      "title": document.title,
      "private": isPrivate
    }, function(response) {
      api.log("[keepPage] response:", response);
    });
  }

  function unkeepPage(shouldSlideOut) {
    api.log("[unkeepPage]", document.URL);

    api.port.emit("set_page_icon", false);
    isKept = false;
    if (shouldSlideOut) slideOut("unkeep");

    logEvent("slider", "unkeep");

    api.port.emit("unkeep", function(o) {
      api.log("[unkeepPage] response:", o);
    });
  }

  function showSlider(trigger, locator) {
    api.port.emit("get_slider_info", function(o) {
      api.log("slider info:", o);

      isKept = o.kept;
      following = o.following;
      lastShownAt = +new Date;

      render("html/kept_hover.html", {
          "logo": api.url('images/kifilogo.png'),
          "arrow": api.url('images/triangle_down.31x16.png'),
          "profilepic": o.session.avatarUrl,
          "name": o.session.name,
          "isKept": o.kept,
          "private": o.private,
          "sensitive": o.sensitive,
          "site": location.hostname,
          "neverOnSite": o.neverOnSite,
          "numComments": o.numComments,
          "numMessages": o.numMessages,
          "connected_networks": api.url("images/networks.png"),
          "socialConnections": o.friends.length == 0 ? null : {
            countText: summaryText(o.friends.length, o.kept),
            friends: o.friends}
        }, {
          "main_hover": "main_hover.html",
          "footer": "footer.html"
        }, function(html) {
          if (document.querySelector(".kifi-slider")) {
            api.log("No need to inject, it's already here!");
          } else {
            drawKeepItHover(o, html, !locator ? $.noop : function() {
              openDeepLink(o.session, locator);
            });
            logEvent("slider", "sliderShown", {trigger: trigger, onPageMs: String(lastShownAt - t0), url: location.href});
            if (!locator) {
              idleTimer.start();
            }
          }
        });

        badGlobalState["session"] = o.session;
        badGlobalState["updates"] = {
          publicCount: o.numComments,
          messageCount: o.numMessages,
          countSum: o.numComments + o.numMessages};
    });
  }

  function drawKeepItHover(o, renderedTemplate, callback) {  // o is the get_slider_info response
    var $slider = $(renderedTemplate).appendTo("body");

    // Event bindings
    $slider.draggable({cursor: "move", axis: "y", distance: 10, handle: ".kifi-slider-title-bar", containment: "body", scroll: false})
    .on("click", ".kifi-slider-x", function() {
      slideOut("x");
    })
    .on("mousedown", ".kifi-slider-▾", function(e) {
      e.preventDefault();
      var $arr = $(this);
      var $box = $arr.siblings(".kifi-slider-▾-box").fadeIn(50);
      var $nev = $box.find(".kifi-slider-never")
        .on("mouseenter", enterItem)
        .on("mouseleave", leaveItem);
      var $act = $box.closest(".kifi-slider-title-actions").addClass("kifi-active");
      document.addEventListener("mousedown", function onDown(e) {
        if (!$box[0].contains(e.target)) {
          document.removeEventListener("mousedown", onDown, true);
          $box.triggerHandler("kifi:hide");
          if ($arr[0] === e.target) {
            e.stopPropagation();
          }
        }
      }, true);
      $box.on("kifi:hide", function hide() {
        $act.removeClass("kifi-active");
        $nev.off("mouseenter", enterItem)
            .off("mouseleave", leaveItem);
        $box.off("kifi:hide", hide).fadeOut(50);
      });
      // .kifi-hover class needed because :hover does not work during drag
      function enterItem() { $(this).addClass("kifi-hover"); }
      function leaveItem() { $(this).removeClass("kifi-hover"); }
    })
    .on("mouseup", ".kifi-slider-never", function(e) {
      e.preventDefault();
      var $nev = $(this).toggleClass("kifi-checked");
      var never = $nev.hasClass("kifi-checked");
      api.port.emit("suppress_on_site", never);
      setTimeout(function() {
        if (never) {
          slideOut("never");
        } else {
          $nev.closest(".kifi-slider-▾-box").triggerHandler("kifi:hide");
        }
      }, 150);
    })
    .on("click", ".kifi-button-unkeep", function() {
      unkeepPage(true);
    })
    .on("click", ".kifi-button-keep", function() {
      keepPage(true);
    })
    .on("click", ".kifi-button-private", function() {
      var $btn = $(this), priv = /private/i.test($btn.text());
      api.log("[setPrivate]", priv);
      api.port.emit("set_private", priv, function(resp) {
        api.log("[setPrivate] response:", resp);
        $btn.text("Make it " + (priv ? "Public" : "Private"));
      });
    })
    .on("mouseenter", ".kifi-keeper", function() {
      var $a = $(this).showHover({
        hideDelay: 600,
        fadesOut: true,
        create: function(callback) {
          var friend = o.friends[$a.prevAll(".kifi-keeper").length];
          render("html/friend_card.html", {
            name: friend.firstName + " " + friend.lastName,
            facebookId: friend.facebookId,
            iconsUrl: api.url("images/social_icons.png")
          }, callback);
          api.port.emit("get_num_mutual_keeps", {id: friend.id}, function gotNumMutualKeeps(o) {
            $a.find(".kifi-kcard-mutual").text(o.n + " mutual keep" + (o.n == 1 ? "" : "s"));
          });
        }});
    })
    .on("click", ".kifi-button-dropdown", function() {
      $(".kifi-keep-options").slideToggle(150);
    })
    .on("click", ".kifi-tab-comments", function() {
      if ($slider.data("view") !== "public") {
        showComments(o.session, "public");
      } else {
        hideComments();
      }
    })
    .on("click", ".kifi-tab-messages", function() {
      if ($slider.data("view") !== "message" || document.querySelector(".kifi-thread-back")) {
        showComments(o.session, "message");
      } else {
        hideComments();
      }
    })
    .on("mousedown click keydown keypress keyup", function(e) {
      idleTimer.dead || idleTimer.kill();
      e.stopPropagation();
    })
    .on("mousewheel", ".kifi-comments-body,.kifi-comment-compose", function(e) {
      this.scrollTop += e.originalEvent.wheelDeltaY / 3;
    })
    .on("mousewheel", function(e) {
      e.preventDefault();
    });

    slideIn();

    callback && callback();
  }

  var idleTimer = {
    start: function() {
      api.log("[idleTimer.start]");
      var t = idleTimer;
      clearTimeout(t.timeout);
      t.timeout = setTimeout(function slideOutIdle() {
        api.log("[slideOutIdle]");
        slideOut("idle");
      }, 10000);
      $(".kifi-slider")
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
      $(".kifi-slider")
        .off("mouseenter", t.clear)
        .off("mouseleave", t.start);
      t.dead = true;
    }};

  function slideOutKept() {
    var $s = $(".kifi-slider");
    $s.animate({
        bottom: '+=' + $s.position().top,
        opacity: 0
      },
      900,
      'easeInOutBack',
      function() {
        $s.remove();
      });
    logEvent("slider", "sliderClosed", {trigger: "keep", shownForMs: String(new Date - lastShownAt)});
  }

  // trigger is for the event log (e.g. "key", "icon"). pass no trigger if just hiding slider temporarily.
  function slideOut(trigger) {
    idleTimer.kill();
    var $s = $(".kifi-slider").animate({
        opacity: 0,
        right: '-=340'
      },
      300,
      'easeQuickSnapBounce',
      !trigger ? $.noop : function() {
        $s.remove();
      });
    if (trigger) {
      logEvent("slider", "sliderClosed", {trigger: trigger, shownForMs: String(new Date - lastShownAt)});
    }
  }

  function slideIn() {
    $(".kifi-slider").animate({
        right: '+=340',
        opacity: 1
      },
      400,
      "easeQuickSnapBounce",
      function() {
        $(this).css({right: "-10px", opacity: 1});
      });
  }

  function redrawFooter(showFooterNav, type) {
    var footerParams = {
      showFooterNav: showFooterNav,
      isMessages: type == "message",
      isKept: isKept,
      logo: api.url('images/kifilogo.png')
    }

    render("html/footer.html", footerParams, function(html) {
      $(".kifi-slider-footer").html(html)
      .on("mousedown", ".kifi-footer-close", hideComments)
      .on("mousedown", ".kifi-footer-keep", function(e) {
        e.preventDefault();
        keepPage(false);
        redrawFooter(showFooterNav, type);
        // TODO: update message/buttons on main panel
      })
      .on("mousedown", ".kifi-footer-unkeep", function(e) {
        e.preventDefault();
        unkeepPage(false);
        redrawFooter(showFooterNav, type);
        // TODO: update message/buttons on main panel
      });
    });
  }

  var badGlobalState = {}, viewTransitionInProgress;

  function isCommentPanelVisible() {
    return $(".kifi-comment-wrapper").is(":visible");
  }

  setInterval(function refreshCommentsHack() {
    if (isCommentPanelVisible() !== true) return;
    hasNewComments(function() {
      updateCommentCount("public", badGlobalState["updates"]["publicCount"]);
      //updateCommentCount("message", badGlobalState["updates"]["messageCount"]);message count includes children, need to fix...
      if (isCommentPanelVisible() !== true) return;
      showComments(badGlobalState.session, badGlobalState.type, badGlobalState.id, true);
    });
  }, 5000);

  function hasNewComments(callback) {
    api.port.emit("get_slider_updates", function(updates) {
      if (badGlobalState["updates"]) {
        var hasUpdates = badGlobalState["updates"]["countSum"] !== updates["countSum"];
        if (hasUpdates && callback) {
          callback();
        }
      }
      badGlobalState["updates"] = updates;
    });
  }

  function hideComments() {
    if (viewTransitionInProgress) {
      api.log("[hideComments]", "ignoring (transition already in progress)");
      return;
    }
    viewTransitionInProgress = true;

    $('.kifi-content').slideDown();
    $('.kifi-comment-wrapper').slideUp(600, 'easeInOutBack', function() {
      viewTransitionInProgress = false;
    });
    $(".kifi-slider").removeClass("kifi-public kifi-message").removeData("view");
    redrawFooter(false);
  }

  function showComments(session, type, id, partialRender) {
    if (viewTransitionInProgress) {
      api.log("[showComments]", "ignoring (transition already in progress)");
      return;
    }
    viewTransitionInProgress = true;

    badGlobalState["session"] = session;
    badGlobalState["type"] = type;
    badGlobalState["id"] = id;

    var $slider = $(".kifi-slider"), typeBefore = $slider.data("view");
    $slider.data("view", type).removeClass("kifi-public kifi-message").addClass("kifi-" + type);

    api.port.emit("get_comments", {kind: type, commentId: id}, function(comments) {
      api.log("[showComments] comments:", comments);
      renderComments(session, comments, type, id, function() {
        if (typeBefore) {  // .kifi-comment-wrapper already visible
          viewTransitionInProgress = false;
        } else {
          repositionScroll(false);

          $('.kifi-content').slideUp(); // hide main hover content
          $('.kifi-comment-wrapper').slideDown(600, function() {
            repositionScroll(false);
            viewTransitionInProgress = false;
          });
        }
        if (type !== typeBefore) {
          redrawFooter(true, type);
        }
      }, partialRender);
    });
  }

  function updateCommentCount(type, count) {
    count = count != null ? count : $(".kifi-comment-real").length; // if no count passed in, count DOM nodes

    $({"public": ".kifi-tab-count-comments", "message": ".kifi-tab-count-messages"}[type]).text(count);
  }

  function renderComments(session, comments, type, id, onComplete, partialRender) {
    api.log("Drawing comments!");
    comments = comments || {};
    comments["public"] = comments["public"] || [];
    comments["message"] = comments["message"] || [];

    var visibleComments = comments[type] || [];

    if (!id) {
      updateCommentCount(type, visibleComments.length);
    }

    var params = {
      kifiuser: {
        "firstName": session.name,
        "lastName": "",
        "avatar": session.avatarUrl
      },
      formatComments: getTextFormatter,
      formatLocalDate: getLocalDateFormatter,
      comments: visibleComments,
      showControlBar: type == "public",
      following: following,
      snapshotUri: api.url("images/snapshot.png"),
      connected_networks: api.url("images/social_icons.png")
    };

    // TODO: fix indentation below

      if (visibleComments.length && visibleComments[0].user && visibleComments[0].user.id) {
        for (msg in visibleComments) {
          visibleComments[msg]["isLoggedInUser"] = visibleComments[msg].user.id == session.userId
        }
      }

      if (type == "message") {
        // For thread lists, we need to do this for each one. For threads, only the first one
        var iterMessages = (id ? [visibleComments[0]] : visibleComments)
        var threadAvatar = "";
        for (msg in iterMessages) {
          var recipients = iterMessages[msg]["recipients"];
          var l = recipients.length;
          if (l == 0) { // No recipients!
            threadAvatar = params.kifiuser.avatar;
          } else if (l == 1) {
            threadAvatar = iterMessages[msg]["recipients"][0]["avatar"];
          } else {
            threadAvatar = api.url("images/convo.png");
          }
          iterMessages[msg]["threadAvatar"] = threadAvatar;

          var recipientNames = [];
          for (r in recipients) {
            var name = recipients[r].firstName + " " + recipients[r].lastName;
            recipientNames.push(name);
          }

          // handled separately because this will need to be refactored to be cleaner
          function formatRecipient(name) {
            return "<strong class=kifi-recipient>" + name + "</strong>";
          }

          var displayedRecipients = [];
          var storedRecipients = [];
          if (l == 0) {
            displayedRecipients.push(session.name);
          } else if (l <= 4) {
            displayedRecipients = recipientNames.slice(0, l);
          } else {
            displayedRecipients = recipientNames.slice(0, 3);
            storedRecipients = recipientNames.slice(3);
          }

          for (d in displayedRecipients) {
            displayedRecipients[d] = formatRecipient(displayedRecipients[d]);
          }

          var recipientText;
          if (l == 0) {
            recipientText = displayedRecipients[0];
          } else if (l == 1) {
            recipientText = displayedRecipients[0];
          } else if (l == 2) {
            recipientText = displayedRecipients[0] + " and " + displayedRecipients[1];
          } else if (l == 3 || l == 4) {
            recipientText = displayedRecipients.slice(0, l - 1).join(", ") + " and " + displayedRecipients[l - 1];
          } else {
            recipientText = displayedRecipients.slice(0, 3).join(", ");
            storedRecipients = recipientNames.slice(3);
          }
          // todo "You wrote to "

          iterMessages[msg]["recipientText"] = recipientText;
          iterMessages[msg]["storedRecipients"] = storedRecipients;
          iterMessages[msg]["showMessageCount"] = iterMessages[msg]["messageCount"] > 1;
        }

        if (id) {
          var othersInConversation = {};
          visibleComments.forEach(function(c) {
            c.recipients.concat([c.user]).forEach(function(u) {
              if (u.id != session.userId) {
                othersInConversation[u.id] = u.firstName + " " + u.lastName;
              }
            });
          });
          var othersIds = Object.keys(othersInConversation);
          params.othersInConversationText = othersIds.map(function(id) {
            return "<strong>" + othersInConversation[id] + "</strong>";
          }).join(", ");
          params.recipientText = visibleComments[0].recipientText;
          params.storedRecipients = visibleComments[0].storedRecipients;
          params.id = visibleComments[0].id;
          params.recipientCount = othersIds.length;
          params.recipientCountText = othersIds.length == 1 ? "person" : "people";
          params.hideComposeTo = true;
        }
      }

      var partials = {
        "comment_body_view": type != "message" ? "comments_list.html" : id ? "thread.html" : "message_list.html",
        "hearts": "hearts.html",
        "comment": type != "message" || id ? "comment.html" : "thread_info.html",
        "comment_post_view": type != "message" ? "comment_post.html" : "message_post.html"};
      if (partialRender) {
        // Hacky solution for a partial refresh. Needs to be refactored.
        render("html/comments/" + partials.comment_body_view, params, partials, function(html) {
          var $b = $(".kifi-comments-body").html(html);
          $b.animate({scrollTop: $b[0].scrollHeight - $b[0].clientHeight})
          $b.find("time").timeago();
          viewTransitionInProgress = false;
        });
      } else {
        render("html/comments/comments_view.html", params, partials, function(html) {
          drawCommentView(html, session, type, id, onComplete);
        });
      }
  }

  function drawCommentView(renderedTemplate, session, type, id, onComplete) {
    onComplete = onComplete || $.noop;
    //api.log(renderedTemplate);
    repositionScroll(false);

    var $w1 = $(".kifi-comment-wrapper");
    if ($w1[0].firstChild && (id || type == "message" && $w1.find(".kifi-thread-back").length)) {
      var $p = $w1.wrap("<div>").parent();
      var $w2 = $($w1[0].cloneNode()).html(renderedTemplate).appendTo($p);
      var r1 = $w1[0].getBoundingClientRect();
      var r2 = $w2[0].getBoundingClientRect();
      $p.css({position: "relative", height: r1.height, overflow: "hidden"}).animate({height: r2.height});
      $w1.css({position: "absolute", width: r1.width, left: "0%", bottom: 0})
      .animate({left: (id ? "-" : "") + "100%"})
      $w2.css({position: "absolute", width: r2.width, left: (id ? "" : "-") + "100%", bottom: 0})
      .animate({left: "0%"}, function() {
        $w1.remove();
        $w2.css({position: "", width: "", left: "", bottom: ""}).unwrap().find("time").timeago();
        onComplete();
      });
    } else {
      $w1.html(renderedTemplate).find("time").timeago();
      onComplete();
    }

    repositionScroll(false);

    createCommentBindings($w2 || $w1, type, session);
  }

  function createCommentBindings($container, type, session) {
    // Note: The same $container may be passed to this function multiple times. To avoid duplicated
    // bindings (repeated execution of same handlers), attach all bindings to descendants of $container,
    // which are guaranteed to be fresh. Also be sure to use $container to scope all element searches.
    $container.find(".kifi-control-bar").on("click", ".kifi-follow", function() {
      following = !following;  // TODO: server should return whether following along with the comments
      api.port.emit("follow", following);
      $(this).toggleClass("kifi-following", following);
    });

    $container.children(".kifi-comments-body,.kifi-comments-post")
    .on("mousedown", "a[href^='x-kifi-sel:']", lookMouseDown)
    .on("click", "a[href^='x-kifi-sel:']", function(e) {
      e.preventDefault();
    });

    $container.find(".kifi-comments-body").on("hover", ".kifi-more-recipients", function(event) {
      //$(this).parent().find('.kifi-more-recipient-list')[event.type == 'mouseenter' ? "show" : "hide"]();
    }).on("click", ".kifi-thread-info", function() {
      showComments(session, type, $(this).parent().data("id"));
    }).on("click", ".kifi-thread-back", function() {
      showComments(session, type);
    })

    var $cpv = $container.find(".kifi-comments-post").on("mousedown", ".kifi-post-to-network .kifi-network", function() {
      alert("Not yet implemented. Coming soon!");
      return;
    });

    if (type == "message") {
      api.port.emit("get_friends", function(friends) {
        api.log("friends:", friends);
        for (var i in friends) {
          var f = friends[i];
          f.name = f.firstName + " " + f.lastName;
        }
        $container.find(".kifi-to-list").tokenInput(friends, {
          searchDelay: 0,
          minChars: 2,
          placeholder: "To",
          hintText: "",
          noResultsText: "",
          searchingText: "",
          animateDropdown: false,
          preventDuplicates: true,
          allowTabOut: true,
          tokenValue: "id",
          theme: "kifi",
          zindex: 2147483641});
        $container.find("#token-input-to-list").keypress(function(e) {
          return e.which !== 13;
        });
      });
    }

    // Main comment textarea

    var typeName = type == "public" ? "comment" : "message";
    var placeholder = "<span class=kifi-placeholder>Add a " + typeName + "…</span>";
    var $kcc = $cpv.find(".kifi-comment-compose").html(placeholder)
    .focus(function() {
      $kcc.find(".kifi-placeholder").remove();
    })
    .blur(function() {
      // wkb.ug/112854 crbug.com/222546
      $("<input style=position:fixed;top:999%>").appendTo("html").each(function() {this.setSelectionRange(0,0)}).remove();

      if (!convertDraftToText($kcc.html())) { // unchanged text!
        $kcc.html(placeholder);
      }
    });
    $cpv.on("click", ".kifi-take-snapshot", function() {
      $cpv.blur();
      slideOut();
      snapshot.take(typeName, function(selector) {
        slideIn();
        if (selector) {
          $kcc.append(" <a href='x-kifi-sel:" + selector.replace("'", "&#39;") + "'>look here</a>");
        }
        $kcc.focus();
        var r = document.createRange();
        r.selectNodeContents($kcc[0]);
        r.collapse(false);
        var s = window.getSelection();
        s.removeAllRanges();
        s.addRange(r);
      });
    }).on("click", ".kifi-submit-comment", function() {
      $(this).closest("form").submit();
    }).on("submit", ".kifi-comment-form", function(e) {
      e.preventDefault();
      var text = convertDraftToText($(".kifi-comment-compose").find(".kifi-placeholder").remove().end().html());
      if (!text) {
        $(".kifi-comment-compose").html(placeholder);
        return false;
      }

      logEvent("slider", "comment");

      submitComment(text, type, session, null, null, function(newComment) {
        $('.kifi-comment-compose').html(placeholder).blur();

        api.log("new comment", newComment);
        // Clean up CSS

        var params = newComment;

        newComment.isLoggedInUser = true;
        params["formatComments"] = getTextFormatter;
        params["formatLocalDate"] = getLocalDateFormatter;

        badGlobalState["updates"].publicCount++;
        badGlobalState["updates"].countSum++;

        render("html/comments/comment.html", params, function(html) {
          //drawCommentView(html, session, type);
          $(".kifi-comments-body").find(".kifi-comment-fake").parent().remove();
          $(".kifi-comments-body").append(html).find("time").timeago();
          updateCommentCount(type);
          repositionScroll(false);
        });
      });
      return false;
    }).on("submit", ".kifi-message-form", function(e) {
      e.preventDefault();
      var text = convertDraftToText($(".kifi-comment-compose").find(".kifi-placeholder").remove().end().html());
      if (!text) {
        $(".kifi-comment-compose").html(placeholder);
        return false;
      }

      var isReply = $(this).hasClass("kifi-message-reply");
      var recipients;
      var parent;

      logEvent("slider", "message", {"newThread": !isReply});

      badGlobalState["updates"].messageCount++;
      badGlobalState["updates"].countSum++;

      if (!isReply) {
        var recipientJson = $("#to-list").tokenInput("get");
        $("#to-list").tokenInput("clear");

        var recipientArr = [];
        for (r in recipientJson) {
          recipientArr.push(recipientJson[r].id);
        }
        if (recipientArr.length == 0) {
          alert("Silly you. You need to add some friends!");
          return false;
        }
        recipients = recipientArr.join(",");
        api.log("[submit] to:", recipients);
      } else {
        parent = $(this).parents(".kifi-comment-wrapper").find(".kifi-thread-wrapper").data("id");
        api.log("[submit] thread id:", parent);
      }

      submitComment(text, type, session, parent, recipients, function submitted(newComment) {
        $(".kifi-comment-compose").html(placeholder).blur();

        api.log("[submitted] new message", newComment);
        // Clean up CSS

        if (!isReply) {
          updateCommentCount(type, +$(".kifi-tab-count-messages").text() + 1);
          api.log("[submitted] not a reply. redirecting to new message");
          showComments(session, type, newComment.message.id);
          return;
        }

        var params = newComment.message;
        newComment.message.isLoggedInUser = true;
        params["formatComments"] = getTextFormatter;
        params["formatLocalDate"] = getLocalDateFormatter;

        render("html/comments/comment.html", params, function(html) {
          //drawCommentView(html, session, type);
          $(".kifi-comments-body").find('.kifi-comment-fake').parent().remove();
          $('.kifi-thread-wrapper').append(html).find("time").timeago();
          repositionScroll(false);
        });

      });
      return false;
    });
  }

  function submitComment(text, type, session, parent, recipients, callback) {
    api.log("[submitComment] parent:", parent);
    api.port.emit("post_comment", {
      "url": document.URL,
      "title": document.title,
      "text": text,
      "permissions": type,
      "parent": parent,
      "recipients": recipients
    }, function(response) {
      // Because we're using very simple templating now, re-rendering has to be done carefully.
      callback(type == "message" ? response : {
          "createdAt": new Date,
          "text": text,
          "user": {
            "id": session.userId,
            "firstName": session.name,
            "lastName": "",
            "facebookId": session.facebookId
          },
          "permissions": type,
          "id": response.commentId});
    });
  }

  function repositionScroll(resizeQuickly) {
    resizeCommentBodyView(resizeQuickly);
    $(".kifi-comments-body").prop("scrollTop", 99999);
  }

  function resizeCommentBodyView(resizeQuickly) {
    api.log("[resizeCommentBodyView]");
    $('.kifi-slider').css("top", "");
    $(".kifi-comments-body").stop().css({'max-height':$(window).height()-320});
    return; // for now, we'll do a rough fix
    var $bar = $(".kifi-slider-title-bar");
    if (resizeQuickly === true) {
      $(".kifi-comments-body").stop().css({'max-height':$(window).height()-320});
    } else {
      if ($bar.length) {
        var offset = Math.round($bar.offset().top - 30);
        if(Math.abs(offset) > 20) {
          $(".kifi-comments-body").stop().animate({'max-height':'+='+offset},20, function() {
            if(Math.abs($(".kifi-comments-body").height() - offset) > 2) {
              return;
            }
            var newOffset = Math.abs($bar.offset().top - 30);
            if (newOffset > 20) {
              resizeCommentBodyView(false);
            }
          });
        }
      }
    }
  }

  function openDeepLink(session, locator) {
    var loc = locator.split("/");
    switch (loc[1]) {
      case "messages":
        showComments(session, "message", loc[2] || null);
        break;
      case "comments":
        showComments(session, "public");
        break;
    }
  }

  $(window).resize(function() {
    resizeCommentBodyView();
  });

  // defining the slider API
  return {
  show: function(trigger, locator) {  // trigger is for the event log (e.g. "auto", "key", "icon")
    showSlider(trigger, locator);
  },
  shown: function() {
    return !!lastShownAt;
  },
  toggle: function(trigger) {  // trigger is for the event log (e.g. "auto", "key", "icon")
    if (document.querySelector(".kifi-slider")) {
      slideOut(trigger);
    } else {
      showSlider(trigger);
    }
  }};
}();
