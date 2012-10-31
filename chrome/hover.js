console.log("injecting keep it hover div");

(function() {
  $ = jQuery.noConflict()
  var config;
  var hover;

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

    log("checking location: " + document.location.href)
    chrome.extension.sendRequest({
      "type": "is_already_kept",
      "location": document.location.href
    }, function(is_kept) {
      user.is_kept = is_kept;
      showKeepItHover(user);
    });
  }

  function getTemplate(name, params, callback) {
    var req = new XMLHttpRequest();
    req.open("GET", chrome.extension.getURL(name), true);
    req.onreadystatechange = function() {
        if (req.readyState == 4 && req.status == 200) {
            var tb = Mustache.to_html(
                req.responseText,
                params
            );
            callback(tb);
        }
    };
    req.send(null);
  }

  function summaryText(numberOfFriends) {
    var summary = "";
    if (numberOfFriends>0) {
      if (numberOfFriends == 1) {
        summary="one of your friends";
      } else {
        summary = numberOfFriends+" other friends";
      }
    }
    return summary;
  }

  function socialTooltip(friend, element) {
    var timeout;
    var timein;

    var friendTooltip = $('.friend_tooltip').first().clone().appendTo('.friendlist').text(friend.firstName);;

    function hide() {
        timeout = setTimeout(function () {
            $(friendTooltip).hide();
        }, 500);
        clearTimeout(timein);
    };

    function show() {
      timein = setTimeout(function() {
        $(friendTooltip).stop().show();
      }, 500)
    }

    $(element).mouseover(function () {
        clearTimeout(timeout);
        show();
    }).mouseout(hide);

    $(friendTooltip).mouseover(function () {
        clearTimeout(timeout);
    }).mouseout(hide);
  }


  function showKeepItHover(user) {
    var logo = chrome.extension.getURL('kifilogo.png');
    var arrow = chrome.extension.getURL('arrow.png');
    var facebookProfileLink = "http://www.facebook.com/" + user.facebook_id;
    var facebookImageLink = "https://graph.facebook.com/" + user.facebook_id + "/picture?type=square";

    $.get("http://" + config.server + "/users/keepurl?url=" + encodeURIComponent(document.location.href),
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
            countText: summaryText(friends.length),
            friends: friends
          }
        }

        console.log(tmpl);

        getTemplate('kept_hover.html', tmpl, function(template) {
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

    slideIn();
  }

  function keptItslideOut() {
    var position = $('.kifi_hover').position().top;
    $('.kifi_hover').animate({
        bottom: '+=' + position,
        opacity: 0
      },
      600,
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


  function chatWith(user) {
    console.log("Im here");
    
    var chatBox = $("<div class='keepit_chat_box'>  </div>");
    $('#keepit_hover').append(chatBox);

    var img = $("<img class='keep_face' src='https://graph.facebook.com/" + user.facebookId + "/picture?type=square' width='24' height='24' alt=''>");
    chatBox.append(img);
    var message = $("<input type='text' class='keepit_text_message'/>");
    var button = $("<button class='keepit_chat_button'>send</button>");
    chatBox.append(message);
    chatBox.append(button);
    var closeForm= $("<a href='#' class='keepit_close_form'>X</a>");
    chatBox.append(closeForm);
    
    closeForm.click(function() {
      chatBox.remove();
    });
    button.click(function(){
      console.log("going to send chat: " + document.location.href);
      var xhr = new XMLHttpRequest();
      xhr.onreadystatechange = function() {
        if (xhr.readyState == 4) {
          console.log("[chat response] xhr response:");
          var result = $.parseJSON(xhr.response);
          console.log(result);
        }
      }
      xhr.open("POST", 'http://' + config["server"] + '/chat/' + user.externalId, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.send(JSON.stringify(  {"url": document.location.href, "message":message.val() } ));
      console.log("sent");
    });
  }

})();
