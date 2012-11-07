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

  var resultsStore = {
    "showDefault": 5
  };
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
    var t1 = new Date().getTime();
    chrome.extension.sendRequest(request, function(results) {
      console.log(new Date().getTime() - t1);
      console.log("RESULTS FROM SERVER", results);

      inprogressSearchQuery = '';
      if($("input[name='q']").val() !== request.query || request.query !== results.searchResults.query) { // query changed
        log("Query changed. Re-loading...");
        updateQuery(0);
        return;
      }
      $("#keepit").detach(); // get rid of old results
      resultsStore = {
        "lastRemoteResults": results.searchResults,
        "results": results.searchResults.hits,
        "query": request.query,
        "userInfo": results.userInfo,
        "currentlyShowing": 0,
        "show": results.userConfig.max_res,
        "mayShowMore": results.searchResults.mayHaveMore,
        "userConfig": results.userConfig,
        "showDefault": results.userConfig.max_res
      };
      if(request.query === '') {
        return;
      }

      log("kifi results recieved for " + resultsStore.query);
      log(resultsStore);

      drawResults(0);

      //fetchMoreResults();
    });
  }

  function fetchMoreResults() {
    if(resultsStore.mayShowMore === true) {
      var request = {
        "type": "get_keeps",
        "query": resultsStore.query,
        "lastUUID": resultsStore.lastRemoteResults.uuid,
        "context": resultsStore.lastRemoteResults.context
      };

      ///search2?term=<term>&externalId=<user external ID>&lastUUID=<uuid>&context=<context string>
      chrome.extension.sendRequest(request, function(results) {
        console.log("fetched more!",results);
        resultsStore.lastRemoteResults = results.searchResults;
        resultsStore.results = resultsStore.results.concat(results.searchResults.hits);
        console.log(resultsStore.results);
        resultsStore.mayShowMore = results.searchResults.mayHaveMore;
        //drawResults(0);
      });
    }
    else {
      $('.kifi_more').hide();
    }

  }

  function showMoreResults() {
    var numberMore = resultsStore.userConfig.max_res;
    resultsStore.show = resultsStore.results.length >= resultsStore.show + numberMore ? resultsStore.show + numberMore : resultsStore.results.length;
    console.log("Showing more results",numberMore,resultsStore.results.length, resultsStore.currentlyShowing, resultsStore.show);
    drawResults(0);
    if(resultsStore.results.length < resultsStore.show + numberMore)
      fetchMoreResults();
  }

  function drawResults(times) {
    if(times > 30) {
      return;
    }
    var searchResults = resultsStore.results;
    var userInfo = resultsStore.userInfo;
    try {
      if (!(searchResults) || searchResults.length == 0) {
        log("No search results!");
        cleanupKifiResults();
        return;
      }

      var old = $('#keepit');
      if ((old && old.length > 0) && (resultsStore.currentlyShowing === resultsStore.show)) {
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
    resultsStore.show = resultsStore.showDefault;
    updateQuery();
    setTimeout(function(){ updateQuery(0); }, 300); // sanity check
  });

  function cleanupKifiResults() {
    var currentQuery = '';
    if(typeof resultsStore.query === 'undefined' || resultsStore.query == '' || resultsStore.results.length == 0) {
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

  var urlAutoFormatters = [
    {
      "match": "docs\\.google\\.com",
      "template": "A file in Google Docs",
      "icon": "gdocs.gif"
    }, {
      "match": "drive\\.google\\.com",
      "template": "A folder in your Google Drive",
      "icon": "gdrive.png"
    }, {
      "match": "dropbox\\.com/home",
      "template": "A folder in your Dropbox",
      "icon": "dropbox.png"
    }, {
      "match": "dl-web\\.dropbox\\.com",
      "template": "A file from Dropbox",
      "icon": "dropbox.png"
    }, {
      "match": "dropbox\\.com/sh/",
      "template": "A shared file on Dropbox",
      "icon": "dropbox.png"
    }, {
      "match": "mail\\.google\\.com/mail/.*/[\\w]{0,}",
      "template": "An email on Gmail",
      "icon": "gmail.png"
    }, {
      "match": "facebook\\.com/messages/[\\w\\-\\.]{4,}",
      "template": "A conversation on Facebook",
      "icon": "facebook.png"
    }
  ];

  function displayURLFormatter(url) {
    var prefix = "^https?://w{0,3}\\.?";
    for(i=0;i<urlAutoFormatters.length;i++) {
      var regex = new RegExp(prefix + urlAutoFormatters[i].match, "ig");
      if(regex.test(url) === true) {
        var result = ""
        if(typeof urlAutoFormatters[i].icon !== 'undefined') {
          var icon = chrome.extension.getURL('icons/'+urlAutoFormatters[i].icon);
          result += "<span class=\"formatted_site\" style=\"background: url(" + icon + ") no-repeat;background-size: 15px;\"></span>"
        }
        result += urlAutoFormatters[i].template;
        return result;
      }
    }
    var body = url.split(/^https?:\/\//);
    if(body.length >= 1) {
      url = body[body.length-1];
    }
    if (url.length > 60) {
      url = url.substring(0, 60) + "...";
    }
    $.each(resultsStore.query.split(" "), function(i, term) { url = boldSearchTerms(url,term,false); });
    return url;
  }

  function addResults() {
    try {
      log("addResults parameters:");
      console.log(resultsStore);
      var userInfo = resultsStore.userInfo;
      var searchResults = resultsStore.results.slice(0,resultsStore.show);
      resultsStore.currentlyShowing = resultsStore.show;

      var req = new XMLHttpRequest();
      req.open("GET", chrome.extension.getURL('google_inject.html'), true);
      req.onreadystatechange = function() {
        if (req.readyState == 4 && req.status == 200) {
          
          log('Rendering Mustache.js Google template...');
          var results = new Array();

          $(searchResults).each(function(i, result){
            var formattedResult = result;

            formattedResult.displayUrl = displayURLFormatter(formattedResult.bookmark.url);
            console.log(formattedResult.bookmark.url, formattedResult.displayUrl);

            var title = formattedResult.bookmark.title;
            $.each(resultsStore.query.split(/[\s\W]/), function(i, term) { title = boldSearchTerms(title,term,true); });
            formattedResult.bookmark.title = title;

            if (config["show_score"] === true) {
              formattedResult.displayScore = "[" + Math.round(result.score*100)/100 + "] ";
            }

            formattedResult.countText = "";

            var numFriends = formattedResult.users.length;

            formattedResult.count = formattedResult.count - formattedResult.users.length - (formattedResult.isMyBookmark ? 1 : 0);

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
          
          var adminMode = config["show_score"] === true;

          var tb = Mustache.to_html(
              req.responseText,
              {"results": results, "userInfo": userInfo, "adminMode": adminMode}
          );

          // Binders
          log("Preparing to inject!");
          $("#keepit").detach();

          function injectResults(times) {
            if(times<=0) {
              return;
            }
            if(resultsStore.query === '' || 
              typeof resultsStore.results === 'undefined' || 
              typeof resultsStore.results === 'undefined' || 
              resultsStore.results.length == 0) {
              // Catch bogus injections
              log("Injection not relevant. Stopping.");
              return;
            }
            else if($("#keepit:visible").length == 0) {
              console.log("Google isn't ready. Trying to injecting again...");
              if($('#ires').length > 0) {
                $('#ires').before(tb);
                $('.kifi_more_button').click(function() {
                  showMoreResults();
                });
                $('#kifi_trusted').click(function() {
                  $('#kifi_reslist').toggle('fast');
                  /*$('#kifi_trusted').click(function() {
                    resultsStore.show = resultsStore.showDefault;
                    updateQuery(0);
                  });*/
                });
                if(config["show_score"] === true) {
                  $('#admin-mode').show().click(function() {
                    $('#kifi_reslist').before('<div id="adminresults"><a href="http://' + config.server + '/admin/search/results/' + resultsStore.lastRemoteResults.uuid + '" target="_blank">Search result info<br/><br/></div>');
                    $(this).click(function() {
                      $("#adminresults").detach();
                    });
                    return false;
                  });
                }
              }
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
