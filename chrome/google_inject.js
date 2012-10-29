console.log("[" + new Date().getTime() + "] starting keepit google_inject.js");

(function () { try {
  $ = jQuery.noConflict()

  var lastInjected = null;
  var config = null;

  function log(message) {
    console.log("[" + new Date().getTime() + "] ", message);
  }

  function error(exception, message) {
    debugger;
    var errMessage = exception.message;
    if(message) {
      errMessage = "[" + message + "] " + exception.message;
    }
    console.error(exception);
    console.error(errMessage);
    console.error(exception.stack);
    alert("exception: " + exception.message);
  }

  log("injecting keep it to google search result page");
  
  function updateQuery() { 
    if ($("body").length === 0) {
      log("no body yet...");
      setTimeout(function(){ updateQuery(); }, 10);
      return;
    }
    var queryInput = $("input[name='q']");
    var query = queryInput.val();
    if (!query) {
      log("query is undefined");
      return;
    }
    log("search term: " + query);
    var request = {
      type: "get_keeps", 
      query: queryInput.val()
    };
    chrome.extension.sendRequest(request, function(results) {
      var searchResults = results.searchResults;
      var userInfo = results.userInfo;
      try {
        if (!(searchResults) || searchResults.length == 0) {
          log("No search results!");
          return;
        }
        log("got " + searchResults.length + " keeps:");
        $(searchResults).each(function(i, e){log(e)});
        var old = $('#keepit');
        if (old && old.length > 0) {
          old.slideUp(function(){
            old.remove();
            addResults(userInfo, searchResults);
          });
        } else {
          addResults(userInfo, searchResults);
        }
      } catch (e) {
        error(e);
      }
    });
  }

  chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
    config = response;
  });

  updateQuery();

  $('#main').change(function() {
    if ($('#keepit').length === 0) {
      updateQuery();
    }
  });

  $("input[name='q']").change(function(){
    updateQuery();
  });

  /*******************************************************/

  function addResults(userInfo, searchResults) {
    try {
      log(":: addResults parameters ::");
      log(userInfo);
      log(searchResults);



      var req = new XMLHttpRequest();
      req.open("GET", chrome.extension.getURL('google_inject.html'), true);
      req.onreadystatechange = function() {
        if (req.readyState == 4 && req.status == 200) {
          
          log('Rendering Mustache.js Google template...');

          var results = new Array();

          $(searchResults).each(function(i, result){
            var formattedResult = result;

            formattedResult.displayUrl = formattedResult.bookmark.url;
            if (formattedResult.bookmark.url.length > 75) {
              formattedResult.displayUrl = formattedResult.displayUrl.substring(0, 75) + "..."
            }

            if (config["show_score"] === true) {
              formattedResult.displayScore = "[" + Math.round(result.score*100)/100 + "] ";
            }

            formattedResult.countText = "";

            var numFriends = formattedResult.users.length;

            // Awful decision tree for clean text. Come up with a better way.
            if(formattedResult.isMyBookmark) { // you
              if(numFriends == 0) { // no friends
                if(formattedResult.count > 0) { // others
                  formattedResult.countText = "<b>You</b> and " + count + " others";
                }
                else { // no others
                  formattedResult.countText = "<b>You</b>";
                }
              }
              else { // numFriends > 0
                if(formattedResult.count > 0) { // others
                  formattedResult.countText = "<b>You</b>, " + numFriends + " friends, and " + count + " others";
                }
                else { // no others
                  formattedResult.countText = "<b>You</b> and " + numFriends + " friends";
                }
              }
            }
            else { // not you
              if(numFriends == 0) { // no friends
                if(formattedResult.count > 0) { // others
                  formattedResult.countText =  count + " others";
                }
                else { // no others
                  formattedResult.countText = "No one"; // ???
                }
              }
              else { // numFriends > 0
                if(formattedResult.count > 0) { // others
                  formattedResult.countText = numFriends + " friends, and " + count + " others";
                }
                else { // no others
                  formattedResult.countText = numFriends + " friends";
                }
              }
            }

            results.push(formattedResult);
          });


          var tb = Mustache.to_html(
              req.responseText,
              {"results": results, "userInfo": userInfo}
          );

          // Binders
          log("Preparing to inject!",$('#ires'));

          function injectResults(times) {
            if(times<=0) {
              return;
            }
            if($("#keepit:visible").length == 0) {
              log("Google isn't ready. Trying to injecting again... ("+times+")");
              $('#ires').prepend(tb);
              setTimeout(function() { injectResults(--times) }, 50);
            }
            else {
              log("Done!");
            }
          }

          injectResults(100);

        }
      };
      req.send(null);

    } catch (e) {
      error(e);
    }
  }

  function addActionToSocialBar(socialBar) {
    socialBar.append("<div class='social_bar_action'>Share It</div>");
  }

  function injectDiv(ol, resultCount, callback) {
    if (ol.head !== lastInjected) {
      return;
    }
    //neight needs to be proportional to num of elements with max = 3
    log("result count is " + resultCount + ", expending...");
    ol.slideDown(500, function() {
      log("done expanding. now at " + ol.css("height"));
      callback();
    });
  }

} catch(exception) {
    debugger;
    alert("exception: " + exception.message);
    console.error(exception);
    console.error(exception.stack);
}})();
