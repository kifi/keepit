// @match /^https?:\/\/www\.google\.(?:com|com\.(?:a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(?:ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(?:|search|webhp)(?:[?#].*)?$/
// @asap
// @require styles/google_inject.css
// @require styles/friend_card.css
// @require scripts/api.js
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/render.js
// @require scripts/title_from_url.js
// @require scripts/html/search/google.js
// @require scripts/html/search/google_hits.js
// @require scripts/html/search/google_hit.js

// (same as match pattern above)
var searchUrlRe = /^https?:\/\/www\.google\.(?:com|com\.(?:a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[by]|m[txy]|n[afgip]|om|p[aehkry]|qa|s[abglv]|t[jrw]|u[ay]|v[cn])|co\.(?:ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[demstz]|b[aefgijsy]|cat|c[adfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aeglmpry]|h[nrtu]|i[emqst]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(?:|search|webhp)(?:[?#].*)?$/;
var pageSession = Math.random().toString(16).slice(2);

$.fn.layout = function () {
  return this.each(function () {this.clientHeight});  // forces layout
};

// We check the pattern because Chrome match/glob patterns aren't powerful enough. crbug.com/289057
if (searchUrlRe.test(document.URL)) !function () {
  'use strict';
  log('[google_inject]');

  var origin = location.origin;
  var $res = $(render('html/search/google', {images: api.url('images')}));   // a reference to our search results (kept so that we can reinsert when removed)
  var $bar = $res.find('.kifi-res-bar');
  var $kifi = $res.find('.kifi-res-bar-kifi');
  var $status = $bar.find('.kifi-res-bar-status');
  var $arrow = $res.find('.kifi-res-arrow');
  attachKifiRes();

  var filter;             // current search filter (null or {who: 'a'|'m'|'f'})
  var query = '';         // latest search query
  var response = {};      // latest kifi results received
  var refinements = -1;   // how many times the user has refined the search on the same page. No searches at all yet.
  var showMoreOnArrival;
  var clicks = {kifi: [], google: []};  // clicked result link hrefs
  var tQuery, tGoogleResultsShown, tKifiResultsReceived, tKifiResultsShown;  // for timing stats

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
  search(true, null, true);  // Google can be slow to initialize the input field, or it may be missing
  if (document.getElementById('ires')) {
    tGoogleResultsShown = tQuery;
  }

  var isVertical;
  function checkSearchType() {
    var hash = location.hash, qs = /[#&]q=/.test(hash) ? hash : location.search;
    var isV = /[?#&]tbm=/.test(qs);
    if (isV !== isVertical) {
      log("[checkSearchType] search type:", isV ? "vertical" : "web");
      isVertical = isV;
    }
  }

  //endedWith is either "unload" or "refinement"
  function sendSearchedEvent(endedWith) {
    api.port.emit("log_search_event", [
      "searched",
      {
        "origin": origin,
        "guided": "guide" in window,
        "uuid": response.uuid,
        "experimentId": response.experimentId,
        "query": response.query,
        "filter": filter,
        "maxResults": response.prefs.maxResults,
        "kifiResults": response.hits.length,
        "kifiExpanded": response.expanded || false,
        "kifiTime": tKifiResultsReceived - tQuery,
        "kifiShownTime": tKifiResultsShown - tQuery,
        "thirdPartyShownTime": tGoogleResultsShown - tQuery,
        "kifiResultsClicked": clicks.kifi.length,
        "thirdPartyResultsClicked": clicks.google.length,
        "refinements": refinements,
        "pageSession": pageSession,
        "endedWith": endedWith
      }
    ]);
  }

  function search(useLocation, newFilter, isFirst) {
    if (isVertical) return;

    var q = ($qp.val() || $q.val() || useLocation && (parseQ(location.hash) || parseQ(location.search)) || '').trim().replace(/\s+/g, ' ');  // TODO: also detect "Showing results for" and prefer that
    if (q === query && areSameFilter(newFilter, filter)) {
      log("[search] nothing new, query:", q, "filter:", newFilter);
      if (isFirst) {
        document.addEventListener('DOMContentLoaded', search.bind(null, false, false, true));
      }
      return;
    }
    if (response) {
      try {
        sendSearchedEvent("refinement");
      } catch (e) {}
    }
    if (!q) {
      log('[search] empty query');
      return;
    }
    query = q;
    filter = newFilter;

    log("[search] query:", q, "filter:", newFilter);

    if (!newFilter) {
      if (!isFirst) {
        collapseResults();
        $res.find('.kifi-filter').removeAttr('data-n').attr('href', 'javascript:')
          .filter('.kifi-filter-all').removeAttr('href data-top');
      }
      $bar.addClass('kifi-loading');
    }
    $status.removeAttr('href data-n data-of');
    $arrow.removeAttr('href');
    $res.find('#kifi-res-list,.kifi-res-end').css('opacity', .2);

    tKifiResultsReceived = null;
    tKifiResultsShown = null;
    var t1 = tQuery = Date.now();
    refinements++;
    api.port.emit("get_keeps", {query: q, filter: newFilter, first: isFirst, whence: 'i'}, function results(resp) {
      if (q !== query || !areSameFilter(newFilter, filter)) {
        log("[results] ignoring for query:", q, "filter:", newFilter);
        return;
      } else if (!resp.me) {
        log("[results] no user info");
        $res.hide();
        return;
      }

      var now = tKifiResultsReceived = Date.now();
      log('[results] took', now - t1, 'ms');
      if (!newFilter) {
        clicks.kifi.length = clicks.google.length = 0;
      }
      removeResults();

      response = resp;
      // if (isFirst && resp.filter && resp.filter.who) {  // restoring previous filter (user navigated back) // TODO: make this work again
      //   filter = newFilter = resp.filter;
      //   $bar.removeClass('kifi-collapsed kifi-preview');
      //   $res.find('.kifi-filter[data-val=' + newFilter.who + ']').trigger('click', [true]);
      // }

      var inDoc = document.contains($res[0]);
      var showAny = Boolean(resp.show && resp.hits.length && (resp.prefs.maxResults && !(inDoc && tGoogleResultsShown >= tQuery) || resp.context === 'guide') || newFilter);
      var showPreview = Boolean(showAny && !newFilter);
      log('[results] tQuery:', tQuery % 10000, 'tGoogleResultsShown:', tGoogleResultsShown % 10000, 'diff:', tGoogleResultsShown - tQuery, 'show:', resp.show, 'inDoc:', inDoc);
      unpack(resp);
      if (!resp.hits.length) resp.mayHaveMore = false;

      if (!newFilter || newFilter.who === 'a') {
        var numTop = resp.numTop = resp.show ? resp.hits.length : 0;
        if (!newFilter) {
          $status
            .attr('data-n', numTop)
            .attr('href', 'javascript:');
          if (!numTop) {
            $status.attr('data-of', resp.mayHaveMore ?
              insertCommas(Math.max(resp.hits.length, resp.myTotal + resp.friendsTotal)) + '+' :
              (resp.hits.length || 'No'));
          }
        }
        $res.find('.kifi-filter-all').attr(numTop ? {'data-top': numTop} : {'data-n': resp.hits.length});
        $res.find('.kifi-filter-yours').attr('data-n', insertCommas(resp.myTotal));
        $res.find('.kifi-filter-friends').attr('data-n', insertCommas(resp.friendsTotal));
      }
      if (showPreview && resp.hits.length > resp.prefs.maxResults) {
        resp.nextHits = resp.hits.splice(resp.prefs.maxResults);
        resp.nextUUID = resp.uuid;
        resp.nextContext = resp.context;
        resp.mayHaveMore = true;
      }
      attachResults();
      $bar[0].className = 'kifi-res-bar' + (showPreview ? ' kifi-preview' : showAny ? '' : ' kifi-collapsed');
      $arrow[0].href = 'javascript:';
      if (inDoc) {
        tKifiResultsShown = Date.now();
      }
      if (showAny) {
        onFirstShow();
      } else {
        $res.data('onFirstShow', onFirstShow);
      }
      function onFirstShow() {
        resp.expanded = true;
        if (!resp.nextHits) {
          prefetchMore();
        }
      }
    });
    $kifi[0].search = '?q=' + encodeURIComponent(q);
  }

  function parseQ(qs) {
    var m = /[?#&]q=([^&]*)/.exec(qs);
    if (m) {
      try {
        return decodeURIComponent(m[1].replace(/\+/g, ' ')).trim();
      } catch (e) {
        log('[parseQ] non-UTF-8 query:', m[1], e);  // e.g. www.google.co.il/search?hl=iw&q=%EE%E9%E4
      }
    }
  }

  $(window).on('hashchange', function () {  // e.g. a click on a Google doodle or a switch from shopping to web search
    log("[hashchange]");
    checkSearchType();
    if (!query && !response.query) {
      search(true, null, true);
    } else {
      search();
    }
  }).on("beforeunload", function(e) {
    if (response.query === query) {
      sendSearchedEvent("unload");
    }
  });

  var observer = new MutationObserver(withMutations);
  function withMutations(mutations) {
    if (isVertical) return;
    outer:
    for (var i = 0; i < mutations.length; i++) {
      for (var j = 0, nodes = mutations[i].addedNodes; j < nodes.length; j++) {
        if (nodes[j].id === "ires") {
          log("[withMutations] Google results inserted");
          tGoogleResultsShown = Date.now();
          if (attachKifiRes(nodes[j]) && !(tKifiResultsShown >= tKifiResultsReceived)) {
            tKifiResultsShown = tGoogleResultsShown;
          }
          if (document.readyState !== 'loading') {  // avoid searching for input value if not yet updated to URL hash
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
  $(function () {
    withMutations(observer.takeRecords());
    observer.disconnect();
    observer.observe(document.getElementById('main'), whatToObserve);
  });

  function attachKifiRes(ires) {
    if ((ires = ires || document.getElementById('ires'))) {
      if ($res[0].nextElementSibling !== ires) {
        $res.insertBefore(ires);
        if (!boundResHandlers) {
          setTimeout(bindResHandlers);
          boundResHandlers = true;
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
  $(document).on("mousedown", "#search h3.r a", function logSearchEvent(e) {
    var href = this.href, $li = $(this).closest("li.g");
    var resIdx = $li.prevAll('li.g').length;
    var isKifi = $li[0].parentNode.id === 'kifi-res-list';

    clicks[isKifi ? "kifi" : "google"].push(href);

    if (href && resIdx >= 0) {
      var hit = isKifi ? response.hits[resIdx] : null;
      api.port.emit("log_search_event", [
        "resultClicked",
        {
          "origin": origin,
          "guided": e.originalEvent.guided || false,
          "uuid": isKifi ? hit.uuid : response.uuid,
          "experimentId": response.experimentId,
          "query": response.query,
          "filter": filter,
          "maxResults": response.prefs.maxResults,
          "kifiResults": response.hits.length,
          "kifiExpanded": response.expanded || false,
          "kifiTime": tKifiResultsReceived - tQuery,
          "kifiShownTime": tKifiResultsShown - tQuery,
          "thirdPartyShownTime": tGoogleResultsShown - tQuery,
          "kifiResultsClicked": clicks.kifi.length,
          "thirdPartyResultsClicked": clicks.google.length,
          "resultPosition": resIdx,
          "resultSource": isKifi ? "Kifi" : "Google",
          "resultUrl": href,
          "hit": isKifi ? {
            "isMyBookmark": hit.keepers.length > 0 && hit.keepers[0].id === response.me.id,
            "isPrivate": hit.secret || false,
            "count": hit.keepersTotal,
            "keepers": hit.keepers.map(function (u) {return u.id}),
            "tags": hit.tags || [],
            "title": hit.title,
            "titleMatches": hit.matches.title.length,
            "urlMatches": hit.matches.url.length
          } : null,
          "refinements": refinements,
          "pageSession": pageSession
        }
      ]);
    }
  });

  api.onEnd.push(function() {
    log("[google_inject:onEnd]");
    $(window).off("hashchange unload");
    observer.disconnect();
    $q.off("input");
    $qf.off("submit");
    $res.remove();
    $res.length = 0;
  });

  /*******************************************************/

  var urlAutoFormatters = [{
      match: /^https?:\/\/docs\.google\.com\//,
      desc: 'A file in Google Docs',
      icon: "gdocs.gif"
    }, {
      match: /^https?:\/\/drive\.google\.com\//,
      desc: 'A folder in your Google Drive',
      icon: "gdrive.png"
    }, {
      match: /^https?:\/\/www.dropbox\.com\/home/,
      desc: 'A folder in your Dropbox',
      icon: "dropbox.png"
    }, {
      match: /^https?:\/\/dl-web\.dropbox\.com\//,
      desc: 'A file from Dropbox',
      icon: "dropbox.png"
    }, {
      match: /^https?:\/\/www.dropbox\.com\/s\//,
      desc: 'A shared file on Dropbox',
      icon: "dropbox.png"
    }, {  // TODO: add support for Gmail labels like inbox/starred?
      match: /^https?:\/\/mail\.google\.com\/mail\/.*#.*\/[0-9a-f]{10,}$/,
      desc: "An email on Gmail",
      icon: "gmail.png"
    }, {
      match: /^https?:\/\/www.facebook\.com\/messages\/\w[\w.-]{2,}$/,
      desc: 'A conversation on Facebook',
      icon: "facebook.png"
    }];

  var strippedSchemeRe = /^https?:\/\//;
  var domainTrailingSlashRe = /^([^\/]*)\/$/;
  function formatDesc(url, matches) {
    for (var i = 0; i < urlAutoFormatters.length; i++) {
      if (urlAutoFormatters[i].match.test(url)) {
        var iconUrl = api.url('images/results/' + urlAutoFormatters[i].icon);
        return "<span class=kifi-res-type-icon style='background:url(" + iconUrl + ") no-repeat;background-size:15px'></span>" +
          urlAutoFormatters[i].desc;
      }
    }
    var strippedSchemeLen = (url.match(strippedSchemeRe) || [''])[0].length;
    url = url.substr(strippedSchemeLen).replace(domainTrailingSlashRe, '$1');
    for (var i = matches.length; i--;) {
      matches[i][0] -= strippedSchemeLen;
    }
    return boldSearchTerms(url, matches);
  }

  var boundResHandlers;
  function bindResHandlers() {
    log('[bindResHandlers]');
    $status.click(function (e) {
      if (e.which > 1 || !this.href) return;
      e.preventDefault();
      if ($bar.hasClass('kifi-preview')) {
        if (response.nextUUID === response.uuid) {
          showMore();
        } else {
          exitPreview();
        }
      } else {
        expandResults();
      }
    });
    $arrow.click(function (e) {
      if (e.which > 1 || !this.href) return;
      e.preventDefault();
      if ($bar.hasClass('kifi-collapsed')) {
        expandResults();
      } else {
        collapseResults();
      }
    });
    $bar.click(function (e) {
      if (e.which === 1 && !e.isDefaultPrevented()) {
        if (e.shiftKey && ~(response.experiments || []).indexOf('admin')) {
          location.href = response.admBaseUri + '/admin/search/results/' + response.uuid;
        } else {
          ($status.is('[href]:visible') ? $status : $arrow).triggerHandler('click');
        }
      }
    });
    $res.on('mousedown', '.kifi-res-menu-a', function (e) {
      if (e.which > 1) return;
      e.preventDefault();
      var $a = $(this).addClass('kifi-active');
      var $menu = $a.next('.kifi-res-menu').fadeIn(50);
      var $items = $menu.find('.kifi-res-menu-item')
        .on('mouseenter', enterItem)
        .on('mouseleave', leaveItem);
      var $leaves = $items.filter('a').on('mouseup', hide);
      document.addEventListener('mousedown', docMouseDown, true);
      document.addEventListener('mousewheel', hide, true);
      document.addEventListener('wheel', hide, true);
      document.addEventListener('keypress', hide, true);
      if (!$leaves.filter('.kifi-res-max-results-n.kifi-checked').length) {
        $leaves.filter('.kifi-res-max-results-' + response.prefs.maxResults).addClass('kifi-checked').removeAttr('href');
      }
      // .kifi-hover class needed because :hover does not work during drag
      function enterItem() {
        this.classList.add('kifi-hover');
      }
      function leaveItem() {
        this.classList.remove('kifi-hover');
      }
      function docMouseDown(e) {
        if (!$menu[0].contains(e.target)) {
          hide();
          if ($a[0] === e.target) {
            e.stopPropagation();
          }
        }
      }
      function hide() {
        if (this && this.classList && this.classList.contains('kifi-checkable')) {
          setTimeout(hide, 150);
          return;
        }
        document.removeEventListener('mousedown', docMouseDown, true);
        document.removeEventListener('mousewheel', hide, true);
        document.removeEventListener('wheel', hide, true);
        document.removeEventListener('keypress', hide, true);
        $a.removeClass('kifi-active');
        $items.off('mouseenter', enterItem)
              .off('mouseleave', leaveItem);
        $leaves.off('mouseup', hide);
        $menu.fadeOut(50, function () {
          $menu.find('.kifi-hover').removeClass('kifi-hover');
        });
      }
    }).on('mouseup', '.kifi-res-kifi-com', function () {
      location.href = response ? response.origin : 'https://www.kifi.com' + (query ? '/find?q=' + encodeURIComponent(query).replace(/%20/g, '+') : '');
    }).on('mouseup', '.kifi-res-max-results-n', function () {
      var $this = $(this).addClass('kifi-checked').removeAttr('href');
      $this.siblings('.kifi-checked').removeClass('kifi-checked').attr('href', 'javascript:');
      var n = +$this.text();
      api.port.emit('set_max_results', n);
    }).on('click', '.kifi-filter[href]', function (e, alreadySearched) {
      if (e.which > 1) return;
      var $v = $(this).removeAttr('href');
      $v.siblings(':not([href])').attr('href', 'javascript:');
      if (!alreadySearched) {
        var val = $v.data('val');
        search(false, {who: val});
      }
    }).hoverfu('.kifi-face.kifi-friend', function (configureHover) {
      var $a = $(this);
      var i = $a.closest("li.g").prevAll("li.g").length;
      var j = $a.prevAll(".kifi-friend").length;
      var friend = response.hits[i].users[j];
      render("html/friend_card", {
        friend: friend
      }, function (html) {
        configureHover(html, {
          position: {my: "center bottom-12", at: "center top", of: $a, collision: "none"},
          canLeaveFor: 600,
          hideAfter: 4000,
          click: "toggle"});
      });
    }).hoverfu('.kifi-res-friends', function (configureHover) {
      var $a = $(this), i = $a.closest("li.g").prevAll("li.g").length;
      render("html/search/friends", {friends: response.hits[i].users}, function(html) {
        configureHover(html, {
          position: {my: "center bottom-8", at: "center top", of: $a, collision: "none"},
          click: "toggle"});
      });
    }).on('mouseover', '.kifi-res-more-a', function () {
      $(this).closest('.kifi-res-more').addClass('kifi-over');
    }).on('mouseout', '.kifi-res-more-a', function () {
      $(this).closest('.kifi-res-more').removeClass('kifi-over');
    }).on('click', '.kifi-res-more-a', function (e) {
      if (e.which > 1) return;
      showMore();
    });
  }

  function expandResults() {
    $bar.removeClass('kifi-collapsed');
    $status.removeAttr('data-n');
    $res.find('.kifi-res-box').css('display', 'none').slideDown(200);

    var onFirstShow = $res.data('onFirstShow');
    if (onFirstShow) {
      $res.removeData('onFirstShow');
      onFirstShow();
    }
  }

  function collapseResults() {
    $res.find('.kifi-res-box').slideUp(200, function () {
      $bar.addClass('kifi-collapsed').removeClass('kifi-preview');
      $status.attr('href', 'javascript:').removeAttr('data-n');
    });
  }

  function removeResults() {
    $res.find('#kifi-res-list,.kifi-res-end').remove();
    $res.find('.kifi-res-box').finish().removeAttr('style');
  }

  function attachResults() {
    $res.find('.kifi-res-box')
      .append(render('html/search/google_hits', {
          hits: response.hits.map(renderDataForHit),
          images: api.url('images'),
          filter: response.filter && response.filter.who !== 'a',
          mayHaveMore: response.mayHaveMore
        }, {
          google_hit: 'google_hit'
        }));
    log('[attachResults] done');
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
          log('[onPrefetchResponse]');
          unpack(resp);

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

  function exitPreview() {
    $bar.removeClass('kifi-preview');
    $status.removeAttr('data-n');
  }

  function showMore() {
    log('[showMore] already showing:', response.hits.length, 'avail:', response.nextHits);
    exitPreview();
    if (response.nextHits) {
      renderMore();
      prefetchMore();
    } else if (response.mayHaveMore) {
      showMoreOnArrival = true;
    } else {
      $res.find('.kifi-res-end').empty();
    }
  }

  function renderMore() {
    var hits = response.nextHits;
    var hitHtml = response.numTop === response.hits.length ? ['<li class=kifi-res-more-heading>More keeps</li>'] : [];
    log("[renderMore] hits:", hits);
    response.hits.push.apply(response.hits, hits);
    response.uuid = response.nextUUID;
    response.context = response.nextContext;
    delete response.nextHits;
    delete response.nextUUID;
    delete response.nextContext;

    for (var i = 0; i < hits.length; i++) {
      hitHtml.push(render('html/search/google_hit', renderDataForHit(hits[i])));
    }
    $(hitHtml.join(''))
    .css({visibility: 'hidden', height: 0, margin: 0})
    .appendTo($res.find('#kifi-res-list'))
    .css({visibility: '', height: '', margin: '', display: 'none'})
    .slideDown(200, function () {
      this.style.overflow = '';  // slideDown clean-up
    });
    if (!response.filter || response.filter.who === 'a') {
      var $filAll = $res.find('.kifi-filter-all');
      $filAll.filter('[data-n]').attr('data-n', response.hits.length);
    }
    if (!response.mayHaveMore) {
      $res.find('.kifi-res-end').empty();
    }
  }

  function unpack(resp) {
    resp.users || (resp.users = []);
    resp.libraries || (resp.libraries = []);
    resp.hits.forEach(unpackHit, resp);
  }

  function unpackHit(hit) {  // 'this' is response in which hit arrived
    hit.uuid = this.uuid;
    var matches = hit.matches || (hit.matches = {});
    matches.title || (matches.title = []);
    matches.url || (matches.url = []);
    var userForIndex = intoOrElse(this.users, this.me);
    hit.keepers = (hit.keepers || []).map(userForIndex);
    if (hit.libraries) {
      var indexes = hit.libraries;
      var libs = hit.libraries = new Array(indexes.length / 2);
      for (var i = 0, j = 0; i < libs.length; i++, j += 2) {
        libs[i] = $.extend({keeper: userForIndex(indexes[j + 1])}, this.libraries[indexes[j]]);
      }
    } else {
      hit.libraries = [];
    }
    hit.tags || (hit.tags = []);
  }

  function renderDataForHit(hit) {
    var who = (response.filter || {}).who;
    var users = hit.keepers.slice(0, who === 'm' ? 1 : 8);
    if (hit.secret) {
      users[0] = $.extend({secret: true}, users[0]);
    }
    return {
      titleHtml: hit.title ?
        boldSearchTerms(hit.title, hit.matches.title) :
        formatTitleFromUrl(hit.url, hit.matches.url, bolded),
      descHtml: formatDesc(hit.url, hit.matches.url),
      score: ~response.experiments.indexOf('show_hit_scores') ? String(Math.round(hit.score * 100) / 100) : '',
      users: users,
      usersMore: hit.keepersTotal - users.length,
      usersPlural: hit.keepersTotal > 1,
      libraries: hit.libraries,
      librariesMore: hit.librariesOmitted - hit.libraries.length,
      tags: hit.tags
    };
  }

  function intoOrElse(arr, other) {
    return function (i) { return i >= 0 ? arr[i] : other; };
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
    for (var i = matches.length; i--;) {
      var match = matches[i];
      var start = match[0];
      if (start >= 0) {
        text = bolded(text, start, match[1]);
      }
    }
    return text;
  }

  function bolded(text, start, len) {
    return text.substr(0, start) + '<b>' + text.substr(start, len) + '</b>' + text.substr(start + len);
  }

  function areSameFilter(f1, f2) {
    return f1 === f2 || !f1 && !f2 || f1 && f1.who === (f2 ? f2.who : 'a') || f2 && f2.who === (f1 ? f1.who : 'a');
  }

  var insertCommasRe = /(\d)(?=(\d\d\d)+$)/g;
  function insertCommas(n) {
    return String(n).replace(insertCommasRe, '$1,');
  }
}();
