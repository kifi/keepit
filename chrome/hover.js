console.log("injecting keep it hover div");

(function() {
  $ = jQuery.noConflict()
  var config;
  var hover;

  function showBookmarkHover(user) {


    var existingElements = $('.kifi_hover').length;
    if (existingElements > 0) {
      console.warn("hover is already injected. There are " + existingElements + " existing elements")
      return;
    }

    var req = new XMLHttpRequest();
    req.open("GET", chrome.extension.getURL('hover.html'), true);
    req.onreadystatechange = function() {
        if (req.readyState == 4 && req.status == 200) {
            //var image = chrome.extension.getURL('logo.jpg');
            var logo = chrome.extension.getURL('kifilogo.png');
            var arrow = chrome.extension.getURL('arrow.png');
            var facebookProfileLink = "http://www.facebook.com/" + user.facebook_id;
            var facebookImageLink = "https://graph.facebook.com/" + user.facebook_id + "/picture?type=square";

            console.log('Rendering Mustache.js hover template...');

            $.get("http://" + config.server + "/users/keepurl?url=" + encodeURIComponent(document.location.href),
                null,
                function(users) {

                  var tmpl = {
                    "logo": logo,
                    "arrow": arrow,
                    "profilepic": facebookImageLink
                  }

                  console.log("got "+users.length+" result from /users/keepUrl");
                  console.log(users);

                  if (users.length>0) {
                    var summary = "";
                    if (users.length == 1) {
                      summary="One of your friends";
                    } else {
                      summary = users.length+" other friends";
                    }
                    tmpl.socialConnections = {
                      countText: summary,
                      friends: users
                    }
                  }

                  var tb = Mustache.to_html(
                      req.responseText,
                      tmpl
                  );

                  // Binders
                  $('body').prepend(tb);
                  $('.close').click(function() {
                    slideOut();
                  });
                  $('.profilepic').click(function() { location=facebookProfileLink; });

                  $('.keepitbtn').click(function() {
                    console.log("bookmarking page: " + document.location.href);
                    var request = {
                      type: "add_bookmarks", 
                      url: document.location.href, 
                      title: document.title, 
                      private: $("#keepit_private").is(":checked")
                    }
                    chrome.extension.sendRequest(request, function(response) {
                      console.log("bookmark added! -> " + JSON.stringify(response));
                      slideOut();
                   });
                  });

                  $('.dropdownbtn').click(function() {
                    $('.moreinnerbox').slideToggle(150);
                  });
                },
                "json"
            );
        }
    };
    req.send(null);
    

    setTimeout(function() {
      slideIn();
    }, 500);//1 seconds // CHANGE ME
  }

  function slideOut() {
    var position = $('.kifi_hover').position().top;
    console.log(position);
    $('.kifi_hover').animate({
        bottom: '+=' + position,
        opacity: 0,
        right: '-=100'
      },
      300, function() {
        $('.kifi_hover').detach();
      });
  }

  function slideIn() {
    $("body").after(hover);
    $('.kifi_hover').animate({
        right: '+=330',
        bottom: '+=75',
        opacity: 1
      },
      300);

  }

  function getUserInfo(callback) {
    chrome.extension.sendRequest({"type": "get_user_info"}, function(userInfo) {
      callback(userInfo);
    });
  }

  function getUsersKeptThisUrl() {
    $.get("http://" + config.server + "/users/keepurl?url=" + encodeURIComponent(document.location.href),
        null,
        function(users) {         
          console.log("got "+users.length+" result from /users/keepUrl");
          console.log(users);          
          if (users.length==0) return;
          var summary="";
          if (users.length == 1) {
            summary="one of your friends";
          } else {
            summary = users.length+" other friends";
          }
          $("#keep_summary").html("<span class='keep_summary_friends'>"+summary+"</span></br>choose to keep this bookmark");
          var faces = $("#keep_face");
          $(users).each(function(index, user){
            if(user.facebookId) {
              var img =  $("<a href='#'><img class='keep_face' src='https://graph.facebook.com/" + user.facebookId + "/picture?type=square'></a>");
              faces.append(img);
              img.click(function() {
                chatWith(user);
              });
            } else { //facebook id is missing for some reason!
              var img =  $("<img class='keep_face' src='/assets/images/missing_user.jpg'");
              faces.append(img);
            }
          });
        },
        "json"
    )//.error(callback);
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
      xhr.open("POST", 'http://' + config.server + '/chat/' + user.externalId, true);
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.send(JSON.stringify(  {"url": document.location.href, "message":message.val() } ));
      console.log("sent");
    });
  }

  chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
    config = response;
    console.log("user config",response);
    getUserInfo(showBookmarkHover);

  });

})();
