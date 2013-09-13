// @match /^https?:\/\/www\.google\.(com|com\.(a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(|search|webhp)([?#].*)?$/
// @asap
// @require styles/google_inject.css
// @require styles/friend_card.css
// @require scripts/api.js
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery-bindhover.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/search/google.js
// @require scripts/html/search/google_hits.js
// @require scripts/html/search/google_hit.js

// (same as match pattern above)
const searchUrlRe = /^https?:\/\/www\.google\.(com|com\.(a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(|search|webhp)([?#].*)?$/;

$.fn.layout = function() {
  return this.each(function() {this.clientHeight});  // forces layout
};

// We check the pattern because Chrome match/glob patterns aren't powerful enough. crbug.com/289057
if (searchUrlRe.test(document.URL)) !function() {
  api.log("[google_inject]");

  function logEvent() {  // parameters defined in main.js
    api.port.emit("log_event", Array.prototype.slice.call(arguments));
  }

  var $res = $(render("html/search/google", {images: api.url("images")}));   // a reference to our search results (kept so that we can reinsert when removed)
  var $status = $res.find(".kifi-res-status");
  attachKifiRes();

  var filter;             // current search filter (null or {[who: "m"|"f"|dot-delimited user ids]?, [when: "t"|"y"|"w"|"m"]?})
  var query = "";         // latest search query
  var response = {};      // latest kifi results received
  var showMoreOnArrival;
  var clicks = {kifi: [], google: []};  // clicked result link hrefs
  var tQuery, tGoogleResultsShown, tKifiResultsReceived, tKifiResultsShown, reportTimingTimer;  // for timing stats

  var $q = $(), $qf = $q, $qp = $q, keyTimer;
  $(function() {
    $q = $("#gbqfq,#lst-ib").on("input", onInput);  // stable identifier: "Google Bar Query Form Query"
    $qf = $("#gbqf,#tsf").submit(onSubmit);  // stable identifiers: "Google Bar Query Form", "Top Search Form"
    $qp = $("#gs_taif0");  // stable identifier: "Google Search Type-Ahead Input Field"
  });
  function onInput() {
    clearTimeout(keyTimer);
    keyTimer = setTimeout(search, 250);  // enough of a delay that we won't search after *every* keystroke (similar to Google's behavior)
  }
  function onSubmit() {
    clearTimeout(keyTimer);
    search();
  }

  checkSearchType();
  search(parseQuery(location.hash || location.search), null, true);  // Google can be slow to initialize the input field, or it may be missing
  if (document.getElementById('ires')) {
    tGoogleResultsShown = tQuery;
  }

  var isVertical;
  function checkSearchType() {
    var hash = location.hash, qs = /[#&]q=/.test(hash) ? hash : location.search;
    var isV = /[?#&]tbm=/.test(qs);
    if (isV !== isVertical) {
      api.log("[checkSearchType] search type:", isV ? "vertical" : "web");
      isVertical = isV;
    }
  }

  function search(fallbackQuery, newFilter, isFirst) {
    if (isVertical) return;

    var q = ($qp.val() || $q.val() || fallbackQuery || "").trim().replace(/\s+/g, " ");  // TODO: also detect "Showing results for" and prefer that
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

    $status.removeAttr("data-n").removeClass("kifi-promote").parent().addClass("kifi-loading");
    $res.find("#kifi-res-list,.kifi-res-end").css("opacity", .2).slideUp(200);

    clearTimeout(reportTimingTimer);
    if (newFilter == null) {
      reportTimingTimer = setTimeout(reportTiming, 1200);
    }
    var t1 = tQuery = Date.now();
    api.port.emit("get_keeps", {query: q, filter: f, first: isFirst}, function results(resp) {
      if (q != query || !areSameFilter(f, filter)) {
        api.log("[results] ignoring for query:", q, "filter:", f);
        return;
      } else if (!resp.session) {
        api.log("[results] no user info");
        return;
      }

      var now = tKifiResultsReceived = Date.now();
      api.log("[results] response after", now - t1, "ms:", resp);
      if (!newFilter) {
        clicks.kifi.length = clicks.google.length = 0;
      }

      response = resp;
      if (isFirst && resp.filter) {  // restoring previous filter (user navigated back)
        filter = f = newFilter = resp.filter;
        $res.find(".kifi-filter-btn").addClass("kifi-expanded");
        $res.find(".kifi-filters").show().each(function() {
          if (f.who) {
            $(this).find(".kifi-filter[data-name=who]").find(".kifi-filter-val")
            .filter("[data-val=" + (f.who.length > 1 ? "f" : f.who) + "]").trigger("mouseup", [true]);
          }
          if (f.when) {
            $(this).find(".kifi-filter[data-name=when]").find(".kifi-filter-val")
            .filter("[data-val=" + f.when + "]").trigger("mouseup", [true]);
          }
        });
      }

      var inDoc = document.contains($res[0]);
      var expanded = Boolean(resp.show && (!inDoc || !(tGoogleResultsShown >= tQuery) || f));
      api.log('[results] tQuery:', tQuery % 10000, 'tGoogleResultsShown:', tGoogleResultsShown % 10000, 'diff:', tGoogleResultsShown - tQuery, 'show:', resp.show, 'inDoc:', inDoc);
      resp.hits.forEach(processHit);
      appendResults(expanded);
      if (inDoc) {
        tKifiResultsShown = Date.now();
      }

      var onShow = function(hits) {
        $status.one("transitionend", hideStatus);
        resp.expanded = true;
        loadChatter(hits);
        prefetchMore();
      }.bind(null, resp.hits.slice());
      if (expanded) {
        onShow();
      } else {
        $res.data("onShow", onShow);
        $status.attr("data-n", resp.hits.length).addClass(resp.show ? "kifi-promote" : "").layout();
      }
      $status.parent().removeClass("kifi-loading");

      logEvent("search", "kifiLoaded", {"query": q, "filter": f, "queryUUID": resp.uuid});
      if (resp.hits.length) {
        logEvent("search", "kifiAtLeastOneResult", {"query": q, "filter": f, "queryUUID": resp.uuid, "experimentId": resp.experimentId});
      }
    });
  }

  function hideStatus() {
    if ($status.attr("data-n") != "0") {
      $status.removeAttr("data-n").removeClass("kifi-promote");
    }
  }

  function parseQuery(hash) {
    var m = /[?#&]q=[^&]*/.exec(hash);
    return m && unescape(m[0].substr(3).replace(/\+/g, " ")).trim() || "";
  }

  function reportTiming() {
    var kR = tKifiResultsReceived - tQuery;
    var kS = tKifiResultsShown - tQuery;
    var gS = tGoogleResultsShown - tQuery;
    logEvent("search", "dustSettled", {
      "query": query,
      "experimentId": response.experimentId,
      "kifiHadResults": response.hits && response.hits.length > 0,
      "kifiReceivedAt": kR >= 0 ? kR : null,
      "kifiShownAt": kR >= 0 && kS >= 0 ? kS : null,
      "googleShownAt": gS >= 0 ? gS : null});
  }

  $(window).on("hashchange", function() {
    api.log("[hashchange]");
    checkSearchType();
    search();  // needed for switch from shopping to web search, for example
  }).on("beforeunload", function(e) {
    if (response.query === query && Date.now() - tKifiResultsShown > 2000) {
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

  var observer = new MutationObserver(withMutations);
  function withMutations(mutations) {
    if (isVertical) return;
    outer:
    for (var i = 0; i < mutations.length; i++) {
      for (var j = 0, nodes = mutations[i].addedNodes; j < nodes.length; j++) {
        if (nodes[j].id === "ires") {
          api.log("[withMutations] Google results inserted");
          tGoogleResultsShown = Date.now();
          if (attachKifiRes(nodes[j]) && !(tKifiResultsShown >= tKifiResultsReceived)) {
            tKifiResultsShown = tGoogleResultsShown;
          }
          if (document.readyState != 'loading') {  // avoid searching for input value if not yet updated to URL hash
            $(setTimeout.bind(null, search));  // prediction may have changed
          }
          break outer;
        }
      }
    }
    if (!$q.length || !document.contains($q[0])) {  // for #lst-ib (e.g. google.co.il)
      $q.remove(), $qf.remove();
      $q = $($q.selector).on("input", onInput);
      $qf = $($qf.selector).submit(onSubmit);
    }
  }
  var whatToObserve = {childList: true, subtree: true};  // TODO: optimize away subtree
  observer.observe(document, whatToObserve);
  $(function() {
    withMutations(observer.takeRecords());
    observer.disconnect();
    observer.observe(document.getElementById('main'), whatToObserve);
  });

  function attachKifiRes(ires) {
    if ((ires = ires || document.getElementById('ires'))) {
      if ($res[0].nextElementSibling !== ires) {
        $res.insertBefore(ires);
        if (bindResHandlers) {
          setTimeout(bindResHandlers);
          bindResHandlers = null;
        }
      }
      return true;
    }
  }

  if (!api.mutationsFirePromptly) {
    setTimeout(function tryAttach() {
      attachKifiRes() || setTimeout(tryAttach, 1);
    }, 1);
  }

  // TODO: also detect result selection via keyboard
  $(document).on("mousedown", "#search h3.r a", function logSearchEvent() {
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

  api.onEnd.push(function() {
    api.log("[google_inject:onEnd]");
    $(window).off("hashchange unload");
    observer.disconnect();
    $q.off("input");
    $qf.off("submit");
    clearTimeout(reportTimingTimer);
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

  function displayURLFormatter(url, matches) {
    for (var i = 0; i < urlAutoFormatters.length; i++) {
      if (urlAutoFormatters[i].match.test(url)) {
        var iconUrl = api.url("images/results/" + urlAutoFormatters[i].icon);
        return "<span class=formatted_site style='background:url(" + iconUrl + ") no-repeat;background-size:15px'></span>" +
          urlAutoFormatters[i].template;
      }
    }
    var prefix = /^https?:\/\//;
    var prefixLen = (url.match(prefix) || [])[0].length || 0;
    url = url.replace(prefix, '');
    url = url.length > 64 ? url.substr(0, 60) + "..." : url;
    matches = (matches || []).map(function (m) { return [m[0] - prefixLen, m[1]]; });
    return boldSearchTerms(url, matches);
  }

  var bindResHandlers = function() {
    $res.on("mouseover", ".kifi-res-more-a", function() {
      $(this).closest(".kifi-res-more").addClass("kifi-over");
    }).on("mouseout", ".kifi-res-more-a", function() {
      $(this).closest(".kifi-res-more").removeClass("kifi-over");
    }).on("click", ".kifi-res-more-a", function(e) {
      if (e.which > 1) return;
      api.log("[moreClick] shown:", response.hits.length, "avail:", response.nextHits);
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
    }).on("click", ".kifi-res-title:not(.kifi-loading)", function(e) {
      if (e.shiftKey && response.session && ~response.session.experiments.indexOf("admin")) {
        location.href = response.admBaseUri + "/admin/search/results/" + response.uuid;
        return;
      }
      $res.find("#kifi-res-list,.kifi-res-end").slideToggle(200);
      if (response.hits.length) {
        $res.find(".kifi-filter-btn").fadeToggle(200);
        $res.find(".kifi-filters-x:visible").click();
      }
      this.classList.toggle("kifi-collapsed");
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
      if (e.which > 1) return;
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
      if (e.which > 1) return;
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
    }).bindHover(".kifi-face.kifi-friend", function(configureHover) {
      var $a = $(this);
      var i = $a.closest("li.g").prevAll("li.g").length;
      var j = $a.prevAll(".kifi-friend").length;
      var friend = response.hits[i].users[j];
      render("html/friend_card", {
        name: friend.firstName + " " + friend.lastName,
        id: friend.id,
        iconsUrl: api.url("images/social_icons.png")
      }, function(html) {
        var $el = $(html);
        configureHover($el, {canLeaveFor: 600, hideAfter: 4000, click: "toggle"});
        api.port.emit("get_networks", friend.id, function(networks) {
          for (nw in networks) {
            $el.find('.kifi-kcard-nw-' + nw)
              .toggleClass('kifi-on', networks[nw].connected)
              .attr('href', networks[nw].profileUrl || null);
          }
        });
      });
    }).bindHover(".kifi-res-friends", function(configureHover) {
      var $a = $(this), i = $a.closest("li.g").prevAll("li.g").length;
      render("html/search/friends", {friends: response.hits[i].users}, function(html) {
        configureHover(html, {
          click: "toggle",
          position: function(w) {
            this.style.left = ($a[0].offsetWidth - w) / 2 + "px";
          }});
      });
    }).bindHover(".kifi-chatter", function(configureHover) {
      render("html/search/chatter", {
        numMessages: $(this).data("n"),
        locator: $(this).data("locator"),
        pluralize: function() {return pluralLambda}
      }, function(html) {
        configureHover(html, {canLeaveFor: 600, click: "toggle"});
      });
    }).on("click", ".kifi-chatter-deeplink", function() {
      api.port.emit("add_deep_link_listener", $(this).data("locator"));
      location.href = $(this).closest("li.g").find("h3.r a")[0].href;
    });
  }

  function appendResults(expanded) {
    $res.find(".kifi-res-title").toggleClass("kifi-collapsed", !expanded);
    $res.find(".kifi-filter-btn")[0].style.display = expanded ? "block" : "none";
    $res.find("#kifi-res-list,.kifi-res-end").remove();
    $res.append(render("html/search/google_hits", {
        show: !!expanded,
        results: response.hits,
        anyResults: response.hits.length > 0,
        session: response.session,
        images: api.url("images"),
        filter: response.filter,
        mayHaveMore: response.mayHaveMore},
      {google_hit: "google_hit"}));
    api.log("[appendResults] done");
  }

  function loadChatter(hits) {
    if (!hits.length) return;
    api.port.emit("get_chatter", hits.map(function(h) {return h.bookmark.url}), function gotChatter(chatter) {
      api.log("[gotChatter]", chatter);
      var bgImg = "url(" + api.url("images/chatter.png") + ")";
      for (var url in chatter) {
        var o = chatter[url];
        if (o && o.threads) {
          $res.find(".kifi-who[data-url='" + url + "']").append(
            $("<span class=kifi-chatter>")
            .css("background-image", bgImg)
            .data({n: o.threads, locator: "/messages" + (o.threadId ? "/" + o.threadId : "")}));
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
      hitHtml.push(render("html/search/google_hit", $.extend({session: response.session, images: api.url("images")}, hits[i])));
    }
    var $list = $("#kifi-res-list");
    $(hitHtml.join("")).hide().insertAfter($list.children("li.g").last()).slideDown(200, function() {
      $(this).css("overflow", "");  // slideDown clean-up
    });
    if (!response.mayHaveMore) {
      $list.find(".kifi-res-more").hide(200);
    }
    loadChatter(hits);
  }

  function processHit(hit) {
    var friendsToShow = 8;

    hit.displayUrl = displayURLFormatter(hit.bookmark.url, (hit.bookmark.matches || {}).url);
    hit.displayTitle = boldSearchTerms(hit.bookmark.title, (hit.bookmark.matches || {}).title) || hit.displayUrl;
    hit.displayScore = response.showScores === true ? "[" + Math.round(hit.score * 100) / 100 + "] " : "";

    var who = response.filter && response.filter.who || "", ids = who.length > 1 ? who.split(".") : null;
    hit.displaySelf = who != "f" && !ids && hit.isMyBookmark;
    hit.displayUsers = who == "m" ? [] :
      (ids ? hit.users.filter(function(u) {return ~ids.indexOf(u.id)}) : hit.users).slice(0, friendsToShow);

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

  function boldSearchTerms(text, matches) {
    return (matches || []).reduceRight(function (text, match) {
      var start = match[0], len = match[1];
      return text.substr(0, start) + '<b>' + text.substr(start, len) + '</b>' + text.substr(start + len);
    }, text || "");
  }

  function areSameFilter(f1, f2) {
    return f1 === f2 || !f1 && !f2 || f1 && f2 && f1.who == f2.who && f1.when == f2.when;
  }

  function showFilterDetail() {
    var $fd = $(this);
    if ($res.is(':visible')) {
      $fd.off("transitionend").on("transitionend", function end(e) {
        if (e.target === this && e.originalEvent.propertyName === "opacity") {
          $fd.off("transitionend", end);
          prepFriendsTokenInput();
        }
      });
    } else {
      prepFriendsTokenInput();
    }
    $fd.prev(".kifi-filter-detail-notch").addBack().addClass("kifi-visible");

    function prepFriendsTokenInput() {
      var $in = $res.find("#kifi-filter-det");
      api.port.emit("get_friends", function(friends) {
        api.log("friends:", friends);
        for (var i in friends) {
          var f = friends[i];
          f.name = f.firstName + " " + f.lastName;
        }
        api.require("scripts/lib/jquery-tokeninput.js", function() {
          if ($in.prev("ul").length) return;
          var addTime, ids = filter && filter.who && filter.who.length > 1 ? filter.who.split(".") : null;
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
            prePopulate: ids && friends.filter(function(f) {return ids.indexOf(f.id) >= 0}),
            resultsFormatter: function(f) {
              return "<li style='background-image:url(//" + cdnBase + "/users/" + f.id + "/pics/100/0.jpg)'>" +
                Mustache.escape(f.name) + "</li>";
            },
            tokenFormatter: function(f) {
              return"<li style='background-image:url(//" + cdnBase + "/users/" + f.id + "/pics/100/0.jpg)'><p>" +
                Mustache.escape(f.name) + "</p></li>";
            },
            onReady: function() {
              $("#token-input-kifi-filter-det").focus().blur(function(e) {
                if (!e.relatedTarget && new Date - addTime < 50) {
                  setTimeout(this.focus.bind(this));  // restore focus (stolen by Google script after add)
                };
              });
            },
            onAdd: function(friend) {
              api.log("[onAdd]", friend.id, friend.name);
              var who = filter.who.length > 1 ? filter.who + "." + friend.id : friend.id;
              search(null, $.extend({}, filter, {who: who}));
              $in.nextAll(".kifi-filter-detail-clear").addClass("kifi-visible");
              addTime = Date.now();
            },
            onDelete: function(friend) {
              api.log("[onDelete]", friend.id, friend.name);
              var who = filter.who.split(".").filter(function(id) {return id != friend.id}).join(".") || "f";
              search(null, $.extend({}, filter, {who: who}));
              if (who == "f") {
                $in.nextAll(".kifi-filter-detail-clear").removeClass("kifi-visible");
              }
            }});
          if (ids) {
            $in.nextAll(".kifi-filter-detail-clear").addClass("kifi-visible");
          }
        });
      });
    }
  }

  function hideFilterDetail() {
    $(this).off("transitionend").on("transitionend", function end(e) {
      if (e.target !== this || e.originalEvent.propertyName != "visibility") return;
      $(this).off("transitionend", end);
      var $in = $res.find("#kifi-filter-det");
      if ($in.tokenInput) {
        $in.tokenInput("destroy");
      }
    }).prev(".kifi-filter-detail-notch").addBack().removeClass("kifi-visible");
  }
}();
