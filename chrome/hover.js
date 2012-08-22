console.log("injecting keep it hover div");
(function() {
  $ = jQuery.noConflict()
  var env = "undefined";
  function async(fun) {
    setTimeout(fun, 1000);
  }
  
  function showBookmarkHover(userInfo) {
    var existingElements = $('#keepit_hover').length;
    if (existingElements > 0) {
      log.warn("hover is already injected. There are " + existingElements + " existing elements")
      return;
    }
    var hover = $("<div id='keepit_hover'></div>");
    var button = $("<button id='keep_action' type='button'>Keep Bookmark</button>")
    button.click(function() {
      console.log("bookmarking page " + document.location.href);
      chrome.extension.sendRequest({type: "add_bookmarks", url: document.location.href, title: document.title, private: $("#keepit_private").is(":checked")}, function(response) {
        console.log("bookmark added! -> " + JSON.stringify(response));
        hover.empty();
        hover.append("<center>Share with facebook!<br/><br/>");
        var img = "<img src='" + userInfo["picture"] + "'/><br/>";
        hover.append(img);
        hover.append('<br/><br/><a target="_top" href="https://www.facebook.com/dialog/oauth/?client_id=157864447681740&redirect_uri=https%3A%2F%2Fwww.keepit.com&scope=email">Connect with Facebook!</a></center>');
      });
    });
    hover.append(button);
    hover.append("<br/><input type='checkbox' id='keepit_private' value='private'>private</input>")
    hover.append("<br/>env: "+env)
    $("body").append(hover);
    setTimeout(function() {
      //$(window).scroll(function () {
        //if( $(this).scrollTop() > 10 ) {
          $('#keepit_hover').animate({
              right: '+=150'
            },
            300);
        //}
      //});
    }, 1000);//1 seconds
    $("#keepit_hover").onClick
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
