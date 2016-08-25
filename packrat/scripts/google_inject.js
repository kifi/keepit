// @match /^https?:\/\/www\.google\.(?:com|com\.(?:a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[bcy]|m[mtxy]|n[afgip]|om|p[aeghkry]|qa|s[abglv]|t[jnrw]|u[ay]|v[cn])|co\.(?:ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|pn|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[cdelmstz]|b[aefgijsty]|cat|c[acdfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aefglmpry]|h[nrtu]|i[emqsto]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|r[osuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(?:|search|webhp)(?:[?#].*)?$/
// @asap
// @require styles/google_inject.css
// @require styles/friend_card.css
// @require scripts/api.js
// @require scripts/lib/purify.js
// @require scripts/formatting.js
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery.layout.js
// @require scripts/lib/jquery-ui-position.min.js
// @require scripts/lib/jquery-hoverfu.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/title_from_url.js
// @require scripts/html/search/kifi_mustache_tags.js
// @require scripts/html/search/google.js
// @require scripts/html/search/google_hits.js
// @require scripts/html/search/google_hit.js
// ***
// Heads up: If you need to add a dependency,
// make sure you add it in the manifest.json too
// ***

api.identify('google_inject');

// Google inject regex accurate as of 2015-08-21. This is helpful: https://en.wikipedia.org/wiki/List_of_Google_domains
// (same as match pattern above)
var searchUrlRe = /^https?:\/\/www\.google\.(?:com|com\.(?:a[fgiru]|b[dhnorz]|c[ouy]|do|e[cgt]|fj|g[hit]|hk|jm|k[hw]|l[bcy]|m[mtxy]|n[afgip]|om|p[aeghkry]|qa|s[abglv]|t[jnrw]|u[ay]|v[cn])|co\.(?:ao|bw|c[kr]|i[dln]|jp|k[er]|ls|m[az]|pn|nz|t[hz]|u[gkz]|v[ei]|z[amw])|a[cdelmstz]|b[aefgijsty]|cat|c[acdfghilmnvz]|d[ejkmz]|e[es]|f[imr]|g[aefglmpry]|h[nrtu]|i[emqsto]|j[eo]|k[giz]|l[aiktuv]|m[degklnsuvw]|n[eloru]|p[lnstosuw]|r[osuw]|s[cehikmnot]|t[dgklmnot]|v[gu]|ws)\/(?:|search|webhp)(?:[?#].*)?$/;
// line comment to kill regex syntax highlighting

var pageSession = Math.random().toString(16).slice(2);

// We check the pattern because Chrome match/glob patterns aren't powerful enough. crbug.com/289057
if (searchUrlRe.test(document.URL)) !function () {
  log('[google_inject]');

  var origin = location.origin;
  var $res = $(k.render('html/search/google'));   // a reference to our search results (kept so that we can reinsert when removed)
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
  var time = {google: {shown: 0, shownAnalytics: 0}, kifi: {queried: 0, received: 0, shown: 0}};  // for timing stats

  var $q = $(), $qf = $q, $qp = $q, keyTimer;
  $(function() {
    $q = $("#gbqfq,#lst-ib").on("input", onInput);  // stable identifier: "Google Bar Query Form Query"
    $qf = $("#gbqf,#tsf").submit(onSubmit);  // stable identifiers: "Google Bar Query Form", "Top Search Form"
    $qp = $("#gs_taif0");  // stable identifier: "Google Search Type-Ahead Input Field"
  });
  function onInput() {
    clearTimeout(keyTimer);
    keyTimer = setTimeout(function () {
      search();
    }, 250);  // enough of a delay that we won't search after *every* keystroke (similar to Google's behavior)
  }
  function onSubmit() {
    clearTimeout(keyTimer);
    search();
  }

  checkSearchType();
  search(true, null, true);  // Google can be slow to initialize the input field, or it may be missing
  if (document.getElementById('ires')) {
    time.google.shown = time.google.shownAnalytics = time.kifi.queried;
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
    var kgDelta = time.kifi.shown - time.google.shownAnalytics;
    api.port.emit("log_search_event", [
      "searched",
      {
        "origin": origin,
        "guided": 'guide' in k,
        "uuid": response.uuid,
        "experimentId": response.experimentId,
        "query": response.query,
        "filter": filter,
        "maxResults": response.prefs.maxResults,
        "kifiResults": response.hits.length,
        "kifiResultsWithLibraries": response.hits.filter(hasLibrary).length,
        "kifiExpanded": response.expanded || false,
        "kifiTime": Math.max(-1, time.kifi.received - time.kifi.queried),
        "kifiShownTime": Math.max(-1, time.kifi.shown - time.kifi.queried),
        "thirdPartyShownTime": Math.max(-1, time.google.shownAnalytics - time.kifi.queried),
        "kifiResultsClicked": clicks.kifi.length,
        "thirdPartyResultsClicked": clicks.google.length,
        "chunkDelta": response.chunkDelta,
        "chunksSplit": kgDelta > 0 && kgDelta < response.chunkDelta,
        "refinements": refinements,
        "pageSession": pageSession,
        "endedWith": endedWith
      }
    ]);
  }

  function search(useLocation, newFilter, isFirst) {
    return; // Kifi is shut down.
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

    var previousQuery = time.kifi.queried;
    var t1 = time.kifi.queried = Date.now();
    refinements++;
    api.port.emit("get_keeps", {query: q, filter: newFilter, first: isFirst, whence: 'i'}, function results(resp) {
      if (q !== query || !areSameFilter(newFilter, filter)) {
        log("[results] ignoring for query:", q, "filter:", newFilter);
        t1 = time.kifi.queried = previousQuery; // since we ignore this query, set the time we queried back to the original
        return;
      } else if (!resp.me) {
        log("[results] no user info");
        $res.hide();
        return;
      }

      var now = time.kifi.received = Date.now();

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
      var showAny = Boolean(resp.cutPoint && (resp.prefs.maxResults && !(inDoc && time.google.shown >= time.kifi.shown) || resp.context === 'guide') || newFilter);
      var showPreview = Boolean(showAny && !newFilter);
      log('[results] cutPoint:', resp.cutPoint, 'inDoc:', inDoc);
      unpack(resp);
      if (resp.hits.length) {
        if (resp.cutPoint) {
          stashExtraHits(resp, resp.cutPoint);
        }
      } else {
        resp.mayHaveMore = false;
      }

      if (!newFilter || newFilter.who === 'a') {
        var numTop = resp.cutPoint;
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
      if (showPreview) {
        stashExtraHits(resp, resp.prefs.maxResults);
      }
      var timeBeforeAttach = +new Date();
      attachResults();
      var timeAfterAttach = +new Date();
      log('[results.attach] timeBefore: %s, timeAfter: %s, delta: %s', timeBeforeAttach, timeAfterAttach, timeAfterAttach - timeBeforeAttach);
      $bar[0].className = 'kifi-res-bar' + (showPreview ? ' kifi-preview' : showAny ? '' : ' kifi-collapsed');
      $arrow[0].href = 'javascript:';
      if (inDoc) {
        time.kifi.shown = Date.now();
        if (showAny) {
          $res.find('.kifi-res-why').each(makeWhyFit);
        }
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

  function stashExtraHits(resp, n) {
    if (resp.hits.length > n) {
      resp.nextHits = resp.hits.splice(n);
      resp.nextUUID = resp.uuid;
      resp.nextContext = resp.context;
      resp.mayHaveMore = true;
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
    if (attachKifiRes() > 0) {
      log('[withMutations] Google results inserted');
      var now = Date.now();
      time.google.shown = now;
      if (time.google.shownAnalytics < time.kifi.queried) {  // updating if we haven't searched would make us look artificially good
        time.google.shownAnalytics = now;
      }
      if (time.kifi.shown < time.kifi.received) {  // updating if we haven't received anything new would make us look artificially bad
        time.kifi.shown = now;
      }
      if (document.readyState !== 'loading') {  // avoid searching for input value if not yet updated to URL hash
        $(function () {
          // prediction may have changed
          setTimeout(function () {
            search();
          }, 0);
        });
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

  function attachKifiRes() {
    var ires = document.getElementById('ires');
    if (ires) {
      if ($res[0].nextElementSibling !== ires) {
        $res.insertBefore(ires);
        if (!$res[0].firstElementChild.classList.contains('kifi-collapsed')) {
          $res.find('.kifi-res-why:not(.kifi-fitted)').each(makeWhyFit);
        }
        if (!boundResHandlers) {
          setTimeout(function () {
            bindResHandlers()
          });
          boundResHandlers = true;
        }
        return 1; // just attached
      }
      return -1; // already attached
    }
  }

  if (!api.mutationsFirePromptly) {
    var retryMs = 1;
    function tryAttach() {
      var success = attachKifiRes();
      if (!success) {
        setTimeout(function () {
          tryAttach();
        }, retryMs);
      }
    };

    setTimeout(function () {
      tryAttach();
    }, retryMs);
  }

  // TODO: also detect result selection via keyboard
  $(document).on('mousedown', '#search h3.r a', function logSearchEvent(e) {
    var href = this.href, $li = $(this).closest("li.g");
    var resIdx = $li.prevAll('li.g').length;
    var isKifi = $li[0].parentNode.id === 'kifi-res-list';

    clicks[isKifi ? 'kifi' : 'google'].push(href);

    if (href && resIdx >= 0) {
      var hit = isKifi ? response.hits[resIdx] : null;
      api.port.emit('log_search_event', [
        "clicked",
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
          "kifiTime": Math.max(-1, time.kifi.received - time.kifi.queried),
          "kifiShownTime": Math.max(-1, time.kifi.shown - time.kifi.queried),
          "thirdPartyShownTime": Math.max(-1, time.google.shownAnalytics - time.kifi.queried),
          "kifiResultsClicked": clicks.kifi.length,
          "thirdPartyResultsClicked": clicks.google.length,
          "resultPosition": resIdx,
          "resultSource": isKifi ? "Kifi" : "Google",
          "resultUrl": href,
          "hit": isKifi ? {
            "isMyBookmark": hit.keepers.length > 0 && hit.keepers[0].id === response.me.id,
            "isPrivate": hit.secret || false,
            "count": hit.keepersTotal,
            "keepers": hit.keepers.map(getId),
            "libraries": hit.libraries.map(getIdAndKeeperId),
            "tags": hit.tags,
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
    $(window).off('hashchange unload');
    observer.disconnect();
    $q.off('input');
    $qf.off("submit");
    $res.find('.kifi-res-user,.kifi-res-users-n,.kifi-res-lib,.kifi-res-libs-n,.kifi-res-tags-n').hoverfu('destroy');
    $res.remove();
    $res.length = 0;
  });

  /*******************************************************/

  var urlAutoFormatters = [{
      match: /^https?:\/\/docs\.google\.com\/(?:[^?#]+\/)document\//,
      desc: 'A document on Google',
      icon: 'docs'
    }, {
      match: /^https?:\/\/drive\.google\.com\//,
      desc: 'A folder on Google Drive',
      icon: 'drive'
    }, {
      match: /^https?:\/\/www.dropbox\.com\/(?:home|work|lightbox\/)/,
      icon: 'dropbox'
    }, {
      match: /^https?:\/\/dl-web\.dropbox\.com\//,
      desc: 'A file on Dropbox',
      icon: 'dropbox'
    }, {
      match: /^https?:\/\/www.dropbox\.com\/s\//,
      desc: 'A shared file on Dropbox',
      icon: 'dropbox'
    }, {  // TODO: add support for Gmail labels like inbox/starred?
      match: /^https?:\/\/mail\.google\.com\/mail\/.*#.*\/[0-9a-f]{10,}$/,
      desc: "An email on Gmail",
      icon: 'gmail'
    }, {
      match: /^https?:\/\/www.facebook\.com\/messages\/\w[\w.-]{2,}$/,
      desc: 'A conversation on Facebook',
      icon: 'facebook'
    }, {
      match: /\.pdf(\?|#|$)/i,
      icon: 'pdf'
    }, {
      match: /^https?:\/\/docs\.google\.com\/(?:[^?#]+\/)spreadsheets\//,
      desc: 'A spreadsheet on Google',
      icon: 'sheets'
    }, {
      match: /^https?:\/\/sites.google.com\/site\//,
      icon: 'sites'
    }, {
      match: /^https?:\/\/docs\.google\.com\/(?:[^?#]+\/)presentation\//,
      desc: 'A slide deck on Google',
      icon: 'slides'
    }];

  var strippedSchemeRe = /^https?:\/\//;
  var domainTrailingSlashRe = /^([^\/]*)\/$/;
  function formatDesc(url, matches) {
    var icon, desc;
    for (var i = 0; i < urlAutoFormatters.length; i++) {
      var uaf = urlAutoFormatters[i];
      if (uaf.match.test(url)) {
        icon = '<span class="kifi-res-type-icon kifi-' + uaf.icon + '"></span>';
        desc = uaf.desc;
        break;
      }
    }
    if (!desc) {
      var strippedSchemeLen = (url.match(strippedSchemeRe) || [''])[0].length;
      url = url.substr(strippedSchemeLen).replace(domainTrailingSlashRe, '$1');
      for (var i = matches.length; i--;) {
        matches[i][0] -= strippedSchemeLen;
      }
      desc = boldSearchTerms(url, matches);
    }
    return (icon || '') + desc;
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
          setTimeout(function () {
            hide();
          }, 150);
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
    }).on('click', '.kifi-res-user', function () {
      var a = this, url = a.href;
      if (url.indexOf('?') < 0) {
        a.href = url + '?o=xsr';
        setTimeout(function () {
          a.href = url;
        });
      }
    }).hoverfu('.kifi-res-user', function (configureHover) {
      var $a = $(this);
      var i = $a.prevAll('.kifi-res-user').length;
      var user = $a.closest('.kifi-res-why').data('users')[i];
      k.render('html/friend_card', $.extend({
        self: user.id === response.me.id,
        className: 'kifi-res-user-card'
      }, user), function (html) {
        configureHover(html, {
          position: {my: 'left-46 bottom-16', at: 'left top', of: $a, collision: 'none'},
          canLeaveFor: 600,
          hideAfter: 4000});
      });
    }).hoverfu('.kifi-res-users-n', function (configureHover) {
      var $a = $(this);
      var data = $a.closest('.kifi-res-why').data();
      var keepersPictured = data.users.filter(isNotDuplicate);
      var moreKeepers = data.raw.keepers.filter(function (u) {
        return !keepersPictured.some(idIs(u.id));
      });
      var self = moreKeepers.length && moreKeepers[0].id === response.me.id;
      k.render('html/search/more_keepers', {
        self: self,
        keepers: self ? moreKeepers.slice(1) : moreKeepers,
        others: +$a.text() - moreKeepers.length
      }, function (html) {
        configureHover(html, {
          position: {my: 'center bottom-9', at: 'center top', of: $a, collision: 'none'},
          click: 'toggle'});
      });
    }).hoverfu('.kifi-res-lib', function (configureHover) {
      var $a = $(this);
      var i = $a.prevAll('.kifi-res-lib').length;
      var library = $a.closest('.kifi-res-why').data('libraries')[i];
      k.render('html/library_card', $.extend({origin: response.origin}, library), function (html) {
        configureHover(html, {
          position: {my: 'left-46 bottom-16', at: 'center top', of: $a, collision: 'none'},
          canLeaveFor: 600,
          click: 'toggle'});
        ($a.data('hoverfu') || {on:api.noop}).$h.on('click', '.kifi-lc-follow[href]', function (e) {
          var $btn = $(this).removeAttr('href');
          var following = library.following;
          var withOutcome = progress($btn.parent(), 'kifi-lc-progress', function (success) {
            $btn.nextAll('.kifi-lc-progress').delay(300).fadeOut(300, function () {
              $(this).remove();
              $btn.prop('href', 'javascript:');
            });
            if (success) {
              library.following = following = !following;
              $btn.toggleClass('kifi-following', following);
              var $n = $btn.closest('.kifi-lc').find('.kifi-lc-followers>.kifi-lc-count-n');
              $n.text(Math.max(0, +$n.text() + (following ? 1 : -1)));
            }
          });
          api.port.emit(following ? 'unfollow_library' : 'follow_library', library.id, withOutcome);
        });
      });
      if (!library.owner) {
        detailLibrary(library, function detail(lib, retryMs) {
          var $card = ($a.data('hoverfu') || {}).$h;
          if ($card) {
            if (lib.image) {
              $card.find('.kifi-lc-top').css({'background-image': 'url(' + lib.imageUrl + ')', 'background-position': lib.image.x + '% ' + lib.image.y + '%'});
            }
            $card.find('.kifi-lc-bottom').css('border-color', lib.color);
            $card.find('.kifi-lc-owner-pic').css('background-image', 'url(' + lib.owner.pictureUrl + ')');
            $card.find('.kifi-lc-owner').text(lib.owner.name);
            var $n = $card.find('.kifi-lc-count-n');
            $n.first().text(lib.keeps);
            $n.last().text(lib.followers);
            $card.find('.kifi-lc-follow').toggleClass('kifi-following', !!lib.following);
            $card.find('.kifi-lc-footer').toggleClass('kifi-mine', lib.mine).toggleClass('kifi-not-mine', !lib.mine);
          } else if ((retryMs || (retryMs = 100)) < 2000) {
            log('[detailLibrary] will retry after %ims', retryMs);
            setTimeout(function () {
              detail(lib, retryMs * 2);
            }, retryMs);
          } else {
            log('[detailLibrary] will not retry');
          }
        });
      }
    }).hoverfu('.kifi-res-libs-n', function (configureHover) {
      var $a = $(this);
      var data = $a.closest('.kifi-res-why').data();
      var nLibsShown = $a.prevAll('.kifi-res-lib').length;
      var moreLibs = data.libraries.slice(nLibsShown, nLibsShown + 3);
      k.render('html/search/more_libraries', {
        libraries: moreLibs,
        origin: response.origin,
        others: +$a.text() - moreLibs.length
      }, function (html) {
        configureHover(html, {
          position: {my: 'center bottom-16', at: 'center top', of: $a, collision: 'none'},
          canLeaveFor: 600,
          click: 'toggle'});
      });
      moreLibs.forEach(function (lib) {
        if (!lib.owner) {
          detailLibrary(lib, function (lib) {
            var $lib = (($a.data('hoverfu') || {}).$h || $()).find('.kifi-ml-lib[data-id=' + lib.id + ']');
            if ($lib) {
              $lib.find('.kifi-ml-owner').text(lib.owner.name);
              var $n = $lib.find('.kifi-ml-count');
              $n.first().attr('data-n', lib.keeps);
              $n.last().attr('data-n', lib.followers);
            }
          });
        }
      });
    }).hoverfu('.kifi-res-tags-n', function (configureHover) {
      var $a = $(this);
      var data = $a.closest('.kifi-res-why').data();
      var nTagsListed = $a.prevAll('.kifi-res-tag').length;
      var moreTags = data.tags.slice(nTagsListed);
      k.render('html/search/more_tags', {
        tags: moreTags,
        others: +$a.text() - moreTags.length
      }, function (html) {
        configureHover(html, {
          position: {my: 'center bottom-9', at: 'center top', of: $a, collision: 'none'},
          click: 'toggle'});
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

    $res.find('.kifi-res-why:not(.kifi-fitted)').each(makeWhyFit);
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
    var hitsData = response.hits.map(renderDataForHit);
    var $hits = $(k.render('html/search/google_hits', {
        hits: hitsData,
        images: api.url('images'),
        filter: response.filter && response.filter.who !== 'a',
        mayHaveMore: response.mayHaveMore,
        fixedColor: ~(response.experiments || []).indexOf('visited_fixed')
      }, {
        google_hit: 'google_hit',
        'kifi_mustache_tags': 'kifi_mustache_tags'
      }));
    $res.find('.kifi-res-box').append($hits);
    $hits.find('.kifi-res-why').get().forEach(stashHitData, [response.hits, hitsData]);
    log('[attachResults] done');
  }

  function stashHitData(el, i) {
    $(el).data(this[1][i]).data('raw', this[0][i]);
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

  function detailLibrary(lib, callback) {
    api.port.emit('get_library', lib.id, function (o) {
      $.extend(lib, o);
      if (o.image) {
        lib.imageUrl = k.cdnBase + '/' + o.image.path;
      }
      lib.owner.pictureUrl = k.cdnBase + '/users/' + o.owner.id + '/pics/200/' + o.owner.pictureName;
      lib.owner.name = o.owner.firstName + ' ' + o.owner.lastName;
      lib.mine = lib.owner.id === response.me.id;
      callback(lib);
    });
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
    var hitHtml = (!response.filter || response.filter.who === 'a') && response.cutPoint === response.hits.length ?
      ['<li class=kifi-res-more-heading>More keeps</li>'] : [];
    log("[renderMore] hits:", hits);
    response.hits.push.apply(response.hits, hits);
    response.uuid = response.nextUUID;
    response.context = response.nextContext;
    delete response.nextHits;
    delete response.nextUUID;
    delete response.nextContext;

    var hitsData = [];
    for (var i = 0; i < hits.length; i++) {
      hitHtml.push(k.render('html/search/google_hit', hitsData[i] = renderDataForHit(hits[i]), {
        'kifi_mustache_tags': 'kifi_mustache_tags'
      }));
    }
    var $hits = $(hitHtml.join(''))
    .css({visibility: 'hidden', height: 0, margin: 0})
    .appendTo($res.find('#kifi-res-list'));

    var $whys = $hits.find('.kifi-res-why');
    $whys.get().forEach(stashHitData, [hits, hitsData]);
    $whys.each(makeWhyFit);

    $hits.css({visibility: '', height: '', margin: '', display: 'none'})
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
    var intersectionBase = '/int?uri=' + hit.uriId;
    var userForIndex = intoOrElse(this.users, this.me);
    hit.keepers = (hit.keepers || []).map(userForIndex).map(function (user) {
      user.intersection = intersectionBase + '&user=' + user.id;
      return user;
    });
    if (hit.libraries) {
      var indexes = hit.libraries;
      var libs = hit.libraries = new Array(indexes.length / 2);
      for (var i = 0, j = 0; i < libs.length; i++, j += 2) {
        libs[i] = $.extend({keeper: userForIndex(indexes[j + 1])}, this.libraries[indexes[j]]);
        libs[i].intersection = intersectionBase + '&library=' + libs[i].id;
      }
    } else {
      hit.libraries = [];
    }
    hit.tags || (hit.tags = []);
  }

  function prioritizeSlack(sourceA, sourceB) {
    if (sourceA.slack && sourceB.twitter) {
      // Slack is "smaller", because we want it at the beginning
      return -1;
    } else if (sourceB.slack && sourceA.twitter) {
      // Twitter is "bigger", because we want it at the end
      return 1;
    } else {
      return 0;
    }
  }

  function renderDataForHit(hit) {
    var who = (response.filter || {}).who;
    var users = hit.keepers.slice(0, who === 'm' ? 1 : 8);
    if (hit.secret) {
      users[0] = $.extend({secret: true}, users[0]);
    }
    hit.libraries.forEach(markKeeperAsDupeIn(users));

    var titleHtml = (hit.title ? boldSearchTerms(hit.title, hit.matches.title) : formatTitleFromUrl(hit.url, hit.matches.url, bolded));
    var descHtml = formatDesc(hit.url, hit.matches.url);

    return {
      raw: hit,
      url: hit.url,
      titleHtml: k.formatting.jsonDom(titleHtml),
      descHtml: k.formatting.jsonDom(descHtml),
      score: ~response.experiments.indexOf('show_hit_scores') ? String(Math.round(hit.score * 100) / 100) : '',
      users: users,
      usersMore: hit.keepersTotal - users.filter(isNotDuplicate).length || '',
      usersPlural: hit.keepersTotal > 1,
      usersName: hit.keepersTotal === 1 && users.length ? (users[0].id === response.me.id ? 'You' : users[0].firstName + ' ' + users[0].lastName) : '',
      libraries: hit.libraries,
      librariesMore: hit.librariesOmitted || '',
      tags: hit.tags,
      tagsMore: hit.tagsOmitted || '',
      sources: (hit.sources || []).sort(prioritizeSlack),
      origin: response.origin,
      uriId: hit.uriId
    };
  }

  function makeWhyFit() {  // 'this' is .kifi-res-why element
    var pxToGo = (function (el, targetWidth) {
      if (targetWidth === 0) {
        throw Error('[makeWhyFit] targetWidth === 0 | ' + el.className + ' | ' + el.parentNode.className + ' | ' + el.parentNode.parentNode.className + ' | ' + el.parentNode.parentNode.parentNode.className);
      }
      if (!el.hasChildNodes()) {
        el = el.previousElementSibling;
      }
      return el.offsetLeft + el.offsetWidth - targetWidth;
    }(this.lastElementChild, this.offsetWidth));

    // postponing DOM writes for perf
    var elsToRemove = [];
    var elClassesToRemove = [];
    var elStylesToSet = [];

    var MIN_LIB_WIDTH = 160;
    while (pxToGo > 0) {
      if (!libEls) {
        var libEls = Array.prototype.slice.call(this.getElementsByClassName('kifi-res-lib'));
        var libWidths = libEls.map(getOffsetWidth);
      }
      var i = findLastAtLeast(libWidths, MIN_LIB_WIDTH + pxToGo);
      if (i >= 0) {
        // ellide one library name to fit
        elStylesToSet.push([libEls[i], 'maxWidth', libWidths[i] - pxToGo + 'px']);
        pxToGo = 0;
        break;
      } else if (libEls.length === 1) {
        // ellide last library name a bit
        if (libWidths[0] > MIN_LIB_WIDTH) {
          elStylesToSet.push([libEls[0], 'maxWidth', MIN_LIB_WIDTH + 'px']);
          pxToGo -= libWidths[0] - MIN_LIB_WIDTH;
        }
        break;
      } else if (libEls.length > 1) {
        // remove a library
        var el = libEls.pop();
        elsToRemove.push(el);
        pxToGo -= libWidths.pop() + 5;  // 5px left margin

        // increment omitted library count
        if (!nLibsEl) {
          var nLibsEl = this.getElementsByClassName('kifi-res-libs-n')[0];
          var nLibs = +nLibsEl.textContent || 0;
          if (nLibs === 0) {
            pxToGo += 24;  // plus and space and 1
          }
        }
        nLibs++;

        var nLibsNow = libEls.length;
        var data = data || $.data(this);
        var userId = data.libraries[nLibsNow].keeper.id;
        if (!data.libraries.slice(0, nLibsNow).some(hasKeeperId(userId))) {
          for (var i = 0; i < data.users.length; i++) {
            var user = data.users[i];
            if (user.id === userId) {
              // show keeper in users list
              var userEls = userEls || Array.prototype.slice.call(this.getElementsByClassName('kifi-res-user'));
              elClassesToRemove.push([userEls[i], 'kifi-duplicate']);
              pxToGo += 26;  // 23px pic + 3px left margin

              // decrement omitted user count
              if (!nUsersEl) {
                var nUsersEl = this.getElementsByClassName('kifi-res-users-n')[0];
                var nUsers = +nUsersEl.textContent;
                if (data.users.every(isDuplicate)) {
                  pxToGo += 12;  // plus and space
                }
              }
              nUsers--;
              break;
            }
          }
        }
      } else {
        break;
      }
    }

    while (pxToGo > 0) {
      var tagEls = tagEls || Array.prototype.slice.call(this.getElementsByClassName('kifi-res-tag'));

      // remove or shorten last tag to fit
      if (tagEls.length > 1) {
        // remove a tag
        var tagEl = tagEls.pop();
        elsToRemove.push(tagEl);
        pxToGo -= tagEl.offsetWidth;  // includes comma and space

        // increment omitted tag count
        if (!nTagsEl) {
          var nTagsEl = this.getElementsByClassName('kifi-res-tags-n')[0];
          var nTags = +nTagsEl.textContent || 0;
          if (nTags === 0) {
            pxToGo += 24;  // plus and space and 1
          }
        }
        nTags++;
      } else if (tagEls.length) {
        // shorten only tag name to fit
        var tagEl = tagEls[0];
        var difference = tagEl.offsetWidth - pxToGo;
        if (difference > 20) {
          elStylesToSet.push([tagEl, 'maxWidth', tagEl.offsetWidth - pxToGo + 'px']);
        } else {
          if (!nTagsEl) {
            var nTagsEl = this.getElementsByClassName('kifi-res-tags-n')[0];
            var nTags = +nTagsEl.textContent || 0;
            if (nTags === 0) {
              pxToGo += 24;  // plus and space and 1
            }
          }
          elsToRemove.push(tagEls.pop());
          nTags++;
          pxToGo -= tagEl.offsetWidth;
        }
        break;
      } else {
        break;
      }
    }

    while (pxToGo > 0) {
      var userEls = userEls || Array.prototype.slice.call(this.getElementsByClassName('kifi-res-user'));
      var userEl;

      if (userEls.length > 2) {
        userEl = userEls.pop();
        elsToRemove.push(userEl);
        pxToGo -= userEl.offsetWidth;
        nUsers++;
      } else {
        break;
      }
    }

    while (pxToGo > 0) {
      var sourceEls = sourceEls || Array.prototype.slice.call(this.getElementsByClassName('kifi-res-source'));
      var sourceEl;

      if (sourceEls.length > 1) {
        sourceEl = sourceEls.pop();
        elsToRemove.push(sourceEl);
        pxToGo -= sourceEl.offsetWidth;
      } else {
        break;
      }
    }

    (nUsersEl || {}).textContent = nUsers;
    (nLibsEl || {}).textContent = nLibs;
    (nTagsEl || {}).textContent = nTags;
    elsToRemove.forEach(function (el) { el.remove() });
    elClassesToRemove.forEach(function (op) { op[0].classList.remove(op[1]) });
    elStylesToSet.forEach(function (op) { op[0].style[op[1]] = op[2] });
    this.classList.add('kifi-fitted');
  }

  function markKeeperAsDupeIn(users) {
    return function (lib) {
      for (var i = 0; i < users.length; i++) {
        var user = users[i];
        if (user.id === lib.keeper.id) {
          users[i] = $.extend({duplicate: true}, user);
          break;
        }
      }
    };
  }

  function findLastAtLeast(vals, val) {
    for (var i = vals.length - 1; i >= 0; i--) {
      if (vals[i] >= val) {
        return i;
      }
    }
  }

  function progress(parent, cssClass, finished) {
    var $el = $('<div>').addClass(cssClass).appendTo(parent);
    var frac = 0, ms = 10;

    var timeout;
    function update() {
      var left = .9 - frac;
      frac += .06 * left;
      $el[0].style.width = Math.min(frac * 100, 100) + '%';
      if (left > .0001) {
        timeout = setTimeout(function () {
          update()
        }, ms);
      }
    }
    timeout = setTimeout(function () {
      update();
    }, ms);

    return function (success) {
      if (success) {
        log('[progress:done]');
        clearTimeout(timeout), timeout = null;
        $el.on('transitionend', function (e) {
          if (e.originalEvent.propertyName === 'clip') {
            $el.off('transitionend');
            finished(true);
          }
        }).addClass('kifi-done');
      } else {
        log('[progress:fail]');
        clearTimeout(timeout), timeout = null;
        var finishFail = function () {
          $el.remove();
          deferred.reject(reason);
        };
        if ($el[0].offsetWidth) {
          $el.one('transitionend', finishFail).addClass('kifi-fail');
        } else {
          finishFail();
        }
      }
    };
  }

  function getOffsetWidth(el) {
    return el.offsetWidth;
  }
  function idIs(id) {
    return function (o) {return o.id === id};
  }
  function getId(o) {
    return o.id;
  }
  function getIdAndKeeperId(lib) {
    return [lib.id, lib.keeper.id];
  }
  function hasLibrary(hit) {
    return hit.libraries.length > 0;
  }
  function hasKeeperId(id) {
    return function (lib) { return lib.keeper.id === id; };
  }
  function isDuplicate(o) {
    return o.duplicate;
  }
  function isNotDuplicate(o) {
    return !o.duplicate;
  }
  function intoOrElse(arr, other) {
    return function (i) { return i >= 0 ? arr[i] : other; };
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
