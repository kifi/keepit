// @match /^https?:\/\/www\.google\.(com|com\.(a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(|search|webhp)([?#].*)?$/
// @require styles/google_inject.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/jquery-showhover.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/api.js

api.log("[google_inject]");

!function() {
  function logEvent() {  // parameters defined in main.js
    api.port.emit("log_event", Array.prototype.slice.call(arguments));
  }

  var hitsHtml, hitHtml, chatterHtml;
  api.load("html/search/google.html", function(html) { $res = $(Mustache.to_html(html)).hide(); bindHandlers() });
  api.load("html/search/google_hits.html", function(html) { hitsHtml = html });
  api.load("html/search/google_hit.html", function(html) { hitHtml = html });
  api.load("html/search/chatter.html", function(html) { chatterHtml = html });

  var $res = $();         // reference to our search results (kept so that we can reinsert when removed)
  var filter;             // current search filter (null, "a", "m", "f", or zero or more dot-delimited user ids)
  var query = "";         // latest search query
  var response = {};      // latest kifi results received
  var showMoreOnArrival;
  var clicks = {kifi: 0, google: 0};
  var friends;            // friends cache for custom filter autocomplete

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
  $("#gbqf").submit(function() {  // stable identifier: "Google Bar Query Form"
    tQuery = +new Date;
    clearTimeout(keyTimer);
    search();  // immediate search
  });

  function onIdle() {
    logEvent("search", "dustSettled", {
      "query": query,
      "experimentId": response.experimentId,
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

  function search(fallbackQuery, newFilter) {
    if (isVertical) return;

    var q = (getPrediction() || $q.val() || fallbackQuery || "").trim().replace(/\s+/, " ");  // TODO: also detect "Showing results for" and prefer that
    var f = newFilter || filter;
    if (q == query && f == filter) {
      api.log("[search] nothing new, query:", q, "filter:", f);
      return;
    }
    query = q;
    filter = f;
    if (!q) {
      api.log("[search] empty query");
      return;
    }
    api.log("[search] query:", q, "filter:", f);

    clearTimeout(idleTimer);
    idleTimer = setTimeout(onIdle, 1200);
    var t1 = +new Date;
    api.port.emit("get_keeps", {query: q, filter: f}, function results(resp) {
      if (q != query || f != filter) {
        api.log("[results] ignoring for query:", q, "filter:", f);
        return;
      } else if (!resp.session) {
        api.log("[results] no user info");
        return;
      }

      tKifiResultsReceived = +new Date;
      api.log("[results] response after", tKifiResultsReceived - t1, "ms:", resp);

      $resList.remove(); // remove any old results
      response = resp;
      response.hits.forEach(processHit);
      response.filter = f;
      if (!newFilter) {
        clicks.kifi = clicks.google = 0;
      }

      var ires = document.getElementById("ires");
      if (ires) {
        response.shown = true;
        tKifiResultsShown = +new Date;
      }

      if (response.hits.length || f) {  // we show a "no results match filter" message if filtering
        appendResults();
        $res.show().insertBefore(ires);
      } else {
        $res.hide();
      }

      logEvent("search", "kifiLoaded", {"query": q, "filter": f, "queryUUID": resp.uuid});
      if (resp.hits.length) {
        logEvent("search", "kifiAtLeastOneResult", {"query": q, "filter": f, "queryUUID": resp.uuid, "experimentId": resp.experimentId});
        loadChatter(response.hits.slice());
        prefetchMore();
      }
    });

    var $resList = $res.find("#kifi-res-list,.kifi-res-end").css("opacity", .2);
  }

  var elPredict;
  function getPrediction() {
    return (elPredict || (elPredict = document.getElementById("gs_taif0")) || 0).value;  // stable identifier: "Google Search Type-Ahead Input Field 0"
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
          $res.insertBefore(nodes[j]);  // reattach
          if (!response.shown) {
            response.shown = true;
            tKifiResultsShown = +new Date;
          }
          search();  // prediction may have changed
        }
      }
    }
  }).observe(document.getElementById("main"), {childList: true, subtree: true});  // TODO: optimize away subtree

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

  function bindHandlers() {
    $res.on("click", ".kifi-res-more", function onMoreClick() {
      api.log("[onMoreClick] shown:", response.hits.length, "avail:", response.nextHits);
      if (response.nextHits) {
        renderMore();
        prefetchMore();
      } else if (response.mayHaveMore) {
        showMoreOnArrival = true;
      } else {
        $(this).closest(".kifi-res-more").hide(200);
      }
    }).on("click", ".kifi-res-title", function() {
      $res.find("#kifi-res-list,.kifi-res-end").toggle(200);
      $res.find(".kifi-res-filter-keeps").fadeToggle(200);
      $res.find(".kifi-res-filters-x:visible").click();
      $(this).toggleClass("kifi-collapsed").delay(200);
    }).on("click", ".kifi-res-filter-keeps", function() {
      var $f = $res.find(".kifi-res-filters");
      if ($f.is(":animated")) return;
      if ($f.is(":visible")) {
        $f.find(".kifi-res-filters-x").click();
      } else {
        $(this).addClass("kifi-expanded");
        $f.slideDown(200);
      }
    }).on("click", ".kifi-res-filters-x", function() {
      var $fils = $(this).closest(".kifi-res-filters");
      var $fil = $fils.find(".kifi-res-filter[data-filter=a]");
      if (!$fil.hasClass("kifi-selected")) {
        $fil.click();
        $fils.delay(150);
      }
      $fils.slideUp(200, function() {
        $res.find(".kifi-res-filter-keeps").removeClass("kifi-expanded");
      });
    }).on("click", ".kifi-res-filter:not(.kifi-selected)", function() {
      var $f = $(this).siblings(".kifi-selected").removeClass("kifi-selected").end().addClass("kifi-selected");
      var f = $f.data("filter");
      if (f != "c") {
        $res.find(".kifi-res-filter-custom").slideUp(200, function() {
          var $in = $res.find("#kifi-res-filter-cust");
          if ($in.tokenInput) {
            $in.tokenInput("clear");
          }
        });
        search(null, f);
      } else {
        var $in = $res.find("#kifi-res-filter-cust");
        $res.find(".kifi-res-filter-custom").slideDown(200, function() {
          $("#token-input-kifi-res-filter-cust").focus();
        });
        if (friends) {
          withFriends();
        } else {
          api.port.emit("get_friends", function(data) {
            api.log("friends:", data);
            friends = data.friends;
            for (var i in friends) {
              var f = friends[i];
              f.name = f.firstName + " " + f.lastName;
            }
            withFriends();
          });
        }
        function withFriends() {
          api.require("scripts/lib/jquery-tokeninput-1.6.1.min.js", function() {
            if ($in.prev("ul").length) return;
            $in.tokenInput(friends, {
              searchDelay: 0,
              minChars: 2,
              placeholder: $in.prop("placeholder"),
              hintText: "",
              noResultsText: "",
              searchingText: "",
              animateDropdown: false,
              preventDuplicates: true,
              allowTabOut: true,
              tokenValue: "externalId",
              theme: "googly",
              onReady: function() {
                $("#token-input-kifi-res-filter-cust").focus();
              },
              onAdd: function(friend) {
                api.log("[onAdd]", friend.externalId, friend.name);
                search("", filter.length > 1 ? (filter + "." + friend.externalId) : friend.externalId);
              },
              onDelete: function(friend) {
                api.log("[onDelete]", friend.externalId, friend.name);
                var f = filter.split(".").filter(function(id) {return id != friend.externalId}).join(".");
                if (f) {
                  search("", f);
                } else {
                  filter = "";  // signifies an empty custom filter
                }
              }});
           });
        }
      }
    }).on("click", ".kifi-res-filter-custom-x", function() {
      $res.find(".kifi-res-filter[data-filter=a]").click();
    }).on("click", ".kifi-res-debug", function(e) {
      e.stopPropagation();
      location = "https://" + response.server + "/admin/search/results/" + response.uuid;
    }).on("mouseenter", ".kifi-chatter", function() {
      $(this).showHover(function() {
        var n = $(this).data("n");
        return Mustache.to_html(chatterHtml, {
          numComments: n[0],
          numMessages: n[1],
          pluralize: function() {return pluralLambda}});
      }).showHover("enter");
    }).on("click", ".kifi-chatter-deeplink", function() {
      api.port.emit("add_deep_link_listener", {locator: $(this).data("locator")});
      window.location = $(this).closest("li.g").find("h3.r a")[0].href;
    });
  }

  function appendResults() {
    var params = {
      results: response.hits,
      anyResults: response.hits.length > 0,
      session: response.session,
      endBgUrl: api.url("images/shade_above.png"),
      mayHaveMore: response.mayHaveMore};

    $res.append(Mustache.to_html(hitsHtml, params, {google_hit: hitHtml}))
      .toggleClass("kifi-debug", !!response.showScores);

    api.log("[appendResults] done");
  }

  function loadChatter(hits) {
    api.port.emit("get_chatter", {ids: hits.map(function(h) {return h.bookmark.externalId})}, function gotChatter(counts) {
      api.log("[gotChatter]", counts);
      var bgImg = "url(" + api.url("images/chatter.png") + ")";
      for (var id in counts) {
        var n = counts[id];
        if (n[0] || n[1]) {
          $res.find("#kifi-who-" + id).append(
            $("<span class=kifi-chatter>").css("background-image", bgImg).data("n", n));
        }
      }
    });
  }

  function prefetchMore() {
    if (response.mayHaveMore) {
      api.port.emit("get_keeps", {
        "query": response.query,
        "filter": response.filter,
        "lastUUID": response.uuid,
        "context": response.context
      }, function onPrefetchResponse(resp) {
        api.log("[onPrefetchResponse]", resp);
        resp.hits.forEach(processHit);

        response.nextHits = resp.hits;
        response.nextUUID = resp.uuid;
        response.nextContext = resp.context;
        response.mayHaveMore = resp.mayHaveMore;
        if (showMoreOnArrival) {
          showMoreOnArrival = false;
          renderMore();
          prefetchMore();
        }
      });
    }
  }

  function renderMore() {
    var hits = response.nextHits;
    api.log("[renderMore] hits:", hits);
    response.hits.push.apply(response.hits, hits);
    response.uuid = response.nextUUID;
    response.context = response.nextContext;
    delete response.nextHits;
    delete response.nextUUID;
    delete response.nextContext;
    var html = hits.map(function(hit) {
      hit.session = response.session;
      return Mustache.to_html(hitHtml, hit);
    }).join("");
    var $list = $("#kifi-res-list");
    $(html).hide().insertAfter($list.children("li.g").last()).slideDown(200);
    if (!response.mayHaveMore) {
      $list.find(".kifi-res-more").hide(200);
    }
    loadChatter(hits);
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

  function pluralLambda(text, render) {
    text = render(text);
    return text + (text.substr(0, 2) == "1 " ? "" : "s");
  }

  function boldSearchTerms(text, query) {
    return (query.match(/\w+/g) || []).reduce(function(text, term) {
      return text.replace(new RegExp("(\\b" + term + "\\b)", "ig"), "<b>$1</b>");
    }, text);
  }
}();
