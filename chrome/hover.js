console.log("[" + new Date().getTime() + "] ", "injecting keep it hover div");

(function() {
  var config, following;

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
      req.open("GET",chrome.extension.getURL(name), true);
      req.onreadystatechange = function() {
          if (req.readyState == 4 && req.status == 200) {
            var response = req.responseText
            callback(response);
            templateCache[name] = response;
          }
      };
      req.send(null);
    }
  }

  function renderTemplate(name, params, callback, partials) {
    loadFile(name, function(contents) {
      var tb = Mustache.render(contents, params, partials);
      callback(tb);
    });
  }

  function summaryText(numFriends, isKept) {
    if (isKept) {
      if (numFriends > 0) {
        return "You and " +
          (numFriends == 1 ? "another friend" : (numFriends + " of your friends")) +
          "kept this.";
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

        var tmpl = {
          "logo": logo,
          "arrow": arrow,
          "profilepic": facebookImageLink,
          "name": user.name,
          "is_kept": o.kept
        }

        following = o.following;

        if (o.friends.length) {
          tmpl.socialConnections = {
            countText: summaryText(o.friends.length, o.kept),
            friends: o.friends
          }
        }

        loadFile("templates/main_hover.html", function(main_hover) {
          var partials = {
            "main_hover": main_hover 
          }

          renderTemplate('kept_hover.html', tmpl, function(template) {
            drawKeepItHover(user, o.friends, o.numComments, o.numMessages, template);
          }, partials);
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

    $(".kifi_hover").draggable({ cursor: "move", axis: "y", distance: 20, handle: "div.kifihdr", containment: "body", scroll: false});

    $('.xlink').click(function() {
      slideOut();
    });
    //$('.profilepic').click(function() { location=facebookProfileLink; });

    $('.unkeepitbtn').click(function() {
      log("un-bookmarking page: " + document.location.href);
      chrome.extension.sendRequest({
        "type": "set_page_icon",
        "is_kept": false
      });
      slideOut();
    });

    $('.keepitbtn').click(function() {
      log("bookmarking page: " + document.location.href);

      chrome.extension.sendRequest({
        "type": "set_page_icon",
        "is_kept": true
      });

      var request = {
        "type": "add_bookmarks", 
        "url": document.location.href, 
        "title": document.title, 
        "private": $("#keepit_private").is(":checked")
      }
      chrome.extension.sendRequest(request, function(response) {
        log("bookmark added! -> " + JSON.stringify(response));
        keptItslideOut();
     });
    });

    $('.dropdownbtn').click(function() {
      $('.moreinnerbox').slideToggle(150);
    });

    $('.comments-label').click(function() {
      showComments(user, true, "public");
    });

    $('.messages-label').click(function() {
      showComments(user, true, "message");
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

  function slideOut() {
    $('.kifi_hover').animate({
        opacity: 0,
        right: '-=330'
      },
      300, 
      'easeQuickSnapBounce',
      function() {
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
      'easeQuickSnapBounce');
  }

  function showComments(user, toggleOpenState, type) {
    var type = type || "public";

    var isVisible = $('.kifi_comment_wrapper:visible').length > 0;
    var showingType = $('.kifi_comment_wrapper').attr("data-view");

    var needsToOpen = false;

    if (toggleOpenState) {
      $('.interaction-bar li').removeClass('active');

      if (isVisible) { // already open!
        if (type == showingType) {
          $('.kifi-content').slideDown();
          $('.kifi_comment_wrapper').slideUp(600,'easeInOutBack');
          return;
        } else { // already open, yet showing a different type.
          // For now, nothing. Eventually, some slick animation for a quick change?
        }
      } else { // not visible
        needsToOpen = true;
      }

      if (type == 'public') {
        $('.interaction-bar li.comments-label').addClass('active');
        $('.kifi_comment_wrapper').attr("data-view", "public");
      } else if (type == 'message') {
        $('.interaction-bar li.messages-label').addClass('active');
        $('.kifi_comment_wrapper').attr("data-view", "message");
      }
    }

    var userExternalId = user.keepit_external_id;
    $.get("http://" + config.server + "/comments/" + type + "?url=" + encodeURIComponent(document.location.href) + "&externalId=" + userExternalId,
      null,
      function(comments) {
        renderComments(user, comments, type, function() {
          if (needsToOpen) {
            repositionScroll(false);

            $('.kifi-content').slideUp(); // hide main hover content
            $('.kifi_comment_wrapper').slideDown(600, function () {
              repositionScroll(false);
            });
          }
        });
      });
  }

  function commentTextFormatter() {
    return function(text, render) {
      text = $.trim(render(text));
      text = "<p class=\"first-line\">" + text + "</p>";
      text = text.replace(/\n\n/g,"\n")
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

  function commentSerializer(comment) {
    var serialized = comment.replace(/<div><br\s*[\/]?><\/div>/gi, '\n').replace(/<br\s*[\/]?>/gi, '\n').replace(/<\/div><div>/gi, '\n').replace(/<\/div>/gi, '').replace(/<div\s*[\/]?>/gi, '\n').replace(/<\/div>/gi, '');

    /*serialized = serialized + "<abbr data-lookhere='adsasd'>[look here]</abbr>"*/

    serializedHTML = $('<div/>').html(serialized);
    serializedHTML.find('abbr[data-lookhere]').replaceWith(function(a,e) {
      console.log("Found one!",this,a,e);
      return "haha";
    });

    res = serializedHTML.text();
    return $.trim(res);
  }

  function updateCommentCount(type, count) {
    count = count != null ? count : $(".real-comment").length; // if no count passed in, count DOM nodes

    $({"public": ".comments-count", "message": ".messages-count"}[type])
      .text(count)
      .toggleClass("zero_comments", count == 0);
  }

  function renderComments(user, comments, type, onComplete) {
    console.log("Drawing comments!");
    comments = comments || {};
    comments["public"] = comments["public"] || [];
    comments["message"] = comments["message"] || [];
    //comments["private"] = comments["private"] || []; // Removed, not for MVP

    var visibleComments = comments[type] || [];

    updateCommentCount(type, visibleComments.length);
    
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
      following: following
    }

    loadFile("templates/comments/hearts.html", function(hearts) {
    loadFile("templates/comments/comments_list.html", function(comment_list) {
    loadFile("templates/comments/comment.html", function(comment) {
    loadFile("templates/comments/message.html", function(message) {
    loadFile("templates/comments/message_list.html", function(message_list) {
    loadFile("templates/comments/comment_post.html", function(comment_post) {
    loadFile("templates/comments/message_post.html", function(message_post) {

      var partials = {
        "comment_body_view": comment_list,
        "hearts": hearts,
        "comment": comment,
        "comment_post_view": comment_post
      };

      // By default we use the comment partials.
      // To override for a specific function, do so here.
      if (type == 'message') {
        partials.comment = message;
        partials.comment_body_view = message_list;
        partials.comment_post_view = message_post;

        // Blarghhh...
        var threadAvatar = "";
        for(msg in visibleComments) {
          var recipients = visibleComments[msg]["recipients"];
          var l = recipients.length;
          if(l == 0) {
            // No recipients!
            threadAvatar = params.kifiuser.avatar;
          }
          else if(l == 1) {
            threadAvatar = visibleComments[msg]["recipients"][0]["avatar"];
          }
          else {
            threadAvatar = chrome.extension.getURL("icons/convo.png");
          }
          visibleComments[msg]["threadAvatar"] = threadAvatar;

          var recipientNames = [];
          for(r in recipients) {
            recipientNames.push(recipients[r].firstName + " " + recipients[r].lastName)
          }

          // handled separately because this will need to be refactored to be cleaner
          function formatRecipient(name) {
            return "<strong class=\"recipient\">" + name + "</strong>";
          }

          var displayedRecipients = [];
          var storedRecipients = [];
          if(l == 0)
            displayedRecipients.push(user.name);
          else if(l <= 4) {
            displayedRecipients = recipientNames.slice(0,l);
          }
          else {
            displayedRecipients = recipientNames.slice(0,3);
            storedRecipients = recipientNames.slice(3);
          }

          for(d in displayedRecipients) {
            displayedRecipients[d] = formatRecipient(displayedRecipients[d]);
          }

          if(l == 0) {
            recipientText = displayedRecipients[0];
          } else if(l <= 4) {
            if(l == 1)
              recipientText = displayedRecipients[0];
            else if(l == 2)
              recipientText = displayedRecipients[0] + " and " + displayedRecipients[1];
            else if(l == 3 || l == 4)
              recipientText = displayedRecipients.slice(0,l-1).join(", ") + " and " + displayedRecipients[l-1];
          } else {
            recipientText = displayedRecipients.slice(0,3).join(", ");
            storedRecipients = recipientNames.slice(3);
            console.log(recipientText, storedRecipients)
          }

          // todo "You wrote to "

          visibleComments[msg]["recipientText"] = recipientText;
          visibleComments[msg]["storedRecipients"] = storedRecipients;

        }
      }

      renderTemplate("templates/comments/comments_view.html", params, function(renderedTemplate) {
        drawCommentView(renderedTemplate, user, type, partials);
        onComplete();
      }, partials);

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
    $('.kifi_comment_wrapper').html(renderedTemplate);
    repositionScroll(false);

    createCommentBindings(type, user);
  }

  function createCommentBindings(type, user) {
    $("time.timeago").timeago();

    $(".control-bar").on("click", ".follow", function() {
      following = !following;  // TODO: server should return whether following along with the comments
      $.ajax("http://" + config.server + "/comments/follow?url=" + encodeURIComponent(document.location.href),
        {"type": following ? "POST" : "DELETE"});
      $(this).toggleClass("following", following);
    });

    if(type == "message") {
      $.get("http://" + config.server + "/users/friends?url=" + encodeURIComponent(document.location.href),
        null,
        function(data) {
          var friends = data.friends; //TODO!
          for(var friend in friends) {
            friends[friend]["name"] = friends[friend]["firstName"] + " " + friends[friend]["lastName"]
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
    var placeholder = "<span class=\"placeholder\">Add a comment…</span>";
    $('.comment-compose').html(placeholder);
    $('.comment_post_view').on('focus','.comment-compose',function() {
      console.log($('.comment-compose').html(),placeholder);
      if ($('.comment-compose').html() == placeholder) { // unchanged text!
        $('.comment-compose').html("");
      }
      //$('.crosshair').slideDown(150); // Not in mvp
      $('.comment-compose').animate({'height': '85'}, 150, 'easeQuickSnapBounce');
    }).on('blur','.comment-compose',function() {
      var value = $('.comment-compose').html()
      value = commentSerializer(value);
      if (value == "") { // unchanged text!
        $('.comment-compose').html(placeholder);
      }
      $('.comment-compose').animate({'height': '35'}, 150, 'easeQuickSnapBounce');

      //$('.crosshair').slideUp(20);
      //$('.submit-comment').slideUp(20);
    }).on('submit','.comment_form', function(e) {
      e.preventDefault();
      var text = commentSerializer($('.comment-compose').html());

      submitComment(text, type, user, null, null, function(newComment) {
        $('.comment-compose').text("").html(placeholder);

        console.log("new comment", newComment);
        // Clean up CSS

        var params = newComment;
        params["formatComments"] = commentTextFormatter;
        params["formatDate"] = commentDateFormatter;
        params["formatIsoDate"] = isoDateFormatter;

        renderTemplate("templates/comments/comment.html", params, function(renderedComment) {
          //drawCommentView(renderedTemplate, user, type, partials);
          $('.comment_body_view').find('.no-comment').parent().detach();
          $('.comment_body_view').append(renderedComment).find("time.timeago").timeago();
          updateCommentCount();
          repositionScroll(false);
        });

      });
      return false;
    }).on('submit','.message_form', function(e) {
      e.preventDefault();
      var text = commentSerializer($('.comment-compose').html());
      var recipientJson = $("#to-list").tokenInput("get");
      $("#to-list").tokenInput("clear");

      var recipientArr = [];
      for(r in recipientJson) {
        recipientArr.push(recipientJson[r]["externalId"]);
      }
      var recipients = recipientArr.join(",");
      console.log("to: ", recipients);

      submitComment(text, type, user, null, recipients, function(newComment) {
        $('.comment-compose').text("").html(placeholder);

        console.log("new message", newComment);
        // Clean up CSS

        var params = newComment;
        params["formatComments"] = commentTextFormatter;
        params["formatDate"] = commentDateFormatter;

        renderTemplate("templates/comments/comment.html", params, function(renderedComment) {
          //drawCommentView(renderedTemplate, user, type, partials);
          $('.comment_body_view').find('.no-comment').parent().detach();
          $('.comment_body_view').append(renderedComment).find("time.timeago").timeago();
          updateCommentCount();
          repositionScroll(false);
        });

      });
      return false;
    });
  }

  function submitComment(text, type, user, parent, recipients, callback) {
    /* Because we're using very simple templating now, re-rendering has to be done carefully.
     */
    var request = {
      "type": "post_comment",
      "url": document.location.href,
      "text": text,
      "permissions": type,
      "parent": parent,
      "recipients": recipients
    };
    chrome.extension.sendRequest(request, function(response) {
      var newComment = {
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
    var kifiheader = $('.kifihdr');
    if (resizeQuickly === true) {
      $('.comment_body_view').stop().css({'max-height':$(window).height()-280});
    } else {
      if (kifiheader.length > 0) {
        var offset = kifiheader.offset().top - 30;
        $('.comment_body_view').stop().animate({'max-height':'+='+offset},20, function() {
          var newOffset = kifiheader.offset().top - 30;
          if (newOffset < 0) {
            resizeCommentBodyView(false);
          }
        });
      }
    }
  }

  $(window).resize(function() {
    resizeCommentBodyView();
  });

  chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
    config = response;
    console.log("user config",response);
  });

})();
