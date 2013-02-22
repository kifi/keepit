// @match /^https?:\/\/www\.google\.(com|com\.(a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/.*/
// @require styles/google_inject.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/api.js

api.log("[google_inject.js]");

api.load("html/google_inject.html", function(tmpl) {
  var $q = $("#gbqfq");   // a stable identifier: "Google Bar Query Form Query"
  var response = {};      // kifi results for latest search query
  var inprogressSearchQuery, showMoreOnArrival;
  var clicks = {kifi: 0, google: 0};

  function logEvent() {  // parameters defined in main.js
    api.port.emit("log_event", Array.prototype.slice.call(arguments));
  }

  // "main" div always stays in the page, so only need to bind listener once.
  // TODO: also detect result selection via keyboard
  $("#main").on("mousedown", "#search h3.r a", function logSearchEvent() {
    var href = this.href, $li = $(this).closest("li.g");
    var $kifiRes = $("#kifi_reslist"), $kifiLi = $kifiRes.children("li.g");
    var resIdx = $li.parent().children("li.g").index($li);
    var isKifi = $li[0].parentNode === $kifiRes[0];

    clicks[isKifi ? "kifi" : "google"]++;

    if (href && resIdx >= 0) {
      logEvent("search", isKifi ? "kifiResultClicked" : "googleResultClicked",
        {"url": href, "whichResult": resIdx, "query": response.query, "experimentId": response.experimentId, "kifiResultsCount": $kifiLi.length});
    }
  });

  function updateQuery(numPrevAttempts) {
    if (~document.URL.indexOf("tbm=isch")) {
      api.log("[updateQuery] bailing (image search)");
      return;
    }

    if (inprogressSearchQuery) {  // TODO: Reverse this. New query should have priority.
      api.log("[updateQuery] already in progress:", inprogressSearchQuery);
      return;
    }

    var query = $q.val();
    if (!query) {
      api.log("[updateQuery] bailing (no query), prev attempts:", numPrevAttempts);
      if (numPrevAttempts < 10) {
        setTimeout(updateQuery.bind(null, numPrevAttempts + 1), 200);
      }
      return;
    }

    if (query === response.query) {
      api.log("[updateQuery] nothing new");
      return;
    }

    api.log("[updateQuery] new query:", query, "old:", response.query);

    inprogressSearchQuery = query;
    var t1 = new Date;
    api.port.emit("get_keeps", {query: query}, function gotKeeps(resp) {
      api.log("[gotKeeps] response after", new Date - t1, "ms:", resp);

      inprogressSearchQuery = '';
      if (!resp.session) {
        api.log("[gotKeeps] no user info")
        return;
      }
      if ($q.val() !== query) { // query changed
        api.log("[gotKeeps] query changed...");
        updateQuery(0);
        return;
      }
      $("#keepit").remove(); // remove any old results
      response = resp;
      response.arrivalTime = +new Date;
      clicks.kifi = clicks.google = 0;

      logEvent("search", "kifiLoaded", {"query": query, "queryUUID": resp.uuid});
      if (resp.hits.length) {
        logEvent("search", "kifiAtLeastOneResult", {"query": query, "queryUUID": resp.uuid, experimentId: response.experimentId});
        showResultsAndPrefetchMore();
      }
    });
  }

  updateQuery(0);

  $("#gbqf").submit(function() {  // stable identifier: "Google Bar Query Form"
    api.log("[formSubmit]");
    updateQuery(0);
  });

  // The only reliable way to detect spelling clicks. (?)
  // Consider using WebKitMutationObserver/MozMutationObserver to detect show/hide and insert/remove of Google results.
  $(window).bind("hashchange", function() {
    api.log("[hashchange]");
    updateQuery(0);
  }).bind("unload", function() {
    if (response.query === $q.val() && new Date - response.arrivalTime > 2000) {
      logEvent("search", "searchUnload", {
        "query": response.query,
        "queryUUID": response.uuid,
        "kifiResultsClicked": clicks.kifi,
        "googleResultsClicked": clicks.google});
    }
  });

  function cleanupKifiResults() {
    if (/^\s*$/.test(response.query || "") || !response.hits.length) {
      $("#keepit").remove();
    }
  }
  setInterval(cleanupKifiResults, 1000);

  /*******************************************************/

  var urlAutoFormatters = [{
      match: /^https?:\/\/docs\.google\.com\//,
      template: "A file in Google Docs",
      icon: "gdocs.gif"
    }, {
      match: /^https?:\/\/drive\.google\.com\//,
      template: "A folder in your Google Drive",
      icon: "gdrive.png"
    }, {
      match: /^https?:\/\/www.dropbox\.com\/home/,
      template: "A folder in your Dropbox",
      icon: "dropbox.png"
    }, {
      match: /^https?:\/\/dl-web\.dropbox\.com\//,
      template: "A file from Dropbox",
      icon: "dropbox.png"
    }, {
      match: /^https?:\/\/www.dropbox\.com\/s\//,
      template: "A shared file on Dropbox",
      icon: "dropbox.png"
    }, {  // TODO: add support for Gmail labels like inbox/starred?
      match: /^https?:\/\/mail\.google\.com\/mail\/.*#.*\/[0-9a-f]{10,}$/,
      template: "An email on Gmail",
      icon: "gmail.png"
    }, {
      match: /^https?:\/\/www.facebook\.com\/messages\/\w[\w.-]{2,}$/,
      template: "A conversation on Facebook",
      icon: "facebook.png"
    }];

  function displayURLFormatter(url) {
    var prefix = "^https?://w{0,3}\\.?";
    for (var i = 0; i < urlAutoFormatters.length; i++) {
      if (urlAutoFormatters[i].match.test(url)) {
        var iconUrl = api.url("images/results/" + urlAutoFormatters[i].icon);
        return "<span class=formatted_site style='background:url(" + iconUrl + ") no-repeat;background-size:15px'></span>" +
          urlAutoFormatters[i].template;
      }
    }
    url = url.replace(/^https?:\/\//, "");
    if (url.length > 64) {
      url = url.substring(0, 60) + "...";
    }
    return boldSearchTerms(url, response.query);
  }

  function showResultsAndPrefetchMore() {
    var hits = response.hits.slice();  // copy to ensure that we don't render any prefetched results that arrive
    api.log("[showResults] hits:", hits);

    hits.forEach(function(hit) {
      hit.displayUrl = displayURLFormatter(hit.bookmark.url);
      // api.log("[showResults] hit url:", hit.bookmark.url, "displayed as:", hit.displayUrl);

      hit.bookmark.title = boldSearchTerms(hit.bookmark.title, response.query);

      if (response.showScores === true) {
        hit.displayScore = "[" + Math.round(hit.score * 100) / 100 + "] ";
      }

      hit.countText = "";

      var numFriends = hit.users.length;

      hit.count = hit.count - hit.users.length - (hit.isMyBookmark ? 1 : 0);

      // Awful decision tree for clean text. Come up with a better way.
      if (hit.isMyBookmark) { // you
        if (numFriends == 0) { // no friends
          if (hit.count > 0) { // others
            hit.countText = "You and " + plural(hit.count, "other");
          } else { // no others
            hit.countText = "You";
          }
        } else { // numFriends > 0
          if (hit.count > 0) { // others
            hit.countText = "You, <b>" + plural(numFriends, "friend") + "</b>, and " + plural(hit.count, "other");
          } else { // no others
            hit.countText = "You and <b>" + plural(numFriends, "friend") + "</b>";
          }
        }
      } else { // not you
        if (numFriends == 0) { // no friends
          if (hit.count > 0) { // others
            hit.countText = plural(hit.count, "other");
          } else { // no others
            hit.countText = "No one"; // ???
          }
        } else { // numFriends > 0
          if (hit.count > 0) { // others
            hit.countText = "<b>" + plural(numFriends, "friend") + "</b>, and " + plural(hit.count, "other");
          } else { // no others
            hit.countText = "<b>" + plural(numFriends, "friend") + "</b>";
          }
        }
      }
    });

    var html = Mustache.to_html(tmpl, {results: hits, session: response.session});

    $("#keepit").remove();
    insertResults(100);

    if (response.mayHaveMore) {
      api.port.emit("get_keeps", {
        "query": response.query,
        "lastUUID": response.uuid,
        "context": response.context
      }, function onPrefetchResponse(resp) {
        api.log("[onPrefetchResponse]", resp);
        response.uuid = resp.uuid;
        response.context = resp.context;
        response.mayHaveMore = resp.mayHaveMore;
        response.hits.push.apply(response.hits, resp.hits);
        if (showMoreOnArrival) {
          showMoreOnArrival = false;
          showResultsAndPrefetchMore();
        }
      });
    }

    function insertResults(attemptsLeft) {
      if (document.getElementById("keepit")) {
        api.log.error(Error("#keepit found"), "insertResults");
        return;
      }
      var googRes = document.getElementById("ires");  // TODO: use WebKitMutationObserver/MozMutationObserver for less latency
      if (!googRes) {
        api.log("[insertResults] Google not ready, remaining retries:", attemptsLeft);
        if (attemptsLeft) {
          setTimeout(insertResults.bind(null, attemptsLeft - 1), 1000 / attemptsLeft);
        }
        return;
      }
      $(googRes).before(html);
      api.log("[insertResults] inserted");
      $(".kifi_more_button").click(function() {
        if (response.hits.length > $("#kifi_reslist").children("li.g").length) {
          // TODO: only append new results, and reveal them with a slide-down animation
          showResultsAndPrefetchMore();
        } else {
          showMoreOnArrival = true;
        }
      });
      $("#kifi_trusted").click(function() {
        $("#kifi_reslist").toggle("fast");
      });
      if (response.showScores) {
        $("#kifi_trusted").prepend(
          '<a class=kifi-debug-results href="https://' + response.server + '/admin/search/results/' + response.uuid + '" target=_blank>debug</a>');
      }
    }
  }

  function plural(n, term) {
    return n + " " + term + (n == 1 ? "" : "s");
  }

  function boldSearchTerms(text, query) {
    return (query.match(/\w+/g) || []).reduce(function(text, term) {
      return text.replace(new RegExp("(\\b" + term + "\\b)", "ig"), "<b>$1</b>");
    }, text);
  }
});
