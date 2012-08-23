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
      "Keepit" + 
      "</div>");
    hover.append(bar);
    var button = $("<div id='keep_action' class='keep_action' type='button'>Keep Bookmark</button>")
    hover.append(button);
    hover.append("<br/><input type='checkbox' id='keepit_private' value='private'>private</input>");

    button.click(function() {
      console.log("bookmarking page " + document.location.href);
      chrome.extension.sendRequest({type: "add_bookmarks", url: document.location.href, title: document.title, private: $("#keepit_private").is(":checked")}, function(response) {
        console.log("bookmark added! -> " + JSON.stringify(response));
        $('.keepit_hover').animate({
            right: '-=207'
          },
        300);
      });
    });
    $("body").append(hover);
    setTimeout(function() {
      $('.keepit_hover').animate({
          right: '+=207'
        },
        300);
    }, 1000);//1 seconds
    //$(".keepit_hover").onClick
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
