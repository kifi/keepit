console.log("[" + new Date().getTime() + "] ", "injecting keep it hover div");

(function() {
  $ = jQuery.noConflict()
  var config;
  var hover;
  var timeoutCollection = {};
  var TIMEOUTS = {
    REPLY_REFRESH: 4000
  };
  var openedCommentsType = "";

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
    chrome.extension.sendRequest({"type": "get_user_info"}, function(userInfo) {
      callback(userInfo);
    });
  }

  function showHover(user) {
    window.kifi_hover = true; // set global variable, so hover will not automatically slide out again.

    if(!user || !user.keepit_external_id) {
      log("No user info! Can't search.")
      return;
    }

    log("checking location: " + document.location.href)
    chrome.extension.sendRequest({
      "type": "is_already_kept",
      "location": document.location.href
    }, function(is_kept) {
      user.is_kept = is_kept;
      showKeepItHover(user);
    });
  }

  var templateCache = {};

  function loadFile(name, callback) {
    var tmpl = templateCache[name];
    if(tmpl) {
      callback(tmpl);
    }
    else {
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
      var tb = Mustache.render(
          contents,
          params,
          partials
      );
      callback(tb);
    });
  }

  function summaryText(numberOfFriends, is_kept) {
    var summary = "";
    if(is_kept) {
      summary = "You "
      if(numberOfFriends>0) {
        summary += "and "
        if(numberOfFriends == 1) summary += "another friend kept this."
        else summary += numberOfFriends + " of your friends kept this."
      }
      else {
        summary += "kept this!"
      }
    }
    else {
      if(numberOfFriends>0) {
        if(numberOfFriends == 1) summary += "One"
        else if(numberOfFriends == 1) summary += "Two"
        else if(numberOfFriends == 1) summary += "Three"
        else if(numberOfFriends == 1) summary += "Four"
        else summary += numberOfFriends
        summary += " of your friends "
        if(numberOfFriends == 1) summary += "kept this."
        else summary += "kept this."
      }
      else {
        summary += "To quickly find this page later..."
      }
    }
    return summary;
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

      $(element).mouseover(function () {
          clearTimeout(timeout);
          show();
      }).mouseout(hide);

      $(friendTooltip).mouseover(function () {
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

    $.get("http://" + config.server + "/users/keepurl?url=" + encodeURIComponent(document.location.href) + "&externalId=" + userExternalId,
      null,
      function(friends) {

        var tmpl = {
          "logo": logo,
          "arrow": arrow,
          "profilepic": facebookImageLink,
          "name": user.name,
          "is_kept": user.is_kept
        }

        log("got "+friends.length+" result from /users/keepUrl");
        log(friends);

        if (friends.length>0) {
          tmpl.socialConnections = {
            countText: summaryText(friends.length, user.is_kept),
            friends: friends
          }
        }
        console.log(tmpl);

        renderTemplate('kept_hover.html', tmpl, function(template) {
          drawKeepItHover(user, friends, template);
        });

      }
    );
  }


  function drawKeepItHover(user, friends, renderedTemplate) {
    if($('.kifi_hover').length > 0) {
      // nevermind!
      log("No need to inject, it's already here!");
      return;
    }

    // Inject the slider!
    $('body').prepend(renderedTemplate);

    $('.social_friend').each(function(i,e) {
      socialTooltip(friends[i],e);
    });

    // Binders

    $(".kifi_hover").draggable({ cursor: "move", axis: "y", distance: 20, handle: "div.kifihdr", containment: "body", scroll: false});

    $('.kificlose').click(function() {
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

    $('.comments_label').click(function() {
      showComments(user, ($('.kifi_comment_wrapper:visible').length == 0), "public");
    });

    $('.messages_label').click(function() {
      showComments(user, ($('.kifi_comment_wrapper:visible').length == 0), "message");
    });

    showComments(user,false, "public"); // prefetch comments, do not show.

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

  function showComments(user, openComments, type) {
    if($('.kifi_comment_wrapper:visible').length > 0 && !openComments) {
      $('.kifi_comment_wrapper').slideUp(600,'easeInOutBack');
      return;
    }
    var userExternalId = user.keepit_external_id;
    $.get("http://" + config.server + "/comments/" + type + "?url=" + encodeURIComponent(document.location.href) + "&externalId=" + userExternalId,
      null,
      function(comments) {
        renderComments(user, comments, type || "public", function() {
          if(openComments) {
            repositionScroll(true);
            $('.kifi_comment_wrapper').slideDown(600,'easeInOutBack', function () {
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
        var date = (new Date(render(text))).toISOString();
        return date;
      }
      catch(e) {
        return "";
      }
    }
  }

  function renderComments(user, comments, type, onComplete) {
    console.log("Drawing comments!")
    comments = comments || {};
    comments["public"] = comments["public"] || [];
    comments["message"] = comments["message"] || [];
    //comments["private"] = comments["private"] || []; // Removed, not for MVP

    var visibleComments = comments[type] || [];

    $('.comments_label').text(comments["public"].length + " Comments");
    
    var params = {
      kifiuser: {
        "firstName": user.name,
        "lastName": "",
        "avatar": "https://graph.facebook.com/" + user.facebook_id + "/picture?type=square"
      },
      formatComments: commentTextFormatter,
      formatDate: commentDateFormatter,
      comments: visibleComments
    }

    loadFile("templates/comments/reply.html", function(reply) {
    loadFile("templates/comments/hearts.html", function(hearts) {
    loadFile("templates/comments/comments_list.html", function(comment_list) {
    loadFile("templates/comments/comment.html", function(comment) {
    loadFile("templates/comments/reply_list.html", function(reply_list) {
    loadFile("templates/comments/message.html", function(message) {
    loadFile("templates/comments/message_list.html", function(message_list) {
    loadFile("templates/comments/comment_post.html", function(comment_post) {
    loadFile("templates/comments/message_post.html", function(message_post) {

      var partials = {
        "comment_body_view": comment_list,
        "hearts": hearts,
        "reply": reply,
        "comment": comment,
        "reply_list": reply_list,
        "comment_post_view": comment_post
      };

      if(type == 'message') {
        partials.comment = message;
        partials.comment_body_view = message_list;
        partials.comment_post_view = message_post;
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
    });
    });

  }

  function drawCommentView(renderedTemplate, user, type, partials) {
    //console.log(renderedTemplate);
    repositionScroll(false);
    $('.kifi_comment_wrapper').html(renderedTemplate);
    repositionScroll(false);

    createMainBinders(type, user);
    createReplyBinders(type, user);

  }

  function createMainBinders(type, user) {
    $("abbr.timeago").timeago();
    /*
    // Not in MVP
    $(".comment_post_view").on('click','.comment_box .crosshair', function() {
      console.log("Hit");
      return false;
    });
    */

    // Main comment textarea 
    $('.comment_post_view').on('focus','#main-comment-textarea',function() {
      //$('.crosshair').slideDown(150); // Not in mvp
      $('.submit-comment').slideDown(150);
      $('.comment_body_view').animate({
        'max-height': '-=45'
      },150,'easeQuickSnapBounce');
      $('.comment-box').animate({
        'height': '85'
      },150,'easeQuickSnapBounce');
      $(".kififtr").animate({
        'margin-top': '-10'
      });
    });
    $('.comment_post_view').on('blur','#main-comment-textarea',function() {
      $('.comment_body_view').animate({
        'max-height': '+=45'
      },150,'easeQuickSnapBounce');
      $('.comment-box').animate({
        'height': '40'
      },150,'easeQuickSnapBounce');

      //$('.crosshair').slideUp(20);
      //$('.submit-comment').slideUp(20);
    });

    // Submit handlers
    $('.comment_post_view').on('submit','.comment_form', function(e) {
      e.preventDefault();
      //debugger;
      submitComment($('#main-comment-textarea').val(), type, user, null, function(newComment) {
        $('#main-comment-textarea').val("");

        console.log("new thread", newComment);
        // Clean up CSS
        $(".kififtr").animate({
          'margin-top': '0'
        },100);
        $('.submit-comment').slideUp(100, function() {
          // Done cleaning up CSS. Redraw.
          //renderComments(user, comments, type);
          var params = newComment;
          params["formatComments"] = commentTextFormatter;
          params["formatDate"] = commentDateFormatter;

          renderTemplate("templates/comments/comment.html", params, function(renderedComment) {
            //drawCommentView(renderedTemplate, user, type, partials);
            $('.comment_body_view').append(renderedComment).find("abbr.timeago").timeago();
            repositionScroll(false);
          });
        });
      });
      return false;
    });
  }

  function createTimeout(id, callback, timeout) {
    timeoutCollection[id] = setTimeout(function() {
        log("Executing timeout: " + id);
        callback();
        createTimeout(id, callback,timeout);
      }, timeout);
  }

  function destroyTimeout(id) {
    if(timeoutCollection[id]) {
      clearTimeout(timeoutCollection[id]);
      timeoutCollection[id] = undefined;
      log("Destroyed timeout: " + id);
    }
  }

  function destroyAllTimeouts() {
    for (var id in timeoutCollection) {
      destroyTimeout(id);
    }
  }

  function createReplyBinders(type, user) {
    $('.comment_body_view').on('click', '.replies', function() {
      var link = $(this);
      var comment = link.parents('.comment-wrapper');
      var list = comment.children('.comment-replies');
      var parent = comment.data("externalid");
      if(list.is(":visible")) { // closing list
        $(list).slideUp(200);
        link.children('.reply-arrow').html('');
        destroyTimeout(parent);
      }
      else { // opening list
        link.children('.reply-arrow').html('&uarr;');
        var scrollTo = $('.comment_body_view').scrollTop() + comment.position().top - 20;
        $('.comment_body_view').animate({
          scrollTop: scrollTo
        });
        refreshReplies(comment, true);
        createTimeout(parent, function() {
          refreshReplies(comment, true);
        }, TIMEOUTS.REPLY_REFRESH);
      }
    });

    $('.comment_body_view').on('focus', '.reply-comment-textarea', function() {
      $(this).animate({
        'height': '+=20'
      },200,'easeQuickSnapBounce');
    });
    $('.comment_body_view').on('blur', '.reply-comment-textarea', function() {
      $(this).animate({
        'height': '-=20'
      },100,'easeQuickSnapBounce');
    });
    $('.comment_body_view').on('keyup', '.reply-comment-textarea', function(e) {
      if(e.keyCode === 13 && !e.ctrlKey) {
        $(this).parents('form').first().submit();
        return false;
      }
      return true;
    });

    // Reply submit handler
    $('.comment_body_view').on('submit', '.reply-comment form', function() {
      var replyTextarea = $(this).find('textarea');
      var comment = replyTextarea.parents('.comment-wrapper');

      var text = replyTextarea.val().trim();
      replyTextarea.val("");
      var parent = comment.data("externalid");
      replyTextarea.blur();
      submitComment(text, type, user, parent, function(newReply) {
        console.log("server response: ",newReply,user);
        refreshReplies(comment, false);
      });
      
      console.log(this,"submitted!",text,"to parent", parent);
      return false;
    });
  }

  function refreshReplies(comment, openList) {
    var parent = comment.data("externalid");
    var list = comment.children('.comment-replies');

    $.get("http://" + config.server + "/comments/reply?commentId=" + parent,
      null,
      function(replies) {
        var params = {
          replies: replies,
          formatComments: commentTextFormatter,
          formatDate: commentDateFormatter
        };
        renderTemplate("templates/comments/reply_list.html", params, function(renderedReplyList) {
          list.find('.existing-replies').html(renderedReplyList).find("abbr.timeago").timeago();
          if(openList)
            list.slideDown(300,'easeQuickSnapBounce');
        });
      }
    );
  }

  function submitComment(text, type, user, parent, callback) {
    /* Because we're using very simple templating now, re-rendering has to be done carefully.
     */
    var request = {
      "type": "post_comment",
      "url": document.location.href,
      "text": text,
      "permissions": type,
      "parent": parent
    };
    chrome.extension.sendRequest(request, function(response) {
      var newComment = {
        "createdAt": (new Date()),
        "text": request.text,
        "user": {
          "externalId": user.keepit_external_id,
          "firstName": user.name,
          "lastName": "",
          "facebookId": user.facebook_id
        },
        "permissions": type,
        "replyCount": 0,
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

    var commentBodyView = $(".comment_body_view")[0];
    if(commentBodyView)
      commentBodyView.scrollTop = 99999;
  }

  function resizeCommentBodyView(resizeQuickly) {
    var kifiheader = $('.kifihdr');
    if(resizeQuickly === true) {
      $('.comment_body_view').stop().css({'max-height':$(window).height()-280});
    }
    else {
      if(kifiheader.length > 0) {
        var offset = kifiheader.offset().top - 30;
        $('.comment_body_view').stop().animate({'max-height':'+='+offset},20);
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
