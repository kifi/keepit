// @match /^https?:\/\/www\.google\.(com|com\.(a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(|search|webhp)([?#].*)?$/
// @require styles/google_inject.css
// @require styles/friend_card.css
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery-bindhover.js
// @require scripts/lib/mustache.js
// @require scripts/api.js
// @require scripts/render.js

api.log("[google_inject]");

!function() {
  function logEvent() {  // parameters defined in main.js
    api.port.emit("log_event", Array.prototype.slice.call(arguments));
  }

  var $res = $();         // a reference to our search results (kept so that we can reinsert when removed)
  render("html/search/google.html", {}, function(html) {
    $res = $(Mustache.to_html(html)).hide();
    bindHandlers();
  });

  var filter;             // current search filter (null or {[who: "m"|"f"|dot-delimited user ids]?, [when: "t"|"y"|"w"|"m"]?})
  var query = "";         // latest search query
  var response = {};      // latest kifi results received
  var showMoreOnArrival;
  var clicks = {kifi: [], google: []};  // clicked result link hrefs

  // "main" div seems to always stay in the page, so only need to bind listener once.
  // TODO: move this code lower, so it runs after we initiate the first search
  // TODO: also detect result selection via keyboard
  $("#main").on("mousedown", "#search h3.r a", function logSearchEvent() {
    var href = this.href, $li = $(this).closest("li.g");
    var $kifiRes = $("#kifi-res-list"), $kifiLi = $kifiRes.children("li.g");
    var resIdx = $li.parent().children("li.g").index($li);
    var isKifi = $li[0].parentNode === $kifiRes[0];

    clicks[isKifi ? "kifi" : "google"].push(href);

    if (href && resIdx >= 0) {
      logEvent("search", isKifi ? "kifiResultClicked" : "googleResultClicked",
        {"url": href, "whichResult": resIdx, "query": response.query, "experimentId": response.experimentId, "kifiResultsCount": $kifiLi.length});
    }
  });

  var keyTimer, idleTimer, tQuery = +new Date, tGoogleResultsShown = tQuery, tKifiResultsReceived, tKifiResultsShown;
  var $q = $("#gbqfq,#lst-ib").on("input", onInput);  // stable identifier: "Google Bar Query Form Query"
  function onInput() {
    tQuery = +new Date;
    clearTimeout(keyTimer);
    keyTimer = setTimeout(search, 250);  // enough of a delay that we won't search after *every* keystroke (similar to Google's behavior)
  }
  var $qf = $("#gbqf,#tsf").submit(onSubmit);  // stable identifier: "Google Bar Query Form"
  function onSubmit() {
    tQuery = +new Date;
    clearTimeout(keyTimer);
    search();  // immediate search
  }

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
    var hash = location.hash, qs = /[#&]q=/.test(hash) ? hash : location.search;
    var isV = /[?#&]tbm=/.test(qs);
    if (isV !== isVertical) {
      api.log("[checkSearchType] search type:", isV ? "vertical" : "web");
      isVertical = isV;
    }
  }

  function search(fallbackQuery, newFilter) {
    if (isVertical) return;

    var q = (getPrediction() || $q.val() || fallbackQuery || "").trim().replace(/\s+/, " ");  // TODO: also detect "Showing results for" and prefer that
    var f = arguments.length > 1 ? newFilter : filter;
    if (q == query && areSameFilter(f, filter)) {
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
      if (q != query || !areSameFilter(f, filter)) {
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
      response.filter = f;
      response.hits.forEach(processHit);
      if (!newFilter) {
        clicks.kifi.length = clicks.google.length = 0;
      }

      var ires = document.getElementById("ires");
      if (ires) {
        resp.shown = true;
        tKifiResultsShown = +new Date;
      }

      if (resp.hits.length || f) {  // we show a "no results match filter" message if filtering
        appendResults();
        if ($res[0].style.display == "none") {  // check to avoid disrupting transition
          $res.show();
        }
        if ($res[0].nextElementSibling !== ires) {
          $res.insertBefore(ires);
        }
      } else {
        $res.hide();
      }

      logEvent("search", "kifiLoaded", {"query": q, "filter": f, "queryUUID": resp.uuid});
      if (resp.hits.length) {
        logEvent("search", "kifiAtLeastOneResult", {"query": q, "filter": f, "queryUUID": resp.uuid, "experimentId": resp.experimentId});
        var onShow = function(hits) {
          resp.expanded = true;
          loadChatter(hits);
          prefetchMore();
        }.bind(null, resp.hits.slice());
        if (resp.show) {
          onShow();
        } else {
          $res.data("onShow", onShow);
        }
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

  $(window).on("hashchange", function() {
    api.log("[hashchange]");
    checkSearchType();
    search();  // needed for switch from shopping to web search, for example
  }).on("beforeunload", function(e) {
    if (response.query === query && new Date - tKifiResultsShown > 2000) {
      logEvent("search", "searchUnload", {
        "query": response.query,
        "queryUUID": response.uuid,
        "kifiResultsClicked": clicks.kifi.length,
        "googleResultsClicked": clicks.google.length,
        "kifiShownURIs": response.expanded ? response.hits.map(function(hit) {return hit.bookmark.url}) : [],
        "kifiClickedURIs": clicks.kifi,
        "googleClickedURIs": clicks.google});
    }
  });

  var MutationObserver = window.MutationObserver || window.WebKitMutationObserver || window.MozMutationObserver;
  var observer = new MutationObserver(function onMutation(mutations) {
    if (isVertical) return;
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
    if (!$q.length || !document.contains($q[0])) {  // for #lst-ib (e.g. google.co.il)
      $q.remove(); $qf.remove();
      $q = $($q.selector).on("input", onInput);
      $qf = $($qf.selector).submit(onSubmit);
    }
  });
  observer.observe(document.getElementById("main"), {childList: true, subtree: true});  // TODO: optimize away subtree

  api.onEnd.push(function() {
    api.log("[google_inject:onEnd]");
    $(window).off("hashchange unload");
    observer.disconnect();
    $q.off("input");
    $qf.off("submit");
    clearTimeout(idleTimer);
    $res.remove();
    $res.length = 0;
  });

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
      url = url.substr(0, 60) + "...";
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
        $(this).hide(200, function() {
          $(this).closest(".kifi-res-end").empty();
        });
      }
    }).on("click", ".kifi-res-title", function() {
      $res.find("#kifi-res-list,.kifi-res-end").toggle(200);
      $res.find(".kifi-filter-btn").fadeToggle(200);
      $res.find(".kifi-filters-x:visible").click();
      $(this).toggleClass("kifi-collapsed");
      var onShow = $res.data("onShow");
      if (onShow) {
        $res.removeData("onShow");
        onShow();
      }
    }).on("click", ".kifi-filter-btn", function() {
      var $f = $res.find(".kifi-filters");
      if ($f.is(":animated")) return;
      if ($f.is(":visible")) {
        $f.find(".kifi-filter-val[data-val=a]").trigger("mouseup");
        $(this).removeClass("kifi-expanded");
        $f.slideUp(150);
      } else {
        $(this).addClass("kifi-expanded");
        $f.slideDown(150);
      }
    }).on("click", ".kifi-filters-clear", function() {
      search(null, null);
      $res.find(".kifi-filter-val[data-val=a]").trigger("mouseup", [true]);
    }).on("click", ".kifi-filters-x", function() {
      $res.find(".kifi-filter-btn").click();
    }).on("mousedown", ".kifi-filter-a", function(e) {
      if (e.which != 1) return;
      e.preventDefault();
      var $a = $(this).addClass("kifi-active");
      var $menu = $a.next("menu").fadeIn(50)
        .on("mouseenter.kifi", ".kifi-filter-val", function() { $(this).addClass("kifi-hover"); })
        .on("mouseleave.kifi", ".kifi-filter-val", function() { $(this).removeClass("kifi-hover"); })
        .on("kifi:hide", hide);
      document.addEventListener("mousedown", docMouseDown, true);
      function docMouseDown(e) {
        if (!$menu[0].contains(e.target)) {
          hide(true);
          if ($a[0] === e.target) {
            e.stopPropagation();
          }
        }
      }
      function hide(fast) {
        document.removeEventListener("mousedown", docMouseDown, true);
        $a.removeClass("kifi-active");
        $menu.off(".kifi", ".kifi-filter-val");
        $menu.off("kifi:hide", hide).delay(fast === true ? 0 : 100).fadeOut(50, function() {
          $menu.find(".kifi-hover").removeClass("kifi-hover");
        });
      }
    }).on("mouseup", ".kifi-filter-val", function(e, alreadySearched) {
      var $v = $(this), $menu = $v.parent(), $f = $menu.parent();
      $menu.filter(":visible").triggerHandler("kifi:hide");

      var name = $f.data("name"), val = $v.data("val");
      $v.siblings(".kifi-selected").removeClass("kifi-selected").end().addClass("kifi-selected");
      $f.toggleClass("kifi-applied", val != "a").find(".kifi-filter-a")[0].firstChild.nodeValue = $v.text();

      if (!alreadySearched) {
        var f = $.extend({}, filter);
        if (val != "a") {
          f[name] = val;
        } else {
          delete f[name];
        }
        search(null, Object.keys(f).length ? f : null);
      }
      if (name == "who") {
        var $det = $res.find(".kifi-filter-detail");
        if (val != "f") {
          $det.filter(".kifi-visible").each(hideFilterDetail);
        } else {
          $det.filter(":not(.kifi-visible)").each(showFilterDetail);
        }
      }
    }).on("click", ".kifi-filter-detail-clear", function() {
      search(null, $.extend({}, filter, {who: "f"}));
      $res.find("#kifi-filter-det").tokenInput("clear");
    }).on("click", ".kifi-filter-detail-x", function() {
      search(null, $.extend({}, filter, {who: "f"}));
      $(this).closest(".kifi-filter-detail").each(hideFilterDetail);
    }).on("click", ".kifi-res-debug", function(e) {
      e.stopPropagation();
      location.href = response.admBaseUri + "/admin/search/results/" + response.uuid;
    }).bindHover(".kifi-face.kifi-friend", function(configureHover) {
      var $a = $(this);
      var i = $a.closest("li.g").prevAll("li.g").length;
      var j = $a.prevAll(".kifi-friend").length;
      var friend = response.hits[i].users[j];
      render("html/friend_card.html", {
        networkIds: friend.networkIds,
        name: friend.firstName + " " + friend.lastName,
        id: friend.id,
        iconsUrl: api.url("images/social_icons.png")
      }, function(html) {
        configureHover(html, {canLeaveFor: 600, hideAfter: 4000, click: "toggle"});
      });
    }).bindHover(".kifi-res-friends", function(configureHover) {
      var $a = $(this), i = $a.closest("li.g").prevAll("li.g").length;
      render("html/search/friends.html", {friends: response.hits[i].users}, function(html) {
        configureHover(html, {
          click: "toggle",
          position: function(w) {
            this.style.left = ($a[0].offsetWidth - w) / 2 + "px";
          }});
      });
    }).bindHover(".kifi-chatter", function(configureHover) {
      var n = $(this).data("n");
      render("html/search/chatter.html", {
        numComments: n[0],
        numMessages: n[1],
        pluralize: function() {return pluralLambda}
      }, function(html) {
        configureHover(html, {canLeaveFor: 600, click: "toggle"});
      });
    }).on("click", ".kifi-chatter-deeplink", function() {
      api.port.emit("add_deep_link_listener", $(this).data("locator"));
      location.href = $(this).closest("li.g").find("h3.r a")[0].href;
    });
  }

  function appendResults() {
    $res.find(".kifi-res-title").toggleClass("kifi-collapsed", !response.show);
    $res.find(".kifi-filter-btn")[response.show ? "show" : "hide"]();
    render("html/search/google_hits.html", {
        show: response.show,
        results: response.hits,
        anyResults: response.hits.length > 0,
        session: response.session,
        endBgUrl: api.url("images/shade_above.png"),
        globeUrl: api.url("images/globe.png"),
        mayHaveMore: response.mayHaveMore
      }, {
        google_hit: "google_hit.html"
      }, function(html) {
        $res.append(html).toggleClass("kifi-debug", response.session.experiments.indexOf("admin") >= 0);
        api.log("[appendResults] done");
      });
  }

  function loadChatter(hits) {
    if (!hits.length) return;
    api.port.emit("get_chatter", {ids: hits.map(function(h) {return h.bookmark.id})}, function gotChatter(counts) {
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
      var origResp = response;
      api.port.emit("get_keeps", {
        "query": response.query,
        "filter": response.filter,
        "lastUUID": response.uuid,
        "context": response.context
      }, function onPrefetchResponse(resp) {
        if (response === origResp) {
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

    var hitHtml = [];
    for (var i = 0; i < hits.length; i++) {
      render("html/search/google_hit.html",
        $.extend({session: response.session, globeUrl: api.url("images/globe.png")}, hits[i]),
        function(html) {
          hitHtml.push(html);
          if (hitHtml.length == hits.length) {
            var $list = $("#kifi-res-list");
            $(hitHtml.join("")).hide().insertAfter($list.children("li.g").last()).slideDown(200, function() {
              $(this).css("overflow", "");  // slideDown clean-up
            });
            if (!response.mayHaveMore) {
              $list.find(".kifi-res-more").hide(200);
            }
            loadChatter(hits);
          }
        });
    }
  }

  function processHit(hit) {
    hit.displayUrl = displayURLFormatter(hit.bookmark.url);
    hit.displayTitle = boldSearchTerms(hit.bookmark.title || "", response.query) || hit.displayUrl;
    hit.displayScore = response.showScores === true ? "[" + Math.round(hit.score * 100) / 100 + "] " : "";

    var who = response.filter && response.filter.who || "", ids = who.length > 1 ? who.split(".") : null;
    hit.displaySelf = who != "f" && !ids && hit.isMyBookmark;
    hit.displayUsers = who == "m" ? [] :
      ids ? hit.users.filter(function(u) {return ~ids.indexOf(u.id)}) :
      hit.users;

    var numOthers = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
    hit.whoKeptHtml = formatCountHtml(
      hit.isMyBookmark,
      hit.isPrivate ? " <span class=kifi-res-private>Private</span>" : "",
      hit.users.length ? "<a class=kifi-res-friends href=javascript:>" + plural(hit.users.length, "friend") + "</a>" : "",
      numOthers ? plural(numOthers, "other") : "");
  }

  function formatCountHtml(kept, priv, friends, others) {
    return kept && !friends && !others ?
      "You kept this" + priv :
      [kept ? "You" + priv : "", friends, others]
        .filter(function(v) {return v})
        .join(" + ") + " kept this";
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

  function areSameFilter(f1, f2) {
    return f1 === f2 || !f1 && !f2 || f1 && f2 && f1.who == f2.who && f1.when == f2.when;
  }

  function showFilterDetail($det) {
    $(this).prev(".kifi-filter-detail-notch").addBack().addClass("kifi-visible").end().end()
    .off("transitionend").on("transitionend", function end(e) {
      if (e.target !== this || e.originalEvent.propertyName != "height") return;
      $(this).off("transitionend", end);
      var $in = $res.find("#kifi-filter-det");
      api.port.emit("get_friends", function(friends) {
        api.log("friends:", friends);
        for (var i in friends) {
          var f = friends[i];
          f.name = f.firstName + " " + f.lastName;
        }
        api.require("scripts/lib/jquery-tokeninput.js", function() {
          if ($in.prev("ul").length) return;
          $in.tokenInput(friends, {
            searchDelay: 0,
            minChars: 1,
            placeholder: $in.prop("placeholder"),
            hintText: "",
            noResultsText: "",
            searchingText: "",
            animateDropdown: false,
            preventDuplicates: true,
            allowTabOut: true,
            tokenValue: "id",
            theme: "googly",
            resultsFormatter: function(f) {
              return "<li style='background-image:url(//" + cdnBase + "/users/" + f.id + "/pics/100/0.jpg)'>" +
                Mustache.escape(f.name) + "</li>";
            },
            tokenFormatter: function(f) {
              return"<li style='background-image:url(//" + cdnBase + "/users/" + f.id + "/pics/100/0.jpg)'><p>" +
                Mustache.escape(f.name) + "</p></li>";
            },
            onReady: function() {
              $("#token-input-kifi-filter-det").focus();
            },
            onAdd: function(friend) {
              api.log("[onAdd]", friend.id, friend.name);
              var who = filter.who.length > 1 ? filter.who + "." + friend.id : friend.id;
              search(null, $.extend({}, filter, {who: who}));
              $in.nextAll(".kifi-filter-detail-clear").addClass("kifi-visible");
            },
            onDelete: function(friend) {
              api.log("[onDelete]", friend.id, friend.name);
              var who = filter.who.split(".").filter(function(id) {return id != friend.id}).join(".") || "f";
              search(null, $.extend({}, filter, {who: who}));
              if (who == "f") {
                $in.nextAll(".kifi-filter-detail-clear").removeClass("kifi-visible");
              }
            }});
        });
      });
    });
  }

  function hideFilterDetail() {
    $(this).prev(".kifi-filter-detail-notch").addBack().removeClass("kifi-visible").end().end()
    .off("transitionend").on("transitionend", function end(e) {
      if (e.target !== this || e.originalEvent.propertyName != "height") return;
      $(this).off("transitionend", end);
      var $in = $res.find("#kifi-filter-det");
      if ($in.tokenInput) {
        $in.tokenInput("destroy");
      }
    });
  }
}();
