console.log("[" + new Date().getTime() + "] starting keepit google_inject.js");

(function () { try {
  $ = jQuery.noConflict()

  var lastInjected = null;
  var config = null;

    var restrictedGoogleInject = [
      "tbm=isch"
    ];

  function log(message) {
    console.log("[" + new Date().getTime() + "] ", message);
  }

  var resultsStore = {};
  var inprogressSearchQuery = '';

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

    if(inprogressSearchQuery !== '') {
      // something else is running it.
      log("Another search is in progress. Ignoring query " + inprogressSearchQuery);

      return;
    }

    if ($("body").length === 0) {
      log("no body yet...");
      setTimeout(function(){ updateQuery(); }, 10);
      return;
    }
    var queryInput = $("input[name='q']");
    var query = queryInput.val();
    if (typeof query === 'undefined' || query == '') {
      if(typeof calledTimes !== 'undefined' && calledTimes <= 10) {
        setTimeout(function(){ updateQuery(++calledTimes); }, 200);
      }
      else if (typeof calledTimes === 'undefined')
        setTimeout(function(){ updateQuery(0); }, 200);
      return;
    }

    if(query === resultsStore.query) {
      log("Nothing new. Disregarding " + query);
      drawResults(0);
      return;
    }

    log("New query! New: " + query + ", old: " + resultsStore.query);

    var request = {
      type: "get_keeps", 
      query: $("input[name='q']").val() // it may have changed since last checked
    };
    inprogressSearchQuery = query;
    chrome.extension.sendRequest(request, function(results) {
      console.log("RESULTS FROM SERVER", results);
      
      inprogressSearchQuery = '';
      if($("input[name='q']").val() !== request.query ) { // query changed
        updateQuery(0);
        return;
      }
      $("#keepit").detach(); // get rid of old results
      resultsStore = {
        "results": results,
        "query": request.query
      };
      if(request.query === '') {
        return;
      }
      window.kifi_resultsStore = resultsStore;
      log("kifi results recieved for " + resultsStore.query);
      log(resultsStore);

      drawResults(0);
    });
  }

  function fetchMoreResults(results) {
    var request = {
      "type": "get_keeps",
      "query": resultsStore.query
    };

    ///search2?term=<term>&externalId=<user external ID>&lastUUID=<uuid>&context=<context string>
    /*chrome.extension.sendRequest(request, function(results) {

    });*/
  }

  function drawResults(times) {
    if(times > 30) {
      return;
    }
    var searchResults = resultsStore.results.searchResults;
    var userInfo = resultsStore.results.userInfo;
    try {
      if (!(searchResults) || searchResults.length == 0) {
        log("No search results!");
        cleanupKifiResults();
        return;
      }

      var old = $('#keepit');
      if (old && old.length > 0) {
        console.log("Old keepit exists.");
        setTimeout(function(){ drawResults(++times); }, 100);
      } else {
        console.log("Drawing results", resultsStore, $("input[name='q']").val());
        addResults();
      }
    } catch (e) {
      error(e);
    }
  }

  chrome.extension.sendRequest({"type": "get_conf"}, function(response) {
    config = response;
  });

  updateQuery();

  /*$('#main').change(function() {
    log("Search results changed! Updating kifi results...");
    updateQuery();
  });*/

  setTimeout(function() {
    $("input[name='q']").parents("form").submit(function(){
      log("Input box changed (submit)! Updating kifi results...");
      updateQuery();
    });
    //updateQuery();
  },500);

  // The only reliable way to detect spelling clicks.
  // For some reason, spelling doesn't fire a blur()
  $(window).bind('hashchange', function() {
    log("URL has changed! Updating kifi results...");
    updateQuery();
    setTimeout(function(){ updateQuery(0); }, 300); // sanity check
  });

  function cleanupKifiResults() {
    var currentQuery = '';
    if(typeof resultsStore.query === 'undefined' || resultsStore.query == '' || resultsStore.results.searchResults.length == 0) {
      $('#keepit').detach();
      if(typeof resultsStore.query !== 'undefined')
        currentQuery = resultsStore.query;
    }
    else {
      currentQuery = resultsStore.query.replace(/\s+/g, '');
    }
    var googleSearch = '';
    var pos = document.title.indexOf(" - Google Search");
    if(pos > 0) {
      googleSearch = document.title.substr(0,pos).replace(/\s+/g, '');
      if(currentQuery !== googleSearch) {
        console.log("Title difference...");
        //updateQuery(0);
      }
    }
  }

  function cleanupCron() {
    cleanupKifiResults();
    setTimeout(cleanupCron, 1000);
  }

  cleanupCron();

  


  /*******************************************************/

  function addResults() {
    try {
      log("addResults parameters:");
      console.log(resultsStore);
      console.log(resultsStore.results.userInfo);
      console.log(resultsStore.results.searchResults);
      var userInfo = resultsStore.results.userInfo;
      var searchResults = resultsStore.results.searchResults;

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
            $.each(resultsStore.query.split(" "), function(i, term) { displayUrl = boldSearchTerms(displayUrl,term,false); });
            formattedResult.displayUrl = displayUrl;

            var title = formattedResult.bookmark.title;
            $.each(resultsStore.query.split(" "), function(i, term) { title = boldSearchTerms(title,term,true); });
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
            if(resultsStore.query === '' || 
              typeof resultsStore.results === 'undefined' || 
              typeof resultsStore.results.searchResults === 'undefined' || 
              resultsStore.results.searchResults.length == 0) {
              // Catch bogus injections
              log("Injection not relevant. Stopping.");
              return;
            }
            else if($("#keepit:visible").length == 0) {
              console.log("Google isn't ready. Trying to injecting again...");
              if($('#ires').length > 0)
                $('#ires').before(tb);
              setTimeout(function() { injectResults(--times) }, 30);
            }
            else {
              setTimeout(function() { injectResults(times > 10 ? 10 : --times) }, 1000/times);
            }
          }
          if(resultsStore.query !== $("input[name='q']").val()) { // the query changed!
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
