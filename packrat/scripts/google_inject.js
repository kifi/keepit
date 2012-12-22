console.log("[" + new Date().getTime() + "] in google_inject.js");

chrome.extension.sendMessage({type: "require", injected: window.injected,
  scripts: [
    "scripts/lib/jquery-1.8.2.min.js",
    "scripts/lib/mustache-0.7.1.min.js"]},
function() {
  var lastInjected;
  var config;
  var restrictedGoogleInject = [
    "tbm=isch"
  ];
  var resultsStore = {
    "showDefault": 5
  };
  var inprogressSearchQuery = "";

  function log() {
    console.log.apply(console, Array.prototype.concat.apply(["[" + new Date().getTime() + "] "], arguments));
  }

  function error(exception, message) {
    console.error(exception);
    console.error((message ? "[" + message + "] " : "") + exception.message);
    console.error(exception.stack);
    //alert("exception: " + exception.message);
  }

  function logSearchEvent(t) {
    //$("li.g .vsc a").mousedown(function(t) { console.log($(this).parents('.kifi_reslist').length); });
    function countWhichResult(elem, selector) {
      var results = selector.find('.g .vsc h3.r a');
      var res = -1;
      for(var i = 0;i<results.length;i++) {
        if(results[i] == elem) {
          res = i;
          break;
        }
      }
      return res;
    }
    var $this = $(t);
    var kifi_reslist = $this.parents('#kifi_reslist')
    var isKifi = kifi_reslist.length == 1;
    var kifiResults = $('#kifi_reslist li').length;
    var href = $this.attr("href");
    var whichResult = -1;
    if(isKifi) {
      whichResult = countWhichResult($this[0], kifi_reslist);
    }
    else {
      whichResult = countWhichResult($this[0], $("#ires"));
    }
    whichResult = whichResult.toString();
    var query = $("input[name='q']").val();
    var queryUUID = resultsStore.lastRemoteResults.uuid;

    if(isKifi && href && queryUUID && whichResult != -1)
      logEvent("search", "kifiResultClicked", {"url": href, "whichResult": whichResult, "query": query, "queryUUID": queryUUID});
    else if(!isKifi && href && whichResult != -1) {
      logEvent("search", "googleResultClicked", {"url": href, "whichResult": whichResult, "query": query, "kifiResultsCount": kifiResults.length});
    }

    if(kifiHasResults && !isKifi && href && whichResult != -1) {
      logEvent("search", "googleResultClickedOverKifi", {"url": href, "whichResult": whichResult, "query": query, "kifiResultsCount": kifiResults.length});
    }
  }

  function bindSearchLogger() {
    if(!$("#main").data("kgr-l")) {
      $("#main").data("kgr-l", true);
      $("#main").on('mousedown','.g .vsc h3.r a', function() {
        logSearchEvent(this);
      });
    }
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

    if (inprogressSearchQuery !== '') {
      // something else is running it.
      log("Another search is in progress. Ignoring query " + inprogressSearchQuery);
      return;
    }

    if ($("body").length === 0) {
      log("no body yet...");
      setTimeout(function(){ updateQuery(); }, 10);
      return;
    }

    bindSearchLogger();

    var query = $("input[name='q']").val();
    if (!query) {
      if (typeof calledTimes !== 'undefined' && calledTimes <= 10) {
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
    chrome.extension.sendMessage(request, function(results) {
      log("query response after: " + (new Date().getTime() - t1));
      log("RESULTS FROM SERVER", results);

      inprogressSearchQuery = '';
      if (!results.userConfig || !results.session) {
        log("No user info. Stopping search.")
        return;
      }
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
        "session": results.session,
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

      logEvent("search", "kifiLoaded");
      if(results.searchResults.hits.length > 0) {
        logEvent("search", "kifiAtLeastOneResult");
      }

      drawResults(0);

      //fetchMoreResults();
    });
  }

  function fetchMoreResults() {
    if (resultsStore.mayShowMore === true) {
      var request = {
        "type": "get_keeps",
        "query": resultsStore.query,
        "lastUUID": resultsStore.lastRemoteResults.uuid,
        "context": resultsStore.lastRemoteResults.context
      };

      ///search2?term=<term>&externalId=<user external ID>&lastUUID=<uuid>&context=<context string>
      chrome.extension.sendMessage(request, function(results) {
        log("fetched more!",results);
        resultsStore.lastRemoteResults = results.searchResults;
        resultsStore.results = resultsStore.results.concat(results.searchResults.hits);
        log(resultsStore.results);
        resultsStore.mayShowMore = results.searchResults.mayHaveMore;
        //drawResults(0);
      });
    } else {
      $('.kifi_more').hide();
    }
  }

  function showMoreResults() {
    var numberMore = resultsStore.userConfig.max_res;
    resultsStore.show = resultsStore.results.length >= resultsStore.show + numberMore ? resultsStore.show + numberMore : resultsStore.results.length;
    log("Showing more results", numberMore, resultsStore.results.length, resultsStore.currentlyShowing, resultsStore.show);
    drawResults(0);
    if (resultsStore.results.length < resultsStore.show + numberMore)
      fetchMoreResults();
  }

  function drawResults(times) {
    if (times > 30) {
      return;
    }
    var searchResults = resultsStore.results;
    try {
      if (!searchResults || !searchResults.length) {
        log("No search results!");
        cleanupKifiResults();
        return;
      }

      if ($('#keepit').length && resultsStore.currentlyShowing === resultsStore.show) {
        log("Old keepit exists.");
        setTimeout(function(){ drawResults(++times); }, 100);
      } else {
        log("Drawing results", resultsStore, $("input[name='q']").val());
        addResults();
      }
    } catch (e) {
      error(e);
    }
  }

  chrome.extension.sendMessage({type: "get_conf"}, function(o) {
    config = o.config;
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
    var currentQuery = "";
    if (!resultsStore.query || !resultsStore.results.length) {
      $("#keepit").remove();
      if (resultsStore.query) {
        currentQuery = resultsStore.query;
      }
    } else {
      currentQuery = resultsStore.query.replace(/\s+/g, '');
    }
    var googleSearch = '';
    var pos = document.title.indexOf(" - Google Search");
    if (pos > 0) {
      googleSearch = document.title.substr(0, pos).replace(/\s+/g, '');
      if (currentQuery !== googleSearch) {
        log("Title difference...");
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

  var urlAutoFormatters = [{
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
    }];

  function displayURLFormatter(url) {
    var prefix = "^https?://w{0,3}\\.?";
    for (var i = 0; i < urlAutoFormatters.length; i++) {
      var regex = new RegExp(prefix + urlAutoFormatters[i].match, "ig");
      if (regex.test(url)) {
        var result = "";
        if (urlAutoFormatters[i].icon) {
          var icon = chrome.extension.getURL('images/results/' + urlAutoFormatters[i].icon);
          result += "<span class=formatted_site style='background:url(" + icon + ") no-repeat;background-size:15px'></span>";
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
      log("addResults parameters:", resultsStore);
      var session = resultsStore.session;
      var searchResults = resultsStore.results.slice(0,resultsStore.show);
      resultsStore.currentlyShowing = resultsStore.show;

      var req = new XMLHttpRequest();
      req.open("GET", chrome.extension.getURL('html/google_inject.html'), true);
      req.onreadystatechange = function() {
        if (req.readyState == 4 && req.status == 200) {

          log('Rendering Mustache.js Google template...');
          var results = [];

          $(searchResults).each(function(i, result){
            var formattedResult = result;

            formattedResult.displayUrl = displayURLFormatter(formattedResult.bookmark.url);
            log(formattedResult.bookmark.url, formattedResult.displayUrl);

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
            if (formattedResult.isMyBookmark) { // you
              if (numFriends == 0) { // no friends
                if (formattedResult.count > 0) { // others
                  formattedResult.countText = "You and " + plural(formattedResult.count, "other");
                } else { // no others
                  formattedResult.countText = "You";
                }
              } else { // numFriends > 0
                if (formattedResult.count > 0) { // others
                  formattedResult.countText = "You, <b>" + plural(numFriends, "friend") + "</b>, and " + plural(formattedResult.count, "other");
                } else { // no others
                  formattedResult.countText = "You and <b>" + plural(numFriends, "friend") + "</b>";
                }
              }
            } else { // not you
              if (numFriends == 0) { // no friends
                if (formattedResult.count > 0) { // others
                  formattedResult.countText = plural(formattedResult.count, "other");
                } else { // no others
                  formattedResult.countText = "No one"; // ???
                }
              } else { // numFriends > 0
                if (formattedResult.count > 0) { // others
                  formattedResult.countText = "<b>" + plural(numFriends, "friend") + "</b>, and " + plural(formattedResult.count, "other");
                } else { // no others
                  formattedResult.countText = "<b>" + plural(numFriends, "friend") + "</b>";
                }
              }
            }

            results.push(formattedResult);
          });

          var adminMode = config["show_score"] === true;

          var tb = Mustache.to_html(
              req.responseText,
              {"results": results, "session": session, "adminMode": adminMode}
          );

          // Binders
          log("Preparing to inject!");
          $("#keepit").detach();

          function injectResults(times) {
            if (times <= 0) {
              return;
            }
            if (resultsStore.query === '' ||
              typeof resultsStore.results === 'undefined' ||
              typeof resultsStore.results === 'undefined' ||
              resultsStore.results.length == 0) {
              // Catch bogus injections
              log("Injection not relevant. Stopping.");
              return;
            } else if ($("#keepit:visible").length == 0) {
              log("Google isn't ready. Trying to injecting again...");
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

  function plural(n, term) {
    return n + " " + term + (n == 1 ? "" : "s");
  }

  function socialTooltip(friend, element) {
     // disabled for now
    getTemplate("html/social_hover.html",{"friend": friend}, function(tmpl) {
      var timeout;
      var timein;

      var friendTooltip = $('.friend_tooltip').first().clone().appendTo('.friendlist').html(tmpl);

      var socialNetworks = chrome.extension.getURL("images/social_icons.png");
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
    if (!needle) return input;
    if (useSpaces === true)
      return input.replace(new RegExp('(^|\\s)(' + needle + ')(\\s|$)','ig'), '$1<b>$2</b>$3');
    else
      return input.replace(new RegExp('(^|\\.?)(' + needle + ')(\\.?|$)','ig'), '$1<b>$2</b>$3');
  }
});
