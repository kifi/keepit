// @match /^https?:\/\/www\.google\.(com|com\.(a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(|search|webhp)([?#].*)?$/
// @require styles/google_inject.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/api.js

api.log("[google_inject]");

!function() {
  function logEvent() {  // parameters defined in main.js
    api.port.emit("log_event", Array.prototype.slice.call(arguments));
  }

  var resultsHtml, hitHtml;
  api.load("html/search/google.html", function(html) { resultsHtml = html });
  api.load("html/search/google_hit.html", function(html) { hitHtml = html });

  var query;              // latest search query
  var response = {};      // latest kifi results received
  var showMoreOnArrival;
  var clicks = {kifi: 0, google: 0};

  // "main" div seems to always stay in the page, so only need to bind listener once.
  // TODO: move this code lower, so it runs after we initiate the first search
  // TODO: also detect result selection via keyboard
  $("#main").on("mousedown", "#search h3.r a", function logSearchEvent() {
    var href = this.href, $li = $(this).closest("li.g");
    var $kifiRes = $("#kifi-res-list"), $kifiLi = $kifiRes.children("li.g");
    var resIdx = $li.parent().children("li.g").index($li);
    var isKifi = $li[0].parentNode === $kifiRes[0];

    clicks[isKifi ? "kifi" : "google"]++;

    if (href && resIdx >= 0) {
      logEvent("search", isKifi ? "kifiResultClicked" : "googleResultClicked",
        {"url": href, "whichResult": resIdx, "query": response.query, "experimentId": response.experimentId, "kifiResultsCount": $kifiLi.length});
    }
  });

  var keyTimer, idleTimer, tQuery = +new Date, tGoogleResultsShown = tQuery, tKifiResultsReceived, tKifiResultsShown;
  var $q = $("#gbqfq").on("input", function() {  // stable identifier: "Google Bar Query Form Query"
    tQuery = +new Date;
    clearTimeout(keyTimer);
    keyTimer = setTimeout(search, 120);  // enough of a delay that we won't search after *every* keystroke (similar to Google's behavior)
  });
  var $qPredict = $("#gs_taif0");  // stable identifier: "Google Search Type-Ahead Input Field 0"
  $("#gbqf").submit(function() {  // stable identifier: "Google Bar Query Form"
    tQuery = +new Date;
    clearTimeout(keyTimer);
    search();  // immediate search
  });

  function onIdle() {
    logEvent("search", "dustSettled", {
      "query": query,
      "kifiHadResults": response.hits && response.hits.length > 0,
      "kifiReceivedAt": tKifiResultsReceived - tQuery,
      "kifiShownAt": tKifiResultsShown - tQuery,
      "googleShownAt": tGoogleResultsShown - tQuery});
  }

  checkSearchType();
  search(parseQuery(location.hash));  // Google can be slow to initialize the input field from the hash.

  var isVertical;
  function checkSearchType() {
    var isV = ~document.URL.indexOf("tbm=");
    if (isV !== isVertical) {
      api.log("[checkSearchType] search type:", isV ? "vertical" : "web");
      isVertical = isV;
    }
  }

  function search(fallbackQuery) {
    if (isVertical) return;

    var q = ($qPredict.val() || $q.val() || fallbackQuery || "").trim().replace(/\s+/, " ");  // TODO: also detect "Showing results for" and prefer that
    if (q === query) {
      api.log("[search] nothing new");
      return;
    } else if (!(query = q)) {
      api.log("[search] empty query");
      return;
    }

    api.log("[search] query:", q);

    clearTimeout(idleTimer);
    idleTimer = setTimeout(onIdle, 1200);
    var t1 = +new Date;
    api.port.emit("get_keeps", {query: q}, function results(resp) {
      if (q !== query) {
        api.log("[results] ignoring for:", q);
        return;
      } else if (!resp.session) {
        api.log("[results] no user info")
        return;
      }

      tKifiResultsReceived = +new Date;
      api.log("[results] response after", tKifiResultsReceived - t1, "ms:", resp);

      $("#kifi-res").remove(); // remove any old results
      response = resp;
      clicks.kifi = clicks.google = 0;

      logEvent("search", "kifiLoaded", {"query": q, "queryUUID": resp.uuid});
      if (resp.hits.length) {
        logEvent("search", "kifiAtLeastOneResult", {"query": q, "queryUUID": resp.uuid, experimentId: response.experimentId});
        showResults()
        prefetchMore();
      } else {
        resp.shown = true;
        tKifiResultsShown = tKifiResultsReceived;
      }
    });
  }

  function parseQuery(hash) {
    var m = /[?#&]q=[^&]*/.exec(hash);
    return m && unescape(m[0].substr(3).replace("+", " ")).trim() || "";
  }

  $(window).bind("hashchange", function() {
    api.log("[hashchange]");
    checkSearchType();
    search();  // needed for switch from shopping to web search, for example
  }).bind("unload", function() {
    if (response.query === query && new Date - tKifiResultsShown > 2000) {
      logEvent("search", "searchUnload", {
        "query": response.query,
        "queryUUID": response.uuid,
        "kifiResultsClicked": clicks.kifi,
        "googleResultsClicked": clicks.google});
    }
  });

  var MutationObserver = window.MutationObserver || window.WebKitMutationObserver || window.MozMutationObserver;
  new MutationObserver(function onMutation(mutations) {
    for (var i = 0; i < mutations.length; i++) {
      for (var j = 0, nodes = mutations[i].addedNodes; j < nodes.length; j++) {
        if (nodes[j].id === "ires") {
          tGoogleResultsShown = +new Date;
          api.log("[onMutation] Google results inserted");
          insertResults.call(nodes[j]);
        }
      }
    }
  }).observe(document.getElementById("main"), {childList: true, subtree: true});

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

  function showResults() {
    var hits = response.hits.slice();  // copy to ensure that we don't render any prefetched results that arrive
    api.log("[showResults] hits:", hits);

    hits.forEach(processHit);

    response.html = Mustache.to_html(resultsHtml, {
        results: hits,
        session: response.session,
        endBgUrl: api.url("images/shade_above.png"),
        mayHaveMore: response.mayHaveMore},
      {google_hit: hitHtml});

    $("#ires").each(insertResults);
  }

  function insertResults() {
    // assert(this.id == "ires")
    if (!response.html) return;
    if (!response.shown) {
      response.shown = true;
      tKifiResultsShown = +new Date;
    }
    var $res = $(response.html)
      .find(".kifi-more")[response.mayHaveMore ? "show" : "hide"]().end()
      .insertBefore(this);
    api.log("[insertResults] inserted");
    $res.on("click", ".kifi-more", function onMoreClick() {
      var numShown = $("#kifi-res-list").children("li.g").length;
      api.log("[onMoreClick] shown:", numShown, "on hand:", response.hits.length);
      if (response.hits.length > numShown) {
        renderMore(response.hits.slice(numShown));
        prefetchMore();
      } else if (response.mayHaveMore) {
        showMoreOnArrival = true;
      } else {
        $(this).closest(".kifi-more").hide(200);
      }
    }).on("click", "#kifi-trusted", function() {
      $(this).nextAll().toggle("fast");
    });
    if (response.showScores) {
      $("#kifi-trusted").prepend(
        '<a class=kifi-debug-results href="https://' + response.server + '/admin/search/results/' + response.uuid + '" target=_blank>debug</a>');
    }
  }

  function prefetchMore() {
    if (response.mayHaveMore) {
      api.port.emit("get_keeps", {
        "query": response.query,
        "lastUUID": response.uuid,
        "context": response.context
      }, function onPrefetchResponse(resp) {
        api.log("[onPrefetchResponse]", resp);
        resp.hits.forEach(processHit);

        response.uuid = resp.uuid;
        response.context = resp.context;
        response.mayHaveMore = resp.mayHaveMore;
        response.hits.push.apply(response.hits, resp.hits);
        if (showMoreOnArrival) {
          showMoreOnArrival = false;
          renderMore(resp.hits);
          prefetchMore();
        }
      });
    }
  }

  function renderMore(hits) {
    api.log("[renderMore] hits:", hits);
    var html = hits.map(function(hit) {
      hit.session = response.session;
      return Mustache.to_html(hitHtml, hit);
    }).join("");
    var $list = $("#kifi-res-list");
    $(html).hide().insertAfter($list.children("li.g").last()).slideDown(200);
    if (!response.mayHaveMore) {
      $list.find(".kifi-more").hide(200);
    }
  }

  function processHit(hit) {
    hit.displayUrl = displayURLFormatter(hit.bookmark.url);
    // api.log("[processHit] hit url:", hit.bookmark.url, "displayed as:", hit.displayUrl);

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
  }

  function plural(n, term) {
    return n + " " + term + (n == 1 ? "" : "s");
  }

  function boldSearchTerms(text, query) {
    return (query.match(/\w+/g) || []).reduce(function(text, term) {
      return text.replace(new RegExp("(\\b" + term + "\\b)", "ig"), "<b>$1</b>");
    }, text);
  }
}();
