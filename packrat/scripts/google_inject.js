// @match /^https?:\/\/www\.google\.(com|com\.(a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/.*/
// @require styles/google_inject.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/api.js

api.log("[google_inject.js]");

!function() {
  var restrictedGoogleInject = [
    "tbm=isch"
  ];
  var resultsStore = {
    "showDefault": 5
  };
  var inprogressSearchQuery = "";
  var timeSinceLastSearch;
  var kifiResultsClicked = 0;
  var googleResultsClicked = 0;

  function logEvent() {  // parameters defined in main.js
    api.port.emit("log_event", Array.prototype.slice.call(arguments));
  }

  function logSearchEvent() {
    var href = this.href, $li = $(this).closest("li.g");
    var $kifiRes = $("#kifi_reslist"), $kifiLi = $kifiRes.children("li.g");
    var resIdx = $li.parent().children("li.g").index($li);
    var query = $("#sftab").find("input[name=q]").val();

    if ($li[0].parentNode === $kifiRes[0]) {
      kifiResultsClicked++;
      var queryUUID = resultsStore.lastRemoteResults.uuid;
      if (href && resIdx >= 0 && queryUUID) {
        logEvent("search", "kifiResultClicked", {"url": href, "whichResult": resIdx, "query": query, "kifiResultsCount": $kifiLi.length, "queryUUID": queryUUID});
      }
    } else {
      googleResultsClicked++;
      if (href && resIdx >= 0) {
        logEvent("search", "googleResultClicked", {"url": href, "whichResult": resIdx, "query": query, "kifiResultsCount": $kifiLi.length});
      }
    }
  }

  function bindSearchLogger() {
    var $res = $("#res");
    if (!$res.data("kifi-mousedown")) {
      $res.data("kifi-mousedown", true).on("mousedown", "h3.r a", logSearchEvent);
    }
  }

  function updateQuery(calledTimes) {
    api.log("updating query...");

    var restrictedElements = $.grep(restrictedGoogleInject, function(e, i){
      return document.location.toString().indexOf(e) >= 0;
    });
    if (restrictedElements.length > 0) {
      api.log("restricted hover page: " + restrictedElements);
      return;
    }

    if (inprogressSearchQuery) {
      api.log("Another search is in progress. Ignoring query " + inprogressSearchQuery);
      return;
    }

    if ($("body").length === 0) {
      api.log("no body yet...");
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

    if (query === resultsStore.query) {
      api.log("Nothing new. Disregarding " + query);
      drawResults(0);
      return;
    }

    api.log("New query! New: " + query + ", old: " + resultsStore.query);

    inprogressSearchQuery = query;
    var t1 = new Date().getTime();
    api.port.emit("get_keeps", {query: query}, function(resp) {
      api.log("query response after: " + (new Date().getTime() - t1));
      api.log("RESULTS FROM SERVER", resp);

      inprogressSearchQuery = '';
      if (!resp.session) {
        api.log("No user info. Stopping search.")
        return;
      }
      if ($("input[name='q']").val() !== query || query !== resp.searchResults.query) { // query changed
        api.log("Query changed. Re-loading...");
        updateQuery(0);
        return;
      }
      $("#keepit").detach(); // get rid of old results
      resultsStore = {
        "lastRemoteResults": resp.searchResults,
        "results": resp.searchResults.hits,
        "query": query,
        "server": resp.server,
        "session": resp.session,
        "currentlyShowing": 0,
        "show": resp.maxResults,
        "mayShowMore": resp.searchResults.mayHaveMore,
        "showDefault": resp.maxResults,
        "maxResults": resp.maxResults,
        "showScores": resp.showScores};
      if (query === '') {
        return;
      }

      timeSinceLastSearch = new Date().getTime();

      api.log("kifi results recieved for " + resultsStore.query);
      api.log(resultsStore);

      logEvent("search", "kifiLoaded", {"query": resultsStore.lastRemoteResults.query, "queryUUID": resultsStore.lastRemoteResults.uuid });
      if (resp.searchResults.hits.length) {
        logEvent("search", "kifiAtLeastOneResult", {"query": resultsStore.lastRemoteResults.query, "queryUUID": resultsStore.lastRemoteResults.uuid });
      }
      kifiResultsClicked = 0;
      googleResultsClicked = 0;

      drawResults(0);

      //fetchMoreResults();
    });
  }

  function fetchMoreResults() {
    if (resultsStore.mayShowMore === true) {
      api.port.emit("get_keeps", {
        "query": resultsStore.query,
        "lastUUID": resultsStore.lastRemoteResults.uuid,
        "context": resultsStore.lastRemoteResults.context
      }, function(resp) {
        api.log("[fetchMoreResults] fetched:", resp);
        resultsStore.lastRemoteResults = resp.searchResults;
        resultsStore.results = resultsStore.results.concat(resp.searchResults.hits);
        api.log("[fetchMoreResults] stored:", resultsStore.results);
        resultsStore.mayShowMore = resp.searchResults.mayHaveMore;
        //drawResults(0);
      });
    } else {
      $('.kifi_more').hide();
    }
  }

  function showMoreResults() {
    var numberMore = resultsStore.maxResults;
    resultsStore.show = resultsStore.results.length >= resultsStore.show + numberMore ? resultsStore.show + numberMore : resultsStore.results.length;
    api.log("Showing more results", numberMore, resultsStore.results.length, resultsStore.currentlyShowing, resultsStore.show);
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
        api.log("No search results!");
        cleanupKifiResults();
        return;
      }

      if ($('#keepit').length && resultsStore.currentlyShowing === resultsStore.show) {
        api.log("Old keepit exists.");
        setTimeout(function(){ drawResults(++times); }, 100);
      } else {
        api.log("Drawing results", resultsStore, $("input[name='q']").val());
        addResults();
      }
    } catch (e) {
      api.log.error(e);
    }
  }

  updateQuery();

  /*$('#main').change(function() {
    api.log("Search results changed! Updating kifi results...");
    updateQuery();
  });*/

  setTimeout(function() {
    $("input[name='q']").parents("form").submit(function(){
      api.log("Input box changed (submit)! Updating kifi results...");
      updateQuery();
    });
    //updateQuery();
  },500);

  // The only reliable way to detect spelling clicks.
  // For some reason, spelling doesn't fire a blur()
  $(window).bind('hashchange', function() {
    api.log("URL has changed! Updating kifi results...");
    resultsStore.show = resultsStore.showDefault;
    updateQuery();
    setTimeout(function(){ updateQuery(0); }, 300); // sanity check
  });

  $(window).bind('unload', function() {
    var now = new Date().getTime();
    var inputQuery = $("input[name='q']").val();
    var kifiQuery = resultsStore.lastRemoteResults.query;

    if(inputQuery == kifiQuery && timeSinceLastSearch && now-timeSinceLastSearch > 2000) {
      logEvent("search", "searchUnload", {"query": kifiQuery, "queryUUID": resultsStore.lastRemoteResults.queryUUID, "kifiResultsClicked": kifiResultsClicked, "googleResultsClicked": googleResultsClicked});
    }
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
        api.log("Title difference...");
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
          var url = api.url("images/results/" + urlAutoFormatters[i].icon);
          result += "<span class=formatted_site style='background:url(" + url + ") no-repeat;background-size:15px'></span>";
        }
        result += urlAutoFormatters[i].template;
        return result;
      }
    }
    url = url.replace(/^https?:\/\//, "");
    if (url.length > 64) {
      url = url.substring(0, 60) + "...";
    }
    return boldSearchTerms(url, resultsStore.query);
  }

  var templateCache = {};
  function loadFile(path, callback) {
    var tmpl = templateCache[path];
    if (tmpl) {
      callback(tmpl);
    } else {
      api.load(path, function(tmpl) {
        callback(templateCache[path] = tmpl);
      });
    }
  }

  function addResults() {
    try {
      api.log("addResults parameters:", resultsStore);
      var session = resultsStore.session;
      var searchResults = resultsStore.results.slice(0,resultsStore.show);
      resultsStore.currentlyShowing = resultsStore.show;

      loadFile("html/google_inject.html", function(tmpl) {
          api.log('Rendering Mustache.js Google template...');
          var results = [];

          $(searchResults).each(function(i, result){
            var formattedResult = result;

            formattedResult.displayUrl = displayURLFormatter(formattedResult.bookmark.url);
            api.log(formattedResult.bookmark.url, formattedResult.displayUrl);

            formattedResult.bookmark.title = boldSearchTerms(formattedResult.bookmark.title, resultsStore.query);

            if (resultsStore.showScores === true) {
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

          var tb = Mustache.to_html(tmpl, {"results": results, "session": session});

          // Binders
          api.log("Preparing to inject!");
          $("#keepit").detach();

          function injectResults(times) {
            if (times <= 0) {
              return;
            }
            if (resultsStore.query === '' ||
                !resultsStore.results ||
                !resultsStore.results.length) {
              // Catch bogus injections
              api.log("Injection not relevant. Stopping.");
              return;
            } else if ($("#keepit:visible").length == 0) {
              api.log("Google isn't ready. Trying to injecting again...");
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
                if (resultsStore.showScores === true) {
                  $("#kifi_trusted").prepend(
                    '<a class=kifi-debug-results href="http://' + resultsStore.server + '/admin/search/results/' + resultsStore.lastRemoteResults.uuid + '" target=_blank>debug</a>');
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
      });
    } catch (e) {
      api.log.error(e);
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

      var socialNetworks = api.url("images/social_icons.png");
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

  function boldSearchTerms(text, query) {
    return (query.match(/\w+/g) || []).reduce(function(text, term) {
      return text.replace(new RegExp("(\\b" + term + "\\b)", "ig"), "<b>$1</b>");
    }, text);
  }
}();
