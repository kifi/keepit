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
    getTemplate("social_hover.html",{"friend": friend}, function(tmpl) {
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
      showComments(user, true);
    });

    showComments(user,false); // prefetch comments, do not show.

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

  function showComments(user, openComments) {
    // trick to make it appear faster: render empty template, start opening
    /*getTemplate("comments.html", {}, function(renderedTemplate) {
      $('.kifi_comment_wrapper').html(renderedTemplate);
      if(openComments)
        $('.kifi_comment_wrapper').slideToggle(300);
    });*/
    if($('.kifi_comment_wrapper:visible').length > 0) {
      $('.kifi_comment_wrapper').slideUp(600,'easeInOutBack');
      return;
    }
    var userExternalId = user.keepit_external_id;
    $.get("http://" + config.server + "/comments/all?url=" + encodeURIComponent(document.location.href) + "&externalId=" + userExternalId,
      null,
      function(comments) {
        drawComments(user, comments["public"]);
        if(openComments)
          $('.kifi_comment_wrapper').slideDown(600,'easeInOutBack');
      });
  }

  function drawComments(user, comments) {
    if(comments && comments.length)
      $('.comments_label').text(comments.length + " Comments");
    else
      $('.comments_label').text("0 Comments");
    getTemplate("comments.html", {"comments":comments}, function(renderedTemplate) {
      console.log("comment feed",comments);
      $('.kifi_comment_wrapper').html(renderedTemplate);

      var commentList = document.getElementById("comment_list");
      commentList.scrollTop = commentList.scrollHeight;

      $('.kifi_comment_wrapper #comment_form').submit(function() {
        console.log("BAM!!!");
        var text = $('#comment_text').val();
        var request = {
          "type": "post_comment",
          "url": document.location.href,
          "text": text,
          "permissions": "public"
        };
        chrome.extension.sendRequest(request, function() {
          $('#comment_text').val("");
          console.log(user);
          var newComment = {
            "createdAt": "",
            "text": request.text,
            "user": {
              "externalId": user.keepit_external_id,
              "firstName": user.name,
              "lastName": "",
              "facebookId": user.facebook_id
            },
            "permissions": "public"
          }
          comments.push(newComment);
          console.log("new thread", comments)
          drawComments(user, comments);
        });
        return false;
      });
    });
  }

$(document).keydown(function(event) {

    //19 for Mac Command+S
    if (!( String.fromCharCode(event.which).toLowerCase() == 'k' && event.ctrlKey) && !(event.which == 19)) return true;


    getUserInfo(showComments)

    event.preventDefault();
    return false;
});


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

  chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
    config = response;
    console.log("user config",response);
  });


  // Selection libs
/*
  jQuery.fn.getNodePath = function () {
    if (this.length != 1) throw 'Requires one element.';

    var path = [], node = this;
    while (node.length) {
      var realNode = node[0], name = realNode.localName;
      if (!name) break;
      name = name.toLowerCase();
      if(name === "body") break;
      var nodeDescriptor = { "tag": "", "classes": [], "id": "", "append": ""};

      nodeDescriptor["tag"] = name;

      if(realNode.className && realNode.className.indexOf('.') === -1) {
        $.each(realNode.className.split(/ /g),function(i,c) {
          nodeDescriptor["classes"].push(c);
        });
        //name += "." + realNode.className.replace(/ /g,'.');
      }
      if(realNode.id) {
        nodeDescriptor["id"] = realNode.id;
        //name += "#" + realNode.id.replace(/ /g, '#');
      }

      var parent = node.parent();
      var currentSelector = name + (realNode.id ? '#'+realNode.id : '') + (realNode.className ? "."+realNode.className.replace(/ /g,".") : '')
      var siblings = parent.children(currentSelector);
      if (siblings.length > 1) {
        nodeDescriptor["append"] = ':eq(' + siblings.index(node) + ')';
        //name += ':eq(' + siblings.index(realNode) + ')';
      }
      path.push(nodeDescriptor);
      //path = name + (path ? '>' + path : '');
      node = parent;
    }
    return path.reverse();
  };

  var nodePathToSelector = function(nodePath) {
    var path = [];
    $(nodePath).each(function(i,e) {
      var classes="",id=e.id,append=e.append;
      if(e.classes.length > 0) {
        classes = "." + e.classes.join(".");
      }
      if(id.length > 0) {
        id = "#" + id;
      }
      var selector = $.trim(e.tag + classes + id + append);
      if(selector.length > 0)
        path.push(selector);
    });
    return path.join(" ");
  }

  var leastCommonSelector = function(nodePath) {
    var $originalNode = $(nodePathToSelector(nodePath));
    if($originalNode.length === 0) {
      throw 'Node path did not return any elements! Oops?';
    }
    // The approach:
    //  - Starting with the least specific (html), remove whole selectors
    console.log($originalNode);
    for(i=0;i<nodePath.length;i++) {
      if(nodePath[i]["classes"].length > 0 || nodePath[i]["id"] !== '')
        nodePath[i]["tag"] = "";
      else {
        nodePath[i]["tag"] = "";
        nodePath[i]["append"] = "";
      }
      $newNode = $(nodePathToSelector(nodePath));
      if($originalNode.is($newNode)) {
        console.log($newNode.selector,"is the same!!!");
      }
      else {
        console.log($newNode.selector,"is NOT the same!!!");
        break;
      }
    }
  }

  $("body").delegate("*","mouseup",function() {
    var range = $.Range.current();
    var selection = {};
    var start = range.start();
    var end = range.end();
    if(start.container == end.container && start.offset === end.offset ) {
      console.log("click!",start, this, $(this))
      selection['node'] = $(start.container.parentNode).getNodePath();
      selection['parent'] = $(range.parent().parentNode).getNodePath();
      selection['nodeSelector'] = nodePathToSelector(selection['node']);
    }
    else {
      console.log("drag!",$(start.container.parentNode),$(end.container.parentNode))
      selection['startNode'] = $(start.container.parentNode).getNodePath();
      selection['endNode'] = $(end.container.parentNode).getNodePath();
      selection['startOffset'] = start.offset;
      selection['endOffset'] = end.offset;
    }
    console.log(selection);

    if(selection.node) {
      $(nodePathToSelector(selection.parent)).addClass('kifi_selection');
    }
    else if(selection.startNode && selection.endNode) {
      var $start = $(nodePathToSelector(selection.startNode)).addClass('kifi_selection');
      var $end = $(nodePathToSelector(selection.endNode)).addClass('kifi_selection');
      var commonParent = $start.parents().has($end).first().addClass('kifi_selection');
      console.log(commonParent)
    }
    //var path = $(element).getNodePath();

    //console.log(range, element, path, nodePathToSelector(path));
    //leastCommonSelector(path);
    //$(nodePathToSelector(path)).css({"background-color":"#ff0000"})
    return false;
  });
*/
/*
  $(document).ready(function(){
     $("body *").mouseover(function(){
        $(this).addClass('kifi_selection');
        $(this).find("*").addClass('kifi_selection');
        return false;
     });
          
     $('body *').mouseout(function(){
        $(this).removeClass('kifi_selection');
        $(this).find("*").removeClass('kifi_selection');
        return false;
     });
  });
*/


})();
