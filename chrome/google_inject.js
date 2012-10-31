console.log("[" + new Date().getTime() + "] starting keepit google_inject.js");

(function () { try {
  $ = jQuery.noConflict()

  var lastInjected = null;
  var config = null;

    var restrictedGoogleInject = [
      "tbm=isch"
    ]

  function log(message) {
    console.log("[" + new Date().getTime() + "] ", message);
  }

  var searchQuery = '';
  var cachedResults = {};

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
  
  function updateQuery(calledTimes) {
    log("updating query...");


    var restrictedElements = $.grep(restrictedGoogleInject, function(e, i){
      return document.location.toString().indexOf(e) >= 0;
    });
    if (restrictedElements.length > 0) {
      log("restricted hover page: " + restrictedElements);
      return;
    }

    if ($("body").length === 0) {
      log("no body yet...");
      setTimeout(function(){ updateQuery(); }, 10);
      return;
    }
    var queryInput = $("input[name='q']");
    var query = queryInput.val();
    if (typeof query === 'undefined') {
      log("query is undefined");
      if(typeof calledTimes !== 'undefined' && calledTimes > 3)
        setTimeout(function(){ updateQuery(++calledTimes); }, 200);
      return;
    }

    if(query === searchQuery) {
      log("Nothing new. Disregarding " + query);
      drawResults(cachedResults, 0);
      return;
    }

    log("New query! New: " + query + ", old: " + searchQuery);

    var request = {
      type: "get_keeps", 
      query: $("input[name='q']").val() // it may have changed since last checked
    };
    chrome.extension.sendRequest(request, function(results) {
      if($("input[name='q']").val() !== query ) { // query changed
        updateQuery(0);
        return;
      }
      searchQuery = query;
      cachedResults = results;
      log("kifi results recieved for " + searchQuery);
      log(results);

      drawResults(results, 0);
    });
  }

  function drawResults(results, times) {
    if(times > 30) {
      return;
    }
    var searchResults = results.searchResults;
    var userInfo = results.userInfo;
    try {
      if (!(searchResults) || searchResults.length == 0) {
        log("No search results!");
        return;
      }

      var old = $('#keepit');
      if (old && old.length > 0) {
        console.log("Old keepit exists.");
        setTimeout(function(){ drawResults(results, ++times); }, 50);
      } else {
        log("Drawing results");
        addResults(userInfo, searchResults, searchQuery);
      }
    } catch (e) {
      error(e);
    }
  }

  chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
    config = response;
  });

  updateQuery();

  $('#main').change(function() {
    log("Search results changed! Updating kifi results...");
    updateQuery();
  });


  $("input[name='q']").blur(function(){
    log("Input box changed (blur)! Updating kifi results...");
    updateQuery();
  });

  // The only reliable way to detect spelling clicks.
  // For some reason, spelling doesn't fire a blur()
  $(window).bind('hashchange', function() {
    updateQuery();
  });


  /*******************************************************/

  function addResults(userInfo, searchResults, query) {
    try {
      log("addResults parameters:");
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
            if (formattedResult.bookmark.url.length > 60) {
              formattedResult.displayUrl = formattedResult.displayUrl.substring(0, 75) + "..."
            }

            var displayUrl = formattedResult.displayUrl;
            $.each(query.split(" "), function(i, term) { displayUrl = boldSearchTerms(displayUrl,term,false); });
            formattedResult.displayUrl = displayUrl;

            var title = formattedResult.bookmark.title;
            $.each(query.split(" "), function(i, term) { title = boldSearchTerms(title,term,true); });
            formattedResult.bookmark.title = title;

            if (config["show_score"] === true) {
              formattedResult.displayScore = "[" + Math.round(result.score*100)/100 + "] ";
            }

            formattedResult.countText = "";

            var numFriends = formattedResult.users.length;

            // Awful decision tree for clean text. Come up with a better way.
            if(formattedResult.isMyBookmark) { // you
              if(numFriends == 0) { // no friends
                if(formattedResult.count > 0) { // others
                  formattedResult.countText = "You and " + formattedResult.count + " others";
                }
                else { // no others
                  formattedResult.countText = "You";
                }
              }
              else { // numFriends > 0
                if(formattedResult.count > 0) { // others
                  formattedResult.countText = "You, <b>" + numFriends + " friends</b>, and " + formattedResult.count + " others";
                }
                else { // no others
                  formattedResult.countText = "You and <b>" + numFriends + " friends</b>";
                }
              }
            }
            else { // not you
              if(numFriends == 0) { // no friends
                if(formattedResult.count > 0) { // others
                  formattedResult.countText =  formattedResult.count + " others";
                }
                else { // no others
                  formattedResult.countText = "No one"; // ???
                }
              }
              else { // numFriends > 0
                if(formattedResult.count > 0) { // others
                  formattedResult.countText = "<b>" + numFriends + " friends</b>, and " + formattedResult.count + " others";
                }
                else { // no others
                  formattedResult.countText = "<b>" + numFriends + " friends</b>";
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
              $('#ires').before(tb);
              setTimeout(function() { injectResults(--times) }, 30);
            }
            else {
              setTimeout(function() { injectResults(times > 10 ? 10 : --times) }, 1000/times);
            }
          }
          if(searchQuery !== $("input[name='q']").val()) { // the query changed!
            updateQuery(0);
          }
          else {
            injectResults(100);
          }
          //updateQuery(0);

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

  function boldSearchTerms(input, needle, useSpaces) {
    if(useSpaces === true)
      return input.replace(new RegExp('(^|\\s)(' + needle + ')(\\s|$)','ig'), '$1<b>$2</b>$3');
    else
      return input.replace(new RegExp('(^|\\.?)(' + needle + ')(\\.?|$)','ig'), '$1<b>$2</b>$3');
  }

} catch(exception) {
    debugger;
    alert("exception: " + exception.message);
    console.error(exception);
    console.error(exception.stack);
}})();
