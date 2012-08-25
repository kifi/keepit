console.log("injecting keep it hover div");
(function() {
  $ = jQuery.noConflict()
  var env = "undefined";
  function async(fun) {
    setTimeout(fun, 1000);
  }
  
  function showBookmarkHover(user) {
    var existingElements = $('#keepit_hover').length;
    if (existingElements > 0) {
      console.warn("hover is already injected. There are " + existingElements + " existing elements")
      return;
    }
    var hover = $("<div id='keepit_hover' class='keepit_hover'></div>");
    var bar = $("<div class='keep_hover_bar'>" + 
      "<a data-hover='tooltip' class='name_tooltip_link' href='http://www.facebook.com/" + user.facebook_id + "' target='_blank'><img src='https://graph.facebook.com/" + user.facebook_id + "/picture?type=square' width='24' height='24' alt=''></a>" +
      "<span class='keep_hover_bar_title'>Keepit</span>" + 
      "</div>");
    hover.append(bar);
    var buttons = $("<div id='keep_hover_buttons' class='keep_hover_buttons'></div>")
    var button = $("<div id='keep_action' class='keep_action' type='button'>Keep Bookmark</button>")
    buttons.append(button);
    buttons.append("<input type='checkbox' id='keepit_private' class='keepit_private' value='private'> Private</input>");
    hover.append(buttons);
    var close = $("<div class='hover_close'>Close</div>")
    hover.append(close);

    button.click(function() {
      console.log("bookmarking page " + document.location.href);
      chrome.extension.sendRequest({type: "add_bookmarks", url: document.location.href, title: document.title, private: $("#keepit_private").is(":checked")}, function(response) {
        console.log("bookmark added! -> " + JSON.stringify(response));
        slideOut();
      });
    });

    close.click(function() {
      slideOut();
    });

    $("body").append(hover);
    setTimeout(function() {
      slideIn();
    }, 1000);//1 seconds
  }

  function slideOut() {
    $('.keepit_hover').animate({
        right: '-=230'
      },
      300);
  }

  function slideIn() {
    $('.keepit_hover').animate({
        right: '+=230'
      },
      300);
  }

  function getUserInfo(callback) {
    chrome.extension.sendRequest({"type": "get_user_info"}, function(userInfo) {
      callback(userInfo);
    });
  }

    chrome.extension.sendRequest({"type": "get_env"}, function(response) {
      env = response.env;
      async(getUserInfo(showBookmarkHover));

    });
})();
