slider = function() {
  var config, following, isKept;

  $('<input id="editableFix" style="opacity:0;color:transparent;width:1px;height:1px;border:none;margin:0;padding:0;" tabIndex="-1">').appendTo('html')

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

  var templateCache = {};

  function renderTemplate(path, params, partialPaths, callback) {
    // sort out partialPaths and callback (both optional)
    if (!callback && typeof partialPaths == "function") {
      callback = partialPaths;
      partialPaths = undefined;
    }

    loadPartials(path.replace(/[^\/]*$/, ""), partialPaths || {}, function(partials) {
      loadFile(path, function(template) {
        callback(Mustache.render(template, params, partials));
      });
    });

    function loadPartials(basePath, paths, callback) {
      var partials = {}, names = Object.keys(paths), numLoaded = 0;
      if (!names.length) {
        callback(partials);
      } else {
        names.forEach(function(name) {
          loadFile(basePath + paths[name], function(tmpl) {
            partials[name] = tmpl;
            if (++numLoaded == names.length) {
              callback(partials);
            }
          });
        });
      }
    }

    function loadFile(path, callback) {
      var tmpl = templateCache[path];
      if (tmpl) {
        callback(tmpl);
      } else {
        var req = new XMLHttpRequest();
        req.open("GET", chrome.extension.getURL(path), true);
        req.onreadystatechange = function() {
          if (req.readyState == 4 && req.status == 200) {
            callback(templateCache[path] = req.responseText);
          }
        };
        req.send(null);
      }
    }
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
    renderTemplate("templates/social_hover.html", {"friend": friend}, function(tmpl) {
      var timeout;
      var timein;

      var friendTooltip = $('.friend_tooltip').first().clone().appendTo('.friendlist').html(tmpl);

      var socialNetworks = chrome.extension.getURL("images/social_icons.png");
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
    if (shouldSlideOut) keptItslideOut();

    var request = {
      "type": "add_bookmarks",
      "url": document.location.href,
      "title": document.title,
      "private": $("#keepit_private").is(":checked")
    };
    chrome.extension.sendRequest(request, function(response) {
     log("[keepPage] response:", response);
    });
  }

  function unkeepPage(shouldSlideOut) {
    log("[unkeepPage]", document.location.href);

    chrome.extension.sendRequest({"type": "set_page_icon", "is_kept": false});
    isKept = false;
    if (shouldSlideOut) slideOut();

    chrome.extension.sendRequest({
        "type": "unkeep",
        "url": document.location.href.replace(/#.*/, "")},
      function(response) {
        log("[unkeepPage] response:", response);
      });
  }

  function showKeepItHover(user) {
    chrome.extension.sendRequest({type: "get_slider_info"}, function(o) {
      log(o);
      isKept = o.kept;
      following = o.following;

      renderTemplate('templates/kept_hover.html', {
          "logo": chrome.extension.getURL('images/kifilogo.png'),
          "arrow": chrome.extension.getURL('images/triangle_down.31x16.png'),
          "profilepic": "https://graph.facebook.com/" + user.facebook_id + "/picture?type=square",
          "name": user.name,
          "is_kept": o.kept,
          "private": o.private,
          "connected_networks": chrome.extension.getURL("images/networks.png"),
          "socialConnections": o.friends.length == 0 ? null : {
            countText: summaryText(o.friends.length, o.kept),
            friends: o.friends}
        }, {
          "main_hover": "main_hover.html",
          "footer": "footer.html"
        }, function(template) {
          drawKeepItHover(user, o.friends, o.numComments, o.numMessages, template);
        });
    });
  }

  function drawKeepItHover(user, friends, numComments, numMessages, renderedTemplate) {
    if ($('.kifi_hover').length) {
      log("No need to inject, it's already here!");
      return;
    }

    // Inject the slider!
    $('body').append(renderedTemplate);

    $('.social_friend').each(function(i,e) {
      socialTooltip(friends[i],e);
    });

    updateCommentCount("public", numComments);
    updateCommentCount("message", numMessages);

    // Event bindings
    var t0 = new Date().getTime();
    $(".kifi_hover").draggable({cursor: "move", axis: "y", distance: 10, handle: "div.kifihdr", containment: "body", scroll: false})
    .on("click", ".xlink", function() {
      logEvent("slider","sliderClosedByX",{"delay":(new Date().getTime() - t0)});
      slideOut();
    })
    // .on("click", ".profilepic", function() {
    //   location = "http://www.facebook.com/" + user.facebook_id;
    // })
    .on("click", ".unkeepitbtn", function() {
      unkeepPage(true);
    })
    .on("click", ".keepitbtn", function() {
      keepPage(true);
    })
    .on("click", ".makeprivatebtn", function() {
      var $btn = $(this), priv = /private/i.test($btn.text());
      log("[setPrivate] " + priv);
      chrome.extension.sendRequest({
          "type": "set_private",
          "url": document.location.href.replace(/#.*/, ""),
          "private": priv},
        function(response) {
          log("[setPrivate] response:", response);
          $btn.text("Make it " + (priv ? "Public" : "Private"));
        });
    })
    .on("click", ".dropdownbtn", function() {
      $('.moreinnerbox').slideToggle(150);
    })
    .on("click", ".comments-label", function() {
      showComments(user, "public");
    })
    .on("click", ".messages-label", function() {
      showComments(user, "message", null, $('.thread-wrapper').length > 0);
    })
    .on("click mouseup mousedown keypress keyup keydown", function(e) {
      e.stopPropagation();
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
        right: '-=340'
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
        right: '+=340',
        opacity: 1
      },
      400,
      'easeQuickSnapBounce', function() {
        $('.kifi_hover').css({'right': '-10px', 'opacity': 1});
        log("opened", $('.kifi_hover')[0], $('.kifi_hover').css('right'))
      });
  }

  function redrawFooter(showFooterNav, type) {
    var footerParams = {
      showFooterNav: showFooterNav,
      isMessages: type == "message",
      isKept: isKept,
      logo: chrome.extension.getURL('images/kifilogo.png')
    }

    renderTemplate("templates/footer.html", footerParams, function(renderedTemplate) {
      $('.kififtr').html(renderedTemplate);

      $('.kififtr .footer-bar').on('mousedown','.close-message', function() {
        showComments(); // called with no params, hides comments/messages
      })
      .on('mousedown', '.footer-keepit', function(e) {
        e.preventDefault();
        keepPage(false);
        redrawFooter(showFooterNav, type);
        // TODO: update message/buttons on main panel
      })
      .on('mousedown', '.footer-unkeepit', function(e) {
        e.preventDefault();
        unkeepPage(false);
        redrawFooter(showFooterNav, type);
        // TODO: update message/buttons on main panel
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
    chrome.extension.sendRequest({type: "get_slider_updates"}, function(updates) {
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

    chrome.extension.sendRequest({type: "get_comments", kind: type, commentId: id}, function(comments) {
      log(comments);
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

  function commentTextFormatter() {
    return function(text, render) {
      // Careful... this is raw text (necessary for URL detection). Be sure to Mustache.escape untrusted portions!
      text = render(text);

      // linkify look-here links (from markdown)
      var parts = text.split(/\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:((?:\\\)|[^)])*)\)/);
      for (var i = 1; i < parts.length; i += 3) {
        parts[i] = "<a href='x-kifi-sel:" + parts[i+1].replace(/\\\)/g, ")") + "'>" + Mustache.escape(parts[i].replace(/\\\]/g, "]")) + "</a>";
        parts[i+1] = "";
      }

      for (i = 0; i < parts.length; i += 3) {
        // linkify URLs, from http://regex.info/listing.cgi?ed=3&p=207
        var bits = parts[i].split(/(\b(?:(ftp|https?):\/\/[-\w]+(?:\.\w[-\w]*)+|(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|[a-z][a-z]\b))(?::[0-9]{1,5})?(?:\/[^.!,?;"'<>()\[\]{}\s\x7F-\xFF]*(?:[.!,?]+[^.!,?;"'<>()\[\]{}\s\x7F-\xFF]+)*)?)/);
        for (var j = 1; j < bits.length; j += 3) {
          var escapedUri = Mustache.escape(bits[j]);
          bits[j] = '<a target=_blank href="' + (bits[j+1] ? ""  : "http://") + escapedUri + '">' + escapedUri + "</a>";
          bits[j+1] = "";
        }
        for (j = 0; j < bits.length; j += 3) {
          bits[j] = Mustache.escape(bits[j]);
        }
        parts[i] = bits.join("");
      }

      return "<p class=first-line>" + parts.join("").replace(/\n(?:[ \t\r]*\n)*/g, "</p><p>") + "</p>";
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
    log("Drawing comments!");
    comments = comments || {};
    comments["public"] = comments["public"] || [];
    comments["message"] = comments["message"] || [];
    //comments["private"] = comments["private"] || []; // Removed, not for MVP

    var visibleComments = comments[type] || [];

    if (!id) {
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
      snapshotUri: chrome.extension.getURL("images/snapshot.png"),
      connected_networks: chrome.extension.getURL("images/social_icons.png")
    };

    // TODO: fix indentation below

      if (visibleComments.length && visibleComments[0].user && visibleComments[0].user.externalId) {
        for (msg in visibleComments) {
          visibleComments[msg]["isLoggedInUser"] = visibleComments[msg].user.externalId == user.keepit_external_id
        }
      }

      if (type == "message") {
        // For thread lists, we need to do this for each one. For threads, only the first one
        var iterMessages = (id ? [visibleComments[0]] : visibleComments)
        var threadAvatar = "";
        for (msg in iterMessages) {
          var recipients = iterMessages[msg]["recipients"];
          var l = recipients.length;
          if(l == 0) { // No recipients!
            threadAvatar = params.kifiuser.avatar;
          }
          else if(l == 1) {
            threadAvatar = iterMessages[msg]["recipients"][0]["avatar"];
          }
          else {
            threadAvatar = chrome.extension.getURL("images/convo.png");
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

        if (id) {
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

      var partials = {
        "comment_body_view": type != "message" ? "comments_list.html" : id ? "thread.html" : "message_list.html",
        "hearts": "hearts.html",
        "comment": type != "message" || id ? "comment.html" : "thread_info.html",
        "comment_post_view": type != "message" ? "comment_post.html" : "message_post.html"};
      if (partialRender) {
        // Hacky solution for a partial refresh. Needs to be refactored.
        renderTemplate("templates/comments/" + partials.comment_body_view, params, partials, function(renderedTemplate) {
          $('.comment_body_view').html(renderedTemplate).find("time").timeago();
        });
      } else {
        renderTemplate("templates/comments/comments_view.html", params, partials, function(renderedTemplate) {
          drawCommentView(renderedTemplate, user, type);
          onComplete();
        });
      }
  }

  function drawCommentView(renderedTemplate, user, type) {
    //log(renderedTemplate);
    repositionScroll(false);
    $('.kifi_comment_wrapper').html(renderedTemplate).find("time").timeago();
    repositionScroll(false);

    createCommentBindings(type, user);
  }

  function createCommentBindings(type, user) {
    $(".control-bar").on("click", ".follow", function() {
      following = !following;  // TODO: server should return whether following along with the comments
      chrome.extension.sendRequest({type: "follow", follow: following});
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
      chrome.extension.sendRequest({type: "get_friends"}, function(data) {
        log("friends:", data);
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
      });
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
            // if (sel.el) log(
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

        log("new comment", newComment);
        // Clean up CSS

        var params = newComment;

        newComment.isLoggedInUser = true;
        params["formatComments"] = commentTextFormatter;
        params["formatDate"] = commentDateFormatter;
        params["formatIsoDate"] = isoDateFormatter;

        badGlobalState["updates"].publicCount++;
        badGlobalState["updates"].countSum++;

        renderTemplate("templates/comments/comment.html", params, function(renderedComment) {
          //drawCommentView(renderedTemplate, user, type);
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
        log("to: ", recipients);
      }
      else {
        parent = $(this).parents(".kifi_comment_wrapper").find(".thread-wrapper").attr("data-externalid");
        log(parent)
      }

      submitComment(text, type, user, parent, recipients, function(newComment) {
        $('.comment-compose').text("").html(placeholder).blur();

        log("new message", newComment);
        // Clean up CSS

        if(!isReply) {
          updateCommentCount(type,parseInt($('.messages-count').text()) + 1)
          log("not a reply. redirecting to new message");
          showComments(user, type, newComment.message.externalId);
          return;
        }

        var params = newComment.message;
        newComment.message.isLoggedInUser = true;
        params["formatComments"] = commentTextFormatter;
        params["formatDate"] = commentDateFormatter;
        params["formatIsoDate"] = isoDateFormatter;

        renderTemplate("templates/comments/comment.html", params, function(renderedComment) {
          //drawCommentView(renderedTemplate, user, type);
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

    log(parent);

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
        log("fff",response)
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

  function repositionScroll(resizeQuickly) {
    resizeCommentBodyView(resizeQuickly);
    $(".comment_body_view").prop("scrollTop", 99999);
  }

  function resizeCommentBodyView(resizeQuickly) {
    log("[resizeCommentBodyView]");
    $('.kifi_hover').css("top", "");
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

  return {  // the slider API
    show: function() {
      var user;
      if (!config) {
        chrome.extension.sendRequest({"type": "get_conf"}, function(o) {
          log("config:", o);
          config = o;
          slider.show();
        });
      } else if ((user = config.user) && user.keepit_external_id && user.facebook_id && user.name && user.avatar_url) {
        showKeepItHover(user);
        this.alreadyShown = true;
      } else {
        log("No user, can't show slider");
      }
    },
    toggle: function() {
      if (document.querySelector(".kifi_hover")) {
        slideOut();
      } else {
        this.show();
      }
    },
    isShowing: function() {
      return document.querySelector(".kifi_hover");
    }
  };
}();
