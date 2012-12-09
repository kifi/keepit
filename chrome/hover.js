console.log("[" + new Date().getTime() + "] ", "injecting keep it hover div");

(function() {
  var config, following, isKept;

  function log(message) {
    console.log("[" + new Date().getTime() + "] ", message);
  }

  chrome.extension.onRequest.addListener(function(request, sender, sendResponse) {
    if (request.type === "show_hover") {
      var existingElements = $('.kifi_hover').length;
      if (existingElements > 0) {
        slideOut();
        return;
      }
      chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
        config = response;
        console.log("user config",response);
        getUserInfo(showHover);
      });
    }
  });

  $('<input id="editableFix" style="opacity:0;color:transparent;width:1px;height:1px;border:none;margin:0;padding:0;" tabIndex="-1">').appendTo('html')

  $.extend(jQuery.easing,{
    easeQuickSnapBounce:function(x,t,b,c,d) {
      if (typeof s === 'undefined') s = 1.3;
      return c*((t=t/d-1)*t*((s+1)*t + s) + 1) + b;
    },
    easeCircle: function (x, t, b, c, d) {
      if ((t/=d/2) < 1) return -c/2 * (Math.sqrt(1 - t*t) - 1) + b;
      return c/2 * (Math.sqrt(1 - (t-=2)*t) + 1) + b;
    },
    easeInOutBack: function (x, t, b, c, d, s) {
      if (s == undefined) s = 1.3;
      if ((t/=d/2) < 1) return c/2*(t*t*(((s*=(1.525))+1)*t - s)) + b;
      return c/2*((t-=2)*t*(((s*=(1.525))+1)*t + s) + 2) + b;
    }
  });

  function getUserInfo(callback) {
    chrome.extension.sendRequest({"type": "get_user_info"}, function(user) {
      callback(user);
    });
  }

  function showHover(user) {
    window.kifi_hover = true; // set global variable, so hover will not automatically slide out again.

    if (!user || !user.keepit_external_id) {
      log("No user info! Can't search.")
      return;
    }

    showKeepItHover(user);
  }

  var templateCache = {};

  function loadFile(name, callback) {
    var tmpl = templateCache[name];
    if (tmpl) {
      callback(tmpl);
    } else {
      var req = new XMLHttpRequest();
      req.open("GET", chrome.extension.getURL(name), true);
      req.onreadystatechange = function() {
        if (req.readyState == 4 && req.status == 200) {
          callback(templateCache[name] = req.responseText);
        }
      };
      req.send(null);
    }
  }

  function renderTemplate(name, params, callback, partials) {
    loadFile(name, function(template) {
      callback(Mustache.render(template, params, partials));
    });
  }

  function summaryText(numFriends, isKept) {
    if (isKept) {
      if (numFriends > 0) {
        return "You and " +
          (numFriends == 1 ? "another friend" : (numFriends + " of your friends")) +
          " kept this.";
      }
      return "You kept this!";
    }
    if (numFriends > 0) {
      return ([,"One","Two","Three","Four"][numFriends] || numFriends) +
        " of your friends kept this.";
    }
    return "To quickly find this page later...";
  }

  function socialTooltip(friend, element) {
     // disabled for now
    renderTemplate("social_hover.html",{"friend": friend}, function(tmpl) {
      var timeout;
      var timein;

      var friendTooltip = $('.friend_tooltip').first().clone().appendTo('.friendlist').html(tmpl);

      var socialNetworks = chrome.extension.getURL("social-icons.png");
      $(friendTooltip).find('.kn_social').css('background-image','url(' + socialNetworks + ')');

      function hide() {
        timeout = setTimeout(function () {
          $(friendTooltip).fadeOut(100);
        }, 600);
        clearTimeout(timein);
      };

      function show() {
        timein = setTimeout(function() {
          $(friendTooltip).stop().fadeIn(100);
        }, 500)
      }

      $(element).mouseover(function() {
        clearTimeout(timeout);
        show();
      }).mouseout(hide);

      $(friendTooltip).mouseover(function() {
        clearTimeout(timeout);
      }).mouseout(hide);

    });
  }

  function keepPage(shouldSlideOut) {
    log("[keepPage]", document.location.href);

    chrome.extension.sendRequest({
      "type": "set_page_icon",
      "is_kept": true});
    isKept = true;
    if (shouldSlideOut) {
      keptItslideOut();
    }

    var request = {
      "type": "add_bookmarks",
      "url": document.location.href,
      "title": document.title,
      "private": $("#keepit_private").is(":checked")
    };
    chrome.extension.sendRequest(request, function(response) {
     log("[keepPage] response", response);
    });
  }

  function unkeepPage() {
    log("[unkeepPage]", document.location.href);

    chrome.extension.sendRequest({"type": "set_page_icon", "is_kept": false});
    isKept = false;
    slideOut();

    chrome.extension.sendRequest({
        "type": "unkeep",
        "url": document.location.href.replace(/#.*/, "")},
      function(response) {
        log("[unkeepPage] response", response);
      });
  }

  function showKeepItHover(user) {
    var logo = chrome.extension.getURL('kifilogo.png');
    var arrow = chrome.extension.getURL('arrow.png');
    var facebookProfileLink = "http://www.facebook.com/" + user.facebook_id;
    var facebookImageLink = "https://graph.facebook.com/" + user.facebook_id + "/picture?type=square";
    var userExternalId = user.keepit_external_id;

    $.get("http://" + config.server + "/users/slider?url=" + encodeURIComponent(document.location.href),
      null,
      function(o) {
        log(o);

        isKept = o.kept;

        var tmpl = {
          "logo": logo,
          "arrow": arrow,
          "profilepic": facebookImageLink,
          "name": user.name,
          "is_kept": o.kept,
          "connected_networks": chrome.extension.getURL("icons/connected_networks.png")
        }

        following = o.following;

        if (o.friends.length) {
          tmpl.socialConnections = {
            countText: summaryText(o.friends.length, o.kept),
            friends: o.friends
          }
        }

        loadFile("templates/footer.html", function(footer) {
        loadFile("templates/main_hover.html", function(main_hover) {
          var partials = {
            "main_hover": main_hover,
            "footer": footer
          }

          renderTemplate('kept_hover.html', tmpl, function(template) {
            drawKeepItHover(user, o.friends, o.numComments, o.numMessages, template);
          }, partials);
        });
        });
      }
    );
  }

  function drawKeepItHover(user, friends, numComments, numMessages, renderedTemplate) {
    if ($('.kifi_hover').length > 0) {
      // nevermind!
      log("No need to inject, it's already here!");
      return;
    }

    // Inject the slider!
    $('body').prepend(renderedTemplate);

    $('.social_friend').each(function(i,e) {
      socialTooltip(friends[i],e);
    });

    updateCommentCount("public", numComments);
    updateCommentCount("message", numMessages);

    // Event bindings

    $(".kifi_hover").draggable({cursor: "move", axis: "y", distance: 10, handle: "div.kifihdr", containment: "body", scroll: false});

    $('.xlink').click(function() {
      slideOut();
    });
    //$('.profilepic').click(function() { location=facebookProfileLink; });

    $('.unkeepitbtn').click(unkeepPage);

    $('.keepitbtn').click(function() {
      keepPage(true);
    });

    $('.dropdownbtn').click(function() {
      $('.moreinnerbox').slideToggle(150);
    });

    $('.comments-label').click(function() {
      showComments(user, "public");
    });

    $('.messages-label').click(function() {
      showComments(user, "message", null, $('.thread-wrapper').length > 0);
    });

    slideIn();
  }

  function keptItslideOut() {
    var position = $('.kifi_hover').position().top;
    $('.kifi_hover').animate({
        bottom: '+=' + position,
        opacity: 0
      },
      900,
      'easeInOutBack',
      function() {
        $('.kifi_hover').detach();
      });
  }

  function slideOut(temporary) {
    $('.kifi_hover').animate({
        opacity: 0,
        right: '-=330'
      },
      300,
      'easeQuickSnapBounce',
      temporary ? $.noop : function() {
        $('.kifi_hover').detach();
      });
  }

  function slideIn() {
    //$("body").after(hover);
    $('.kifi_hover').animate({
        right: '+=330',
        opacity: 1
      },
      400,
      'easeQuickSnapBounce', function() {
        $('.kifi_hover').css({'right': '-20px', 'opacity': 1});
        console.log("opened", $('.kifi_hover')[0], $('.kifi_hover').css('right'))
      });
  }

  function redrawFooter(showFooterNav, type) {
    var footerParams = {
      showFooterNav: showFooterNav,
      isMessages: type == "message",
      isKept: isKept,
      logo: chrome.extension.getURL('kifilogo.png')
    }

    renderTemplate("templates/footer.html", footerParams, function(renderedTemplate) {
      $('.kififtr').html(renderedTemplate);

      $('.kififtr .footer-bar').on('mousedown','.close-message', function() {
        showComments(); // called with no params, hides comments/messages
      })
      .on('mousedown', '.footer-keepit', function() {
        keepPage(false);
        redrawFooter(showFooterNav, type)
      })
      .on('mousedown', '.footer-unkeepit', function() {
        alert("To be implemented.");
      });
    });
  }

  var badGlobalState = { }

  function isCommentPanelVisible() {
    return $(".kifi_comment_wrapper").is(":visible");
  }

  function refreshCommentsHack() {
    if (isCommentPanelVisible() !== true) return;
    hasNewComments(function(){
      updateCommentCount("public", badGlobalState["updates"]["publicCount"]);
      //updateCommentCount("message", badGlobalState["updates"]["messageCount"]);message count includes children, need to fix...
      if (isCommentPanelVisible() !== true) return;
      showComments(badGlobalState.user, badGlobalState.type, badGlobalState.id, true, true);
    });
  }

  function hasNewComments(callback) {
    var url = "http://" + config.server + "/users/slider/updates?url=" + encodeURIComponent(document.location.href);
    $.get(url, null, function(updates) {
      if (badGlobalState["updates"]) {
        var hasUpdates = badGlobalState["updates"]["countSum"] !== updates["countSum"];
        if (hasUpdates && callback) {
          callback();
        }
      }
      badGlobalState["updates"] = updates;
    });
  }

  setInterval(function(){
    refreshCommentsHack();
  }, 5000);

  function showComments(user, type, id, keepOpen, partialRender) {
    var type = type || "public";

    badGlobalState["user"] = user;
    badGlobalState["type"] = type;
    badGlobalState["id"] = id;

    var isVisible = isCommentPanelVisible();
    var showingType = $(".kifi_hover").data("view");
    var shouldRedrawFooter = !isVisible || (showingType && type != showingType)

    if (isVisible && !id && !keepOpen) { // already open!
      if (type == showingType) {
        $('.kifi-content').slideDown();
        $('.kifi_comment_wrapper').slideUp(600, 'easeInOutBack');
        $(".kifi_hover").removeClass(type);
        redrawFooter(false);
        return;
      } else { // already open, yet showing a different type.
        // For now, nothing. Eventually, some slick animation for a quick change?
      }
    }

    $(".kifi_hover").data("view", type).removeClass("public message").addClass(type);

    fetchComments(type, id, function(comments) {
      console.log(comments);
      renderComments(user, comments, type, id, function() {
        if (!isVisible) {
          repositionScroll(false);

          $('.kifi-content').slideUp(); // hide main hover content
          $('.kifi_comment_wrapper').slideDown(600, function() {
            repositionScroll(false);
          });
        }
        if(shouldRedrawFooter) {
          redrawFooter(true, type);
        }
      }, partialRender);
    });
  }

  function fetchComments(type, id, callback) {
    var url = "http://" + config.server +
      (type == "public" ? "/comments/public" : "/messages/threads") +
      (id ? "/" + id : ("?url=" + encodeURIComponent(document.location.href)));
    $.get(url, null, function(payload) {
      callback(payload)
    });
  }

  function commentTextFormatter() {
    return function(text, render) {
      text = $.trim(render(text));
      text = text.replace(/\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:((?:\\\)|[^)])*)\)/g, function($0, $1, $2) {
        return "<a href='x-kifi-sel:" + $2.replace(/\\(.)/g, "$1") + "'>" + $1.replace(/\\(.)/g, "$1") + "</a>";
      });
      text = "<p class=\"first-line\">" + text + "</p>";
      text = text.replace(/\n\n/g, "\n")
      text = text.replace(/\n/g, "</p><p>");
      return text;
    }
  }

  function commentDateFormatter() {
    return function(text, render) {
      try {
        return new Date(render(text)).toString();
      } catch (e) {
        return "";
      }
    }
  }

  function isoDateFormatter() {
    return function(text, render) {
      try {
        return new Date(render(text)).toISOString();
      } catch (e) {
        return "";
      }
    }
  }

  function commentSerializer(html) {
    html = html
      .replace(/<div><br\s*[\/]?><\/div>/gi, '\n')
      .replace(/<br\s*[\/]?>/gi, '\n')
      .replace(/<\/div><div>/gi, '\n')
      .replace(/<div\s*[\/]?>/gi, '\n')
      .replace(/<\/div>/gi, '')
      .replace(/<a [^>]*\bhref="x-kifi-sel:([^"]*)"[^>]*>(.*?)<\/a>/gi, function($0, $1, $2) {
        return "[" + $2.replace(/\]/g, "\\]") + "](x-kifi-sel:" + $1.replace(/\)/g, "\\)") + ")";
      });
    return $('<div>').html(html).text().trim();
  }

  function updateCommentCount(type, count) {
    count = count != null ? count : $(".real-comment").length; // if no count passed in, count DOM nodes

    $({"public": ".comments-count", "message": ".messages-count"}[type])
      .text(count)
      .toggleClass("zero_comments", count == 0);
  }

  function renderComments(user, comments, type, id, onComplete, partialRender) {
    console.log("Drawing comments!");
    comments = comments || {};
    comments["public"] = comments["public"] || [];
    comments["message"] = comments["message"] || [];
    //comments["private"] = comments["private"] || []; // Removed, not for MVP

    var visibleComments = comments[type] || [];

    if(!id) {
      updateCommentCount(type, visibleComments.length);
    }

    var params = {
      kifiuser: {
        "firstName": user.name,
        "lastName": "",
        "avatar": "https://graph.facebook.com/" + user.facebook_id + "/picture?type=square"
      },
      formatComments: commentTextFormatter,
      formatDate: commentDateFormatter,
      formatIsoDate: isoDateFormatter,
      comments: visibleComments,
      showControlBar: type == "public",
      following: following,
      snapshotUri: chrome.extension.getURL("snapshot.png"),
      connected_networks: chrome.extension.getURL("icons/social-icons.png")
    }

    loadFile("templates/comments/hearts.html", function(hearts) {
    loadFile("templates/comments/comments_list.html", function(comment_list) {
    loadFile("templates/comments/comment.html", function(comment) {
    loadFile("templates/comments/thread_info.html", function(thread_info) {
    loadFile("templates/comments/thread.html", function(thread) {
    loadFile("templates/comments/message_list.html", function(message_list) {
    loadFile("templates/comments/comment_post.html", function(comment_post) {
    loadFile("templates/comments/message_post.html", function(message_post) {

      var partials = {
        "comment_body_view": comment_list,
        "hearts": hearts,
        "comment": comment,
        "comment_post_view": comment_post
      };

      if (visibleComments.length && visibleComments[0].user && visibleComments[0].user.externalId) {
        for (msg in visibleComments) {
          visibleComments[msg]["isLoggedInUser"] = visibleComments[msg].user.externalId == user.keepit_external_id
        }
      }

      // By default we use the comment partials.
      // To override for a specific function, do so here.
      if (type == "message") {
        partials.comment = (id ? comment : thread_info);
        partials.comment_body_view = (id ? thread : message_list);
        partials.comment_post_view = message_post;

      // For thread lists, we need to do this for each one. For threads, only the first one
        var iterMessages = (id ? [visibleComments[0]] : visibleComments )
        var threadAvatar = "";
        for(msg in iterMessages) {
          var recipients = iterMessages[msg]["recipients"];
          var l = recipients.length;
          if(l == 0) { // No recipients!
            threadAvatar = params.kifiuser.avatar;
          }
          else if(l == 1) {
            threadAvatar = iterMessages[msg]["recipients"][0]["avatar"];
          }
          else {
            threadAvatar = chrome.extension.getURL("icons/convo.png");
          }
          iterMessages[msg]["threadAvatar"] = threadAvatar;

          var recipientNames = [];
          for(r in recipients) {
            var name = recipients[r].firstName + " " + recipients[r].lastName;
            recipientNames.push(name);
          }

          // handled separately because this will need to be refactored to be cleaner
          function formatRecipient(name) {
            return "<strong class=\"recipient\">" + name + "</strong>";
          }

          var displayedRecipients = [];
          var storedRecipients = [];
          if(l == 0) {
            displayedRecipients.push(user.name);
          }
          else if(l <= 4) {
            displayedRecipients = recipientNames.slice(0, l);
          }
          else {
            displayedRecipients = recipientNames.slice(0, 3);
            storedRecipients = recipientNames.slice(3);
          }

          for(d in displayedRecipients) {
            displayedRecipients[d] = formatRecipient(displayedRecipients[d]);
          }

          var recipientText;
          if(l == 0) {
            recipientText = displayedRecipients[0];
          } else if(l <= 4) {
            if(l == 1)
              recipientText = displayedRecipients[0];
            else if(l == 2)
              recipientText = displayedRecipients[0] + " and " + displayedRecipients[1];
            else if(l == 3 || l == 4)
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

        if(id) {
          var othersInConversation = {};
          var recipientCount = 0;
          for(msg in visibleComments) {
            var recipients = visibleComments[msg]["recipients"];
            var initiatorId = visibleComments[msg].user.externalId;
            if (initiatorId != config.user.keepit_external_id) {
              var initiatorName = visibleComments[msg].user.firstName + " " + visibleComments[msg].user.lastName;
              othersInConversation[visibleComments[msg].user.externalId] = initiatorName;
            }
            for(r in recipients) {
              var name = recipients[r].firstName + " " + recipients[r].lastName;
              var recipientId = recipients[r].externalId;
              if (recipientId != config.user.keepit_external_id) {
                othersInConversation[recipientId] = name;
              }
            }
          }
          var othersInConversationText = "";
          for (id in othersInConversation) {
            recipientCount++;
            if (othersInConversationText.length > 1) {
              othersInConversationText += ", ";
            }
            othersInConversationText += "<strong>" + othersInConversation[id] + "</strong>";
          }
          params.othersInConversationText = othersInConversationText;
          params.recipientText = visibleComments[0].recipientText;
          params.storedRecipients = visibleComments[0].storedRecipients;
          params.externalId = visibleComments[0].externalId;
          params.recipientCount = recipientCount;
          params.recipientCountText = recipientCount == "1" ? "person" : "people";
          params.hideComposeTo = true;
        }
      }

      if(partialRender) {
        // Hacky solution for a partial refresh. Needs to be refactored.
        var renderedTemplate = Mustache.render(partials.comment_body_view, params, partials);
        $('.comment_body_view').html(renderedTemplate).find("time").timeago();
      }
      else {
        renderTemplate("templates/comments/comments_view.html", params, function(renderedTemplate) {
          drawCommentView(renderedTemplate, user, type, partials);
          onComplete();
        }, partials);
      }


    });
    });
    });
    });
    });
    });
    });
    });
  }

  function drawCommentView(renderedTemplate, user, type, partials) {
    //console.log(renderedTemplate);
    repositionScroll(false);
    $('.kifi_comment_wrapper').html(renderedTemplate).find("time").timeago();
    repositionScroll(false);

    createCommentBindings(type, user);
  }

  function createCommentBindings(type, user) {
    $(".control-bar").on("click", ".follow", function() {
      following = !following;  // TODO: server should return whether following along with the comments
      $.ajax("http://" + config.server + "/comments/follow?url=" + encodeURIComponent(document.location.href),
        {"type": following ? "POST" : "DELETE"});
      $(this).toggleClass("following", following);
    });

    $(".kifi_comment_wrapper").on("mousedown", "a[href^='x-kifi-sel:']", function(e) {
      if (e.which != 1) return;
      e.preventDefault();
      var el = snapshot.fuzzyFind(this.href.substring(11));
      if (el) {
        // make absolute positioning relative to document instead of viewport
        document.documentElement.style.position = "relative";

        var aRect = this.getBoundingClientRect();
        var elRect = el.getBoundingClientRect();
        var sTop = e.pageY - e.clientY, sLeft = e.pageX - e.clientX;
        var ms = scrollTo(elRect);
        $("<div class=snapshot-highlight>").css({
          left: aRect.left + sLeft,
          top: aRect.top + sTop,
          width: aRect.width,
          height: aRect.height
        }).appendTo("body").animate({
          left: elRect.left + sLeft - 3,
          top: elRect.top + sTop - 2,
          width: elRect.width + 6,
          height: elRect.height + 4
        }, ms).delay(2000).fadeOut(1000, function() {$(this).remove()});
      } else {
        alert("Sorry, this reference is no longer valid on this page.");
      }

      function scrollTo(r) {  // TODO: factor out for reuse
        var pad = 100;
        var hWin = $(window).height();
        var wWin = $(window).width();
        var sTop = $(document).scrollTop(), sTop2;
        var sLeft = $(document).scrollLeft(), sLeft2;
        var oTop = sTop + r.top;
        var oLeft = sLeft + r.left;

        if (r.height + 2 * pad < hWin) { // fits with space around it
          sTop2 = (sTop > oTop - pad) ? oTop - pad :
            (sTop + hWin < oTop + r.height + pad) ? oTop + r.height + pad - hWin : sTop;
        } else if (r.height < hWin) { // fits without full space around it, so center
          sTop2 = oTop - (hWin - r.height) / 2;
        } else { // does not fit, so get it to fill up window
          sTop2 = sTop < oTop ? oTop : (sTop + hWin > oTop + r.height) ? oTop + r.height - hWin : sTop;
        }
        sTop2 = Math.max(0, sTop2);

        if (r.width + 2 * pad < wWin) { // fits with space around it
          sLeft2 = (sLeft > oLeft - pad) ? oLeft - pad :
            (sLeft + wWin < oLeft + r.width + pad) ? oLeft + r.width + pad - wWin : sLeft;
        } else if (r.width < wWin) { // fits without full space around it, so center
          sLeft2 = oLeft - (wWin - r.width) / 2;
        } else { // does not fit, so get it to fill up window
          sLeft2 = sLeft < oLeft ? oLeft : (sLeft + wWin > oLeft + r.width) ? oLeft + r.width - wWin : sLeft;
        }
        sLeft2 = Math.max(0, sLeft2);

        if (sTop2 == sTop && sLeft2 == sLeft) return 400;

        var ms = Math.max(400, Math.min(800, 100 * Math.log(Math.max(Math.abs(sLeft2 - sLeft), Math.abs(sTop2, sTop)))));
        $("<b>").css({position: "absolute", opacity: 0, display: "none"}).appendTo("body").animate({opacity: 1}, {
            duration: ms,
            step: function(a) {
              window.scroll(
                sLeft2 * a + sLeft * (1 - a),
                sTop2 * a + sTop * (1 - a));
            }, complete: function() {
              $(this).remove()
            }});
        return ms;
      }
    }).on("click", "a[href^='x-kifi-sel:']", function(e) {
      e.preventDefault();
    });

    $('.comment_body_view').on("hover", ".more-recipients", function(event) {
      //$(this).parent().find('.more-recipient-list')[event.type == 'mouseenter' ? "show" : "hide"]();
    }).on('click', '.thread-info', function() {
      showComments(user, type, $(this).parent().data("externalid"));
    }).on('click', '.back-button', function() {
      showComments(user, type, null, true);
    });

    $('.comment_post_view').on("mousedown", ".post_to_network .kn_social", function() {
      alert("Not yet implemented. Coming soon!");
      return;
    });

    if (type == "message") {
      $.get("http://" + config.server + "/users/friends?url=" + encodeURIComponent(document.location.href),
        null,
        function(data) {
          var friends = data.friends; //TODO!
          for (var friend in friends) {
            friends[friend].name = friends[friend].firstName + " " + friends[friend].lastName
          }
          $("#to-list").tokenInput(friends, {
            theme: "kifi"
          });
          $("#token-input-to-list").keypress(function(e) {
            return e.which !== 13;
          });
        }
      );
    }

    // Main comment textarea
    var editableFix = $('#editableFix');

    var typeName = type == "public" ? "comment" : "message";
    var placeholder = "<span class=\"placeholder\">Add a " + typeName + "…</span>";
    $('.comment-compose').html(placeholder);
    $('.comment_post_view').on('focus','.comment-compose',function() {
      if ($('.comment-compose').html() == placeholder) { // unchanged text!
        $('.comment-compose').html("");
      }
      $('.comment-compose').animate({'height': '85'}, 150, 'easeQuickSnapBounce');
    }).on('blur', '.comment-compose', function() {
      editableFix[0].setSelectionRange(0, 0);
      editableFix.blur();

      var value = $('.comment-compose').html()
      value = commentSerializer(value);
      if (value == "") { // unchanged text!
        $('.comment-compose').html(placeholder);
      }
      $('.comment-compose').animate({'height': '35'}, 150, 'easeQuickSnapBounce');
    }).on('click','.take-snapshot', function() {
      // make absolute positioning relative to document instead of viewport
      document.documentElement.style.position = "relative";

      slideOut(true);

      var sel = {}, cX, cY;
      var $shades = $(["t","b","l","r"].map(function(s) {
        return $("<div class='snapshot-shade snapshot-shade-" + s + "'>")[0];
      }));
      var $glass = $("<div class=snapshot-glass>");
      var $selectable = $shades.add($glass).appendTo("body").on("mousemove", function(e) {
        updateSelection(cX = e.clientX, cY = e.clientY, e.pageX - e.clientX, e.pageY - e.clientY);
      });
      renderTemplate("templates/comments/snapshot_bar.html", {"type": typeName}, function(html) {
        $(html).appendTo("body")
          .draggable({cursor: "move", distance: 10, handle: ".snapshot-bar", scroll: false})
          .on("click", ".cancel", exitSnapshotMode)
          .add($shades).css("opacity", 0).animate({opacity: 1}, 300);
        key.setScope("snapshot");
        key("esc", "snapshot", exitSnapshotMode);
      });
      $(window).scroll(function() {
        if (sel) updateSelection(cX, cY);
      });
      $glass.click(function() {
        exitSnapshotMode();
        $(".kifi_hover").find(".comment-compose")
          .find(".placeholder").remove().end()
          .append(" <a href='x-kifi-sel:" + snapshot.generateSelector(sel.el).replace("'", "&#39;") + "'>look here</a>");
      });
      function exitSnapshotMode() {
        $selectable.add(".snapshot-bar-wrap").animate({opacity: 0}, 400, function() { $(this).remove(); });
        key.deleteScope("snapshot");
        slideIn();
      }
      function updateSelection(clientX, clientY, scrollLeft, scrollTop) {
        $selectable.hide();
        var el = document.elementFromPoint(clientX, clientY);
        $selectable.show();
        if (!el) return;
        if (scrollLeft == null) scrollLeft = document.body.scrollLeft;
        if (scrollTop == null) scrollTop = document.body.scrollTop;
        var pageX = scrollLeft + clientX;
        var pageY = scrollTop + clientY;
        if (el === sel.el) {
          // track the latest hover point over the current element
          sel.x = pageX; sel.y = pageY;
        } else {
          var r = el.getBoundingClientRect();
          var dx = Math.abs(pageX - sel.x);
          var dy = Math.abs(pageY - sel.y);
          if (!sel.el ||
              (dx == 0 || r.width < sel.r.width * 2 * dx) &&
              (dy == 0 || r.height < sel.r.height * 2 * dy) &&
              (dx == 0 && dy == 0 || r.width * r.height < sel.r.width * sel.r.height * Math.sqrt(dx * dx + dy * dy))) {
            // if (sel.el) console.log(
            //   r.width + " < " + sel.r.width + " * 2 * " + dx + " AND " +
            //   r.height + " < " + sel.r.height + " * 2 * " + dy + " AND " +
            //   r.width * r.height + " < " + sel.r.width * sel.r.height + " * " + Math.sqrt(dx * dx + dy * dy));
            var yT = scrollTop + r.top - 2;
            var yB = scrollTop + r.bottom + 2;
            var xL = scrollLeft + r.left - 3;
            var xR = scrollLeft + r.right + 3;
            $shades.eq(0).css({height: yT});
            $shades.eq(1).css({top: yB, height: document.documentElement.scrollHeight - yB});
            $shades.eq(2).css({top: yT, height: yB - yT, width: xL});
            $shades.eq(3).css({top: yT, height: yB - yT, left: xR});
            $glass.css({top: yT, height: yB - yT, left: xL, width: xR - xL});
            sel.el = el; sel.r = r; sel.x = pageX; sel.y = pageY;
          }
        }
      }
    }).on('submit','.comment_form', function(e) {
      e.preventDefault();
      var text = commentSerializer($('.comment-compose').find(".placeholder").remove().end().html());
      if (!text) return false;

      submitComment(text, type, user, null, null, function(newComment) {
        $('.comment-compose').text("").html(placeholder).blur();

        console.log("new comment", newComment);
        // Clean up CSS

        var params = newComment;

        newComment.isLoggedInUser = true;
        params["formatComments"] = commentTextFormatter;
        params["formatDate"] = commentDateFormatter;
        params["formatIsoDate"] = isoDateFormatter;

        badGlobalState["updates"].publicCount++;
        badGlobalState["updates"].countSum++;

        renderTemplate("templates/comments/comment.html", params, function(renderedComment) {
          //drawCommentView(renderedTemplate, user, type, partials);
          $('.comment_body_view').find('.no-comment').parent().detach();
          $('.comment_body_view').append(renderedComment).find("time").timeago();
          updateCommentCount(type);
          repositionScroll(false);
        });
      });
      return false;
    }).on('submit','.message_form', function(e) {
      e.preventDefault();
      var text = commentSerializer($('.comment-compose').find(".placeholder").remove().end().html());
      if (!text) return false;

      var isReply = $(this).is('.message-reply');
      var recipients;
      var parent;

      badGlobalState["updates"].messageCount++;
      badGlobalState["updates"].countSum++;

      if(!isReply) {
        var recipientJson = $("#to-list").tokenInput("get");
        $("#to-list").tokenInput("clear");

        var recipientArr = [];
        for(r in recipientJson) {
          recipientArr.push(recipientJson[r]["externalId"]);
        }
        if(recipientArr.length == 0) {
          alert("Silly you. You need to add some friends!");
          return false;
        }
        recipients = recipientArr.join(",");
        console.log("to: ", recipients);
      }
      else {
        parent = $(this).parents(".kifi_comment_wrapper").find(".thread-wrapper").attr("data-externalid");
        console.log(parent)
      }

      submitComment(text, type, user, parent, recipients, function(newComment) {
        $('.comment-compose').text("").html(placeholder).blur();

        console.log("new message", newComment);
        // Clean up CSS

        if(!isReply) {
          updateCommentCount(type,parseInt($('.messages-count').text()) + 1)
          console.log("not a reply. redirecting to new message");
          showComments(user, type, newComment.message.externalId);
          return;
        }

        var params = newComment.message;
        newComment.message.isLoggedInUser = true;
        params["formatComments"] = commentTextFormatter;
        params["formatDate"] = commentDateFormatter;
        params["formatIsoDate"] = isoDateFormatter;

        renderTemplate("templates/comments/comment.html", params, function(renderedComment) {
          //drawCommentView(renderedTemplate, user, type, partials);
          $('.comment_body_view').find('.no-comment').parent().detach();
          $('.thread-wrapper').append(renderedComment).find("time.timeago").timeago();
          repositionScroll(false);
        });

      });
      return false;
    });
  }

  function submitComment(text, type, user, parent, recipients, callback) {
    /* Because we're using very simple templating now, re-rendering has to be done carefully.
     */
    var permissions = type;

    console.log(parent);

    var request = {
      "type": "post_comment",
      "url": document.location.href,
      "title": document.title,
      "text": text,
      "permissions": type,
      "parent": parent,
      "recipients": recipients
    };
    chrome.extension.sendRequest(request, function(response) {
      var newComment;
      if(type == "message") {
        console.log("fff",response)
        newComment = response;
      }
      else {
        newComment = {
          "createdAt": new Date,
          "text": request.text,
          "user": {
            "externalId": user.keepit_external_id,
            "firstName": user.name,
            "lastName": "",
            "facebookId": user.facebook_id
          },
          "permissions": type,
          "externalId": response.commentId
        }
      }
      callback(newComment);
    });
  }

  key('command+shift+k, ctrl+shift+k', function(){
    console.log('Opening kifi slider!');

    var existingElements = $('.kifi_hover').length;
    if (existingElements > 0) {
      slideOut();
      return;
    }
    chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
      config = response;
      console.log("user config",response);
      getUserInfo(showHover);
    });

    key.setScope('kifi_open');

    return false;
  });

  function repositionScroll(resizeQuickly) {
    resizeCommentBodyView(resizeQuickly);
    $(".comment_body_view").prop("scrollTop", 99999);
  }

  function resizeCommentBodyView(resizeQuickly) {
    console.log("hit")
    $('.kifi_hover').css({'top':''});
    $('.comment_body_view').stop().css({'max-height':$(window).height()-320});
    return; // for now, we'll do a rough fix
    var kifiheader = $('.kifihdr');
    if (resizeQuickly === true) {
      $('.comment_body_view').stop().css({'max-height':$(window).height()-320});
    } else {
      if (kifiheader.length > 0) {
        var offset = Math.round(kifiheader.offset().top - 30);
        if(Math.abs(offset) > 20) {
          $('.comment_body_view').stop().animate({'max-height':'+='+offset},20, function() {
            if(Math.abs($('.comment_body_view').height() - offset) > 2) {
              return;
            }
            var newOffset = Math.abs(kifiheader.offset().top - 30);
            if (newOffset > 20) {
              resizeCommentBodyView(false);
            }
          });
        }
      }
    }
  }

  $(window).resize(function() {
    resizeCommentBodyView();
  });

  chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
    config = response;
    console.log("user config",response);
    hasNewComments();
  });

})();
