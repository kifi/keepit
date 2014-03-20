// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/mustache.js
// @require scripts/render.js

var initFriendSearch = (function () {
  var searchCallbacks = {};

  api.port.on({
    nonusers: function (o) {
      var withResults = searchCallbacks[o.searchId];
      if (withResults) {
        delete searchCallbacks[o.searchId];
        withResults(o.nonusers.concat(['tip']), false, o.error);
      }
    }
  });

  return function ($in, inviteSource, includeSelf, options) {
    $in.tokenInput(search.bind(null, includeSelf), $.extend({
      resultsLimit: 4,
      preventDuplicates: true,
      tokenValue: 'id',
      classPrefix: 'kifi-ti-',
      classForRoots: 'kifi-root',
      formatResult: formatResult,
      onSelect: onSelect.bind(null, $in, inviteSource)
    }, options));
    $('.kifi-ti-dropdown').css('background-image', 'url(' + api.url('images/wait.gif') + ')');
  };

  function search(includeSelf, numTokens, query, withResults) {
    api.port.emit('search_friends', {q: query, n: 4, includeSelf: includeSelf(numTokens)}, function (o) {
      if (o.results.length || !o.searchId) {  // wait for more if none yet
        withResults(o.results.concat(o.searchId ? [] : ['tip']), !!o.searchId);
      }
      if (o.searchId) {
        searchCallbacks[o.searchId] = withResults;
      }
    });
  }

  function formatResult(res) {
    if (res.pictureName) {
      var html = [
        '<li class="kifi-ti-dropdown-item-autoselect" style="background-image:url(//', cdnBase, '/users/', res.id, '/pics/100/', res.pictureName, ')">',
        Mustache.escape(res.parts[0])];
      for (var i = 1; i < res.parts.length; i++) {
        html.push(i % 2 ? '<b>' : '</b>', Mustache.escape(res.parts[i]));
      }
      html.push('</li>');
      return html.join('');
    } else if (res.id) {
      return [
          '<li class="kifi-ti-dropdown-invite-social', res.invited ? ' kifi-invited' : '', '"',
          ' style="background-image:url(', Mustache.escape(res.pic || 'https://www.kifi.com/assets/img/ghost-linkedin.100.png'), ')">',
          '<div class="kifi-ti-dropdown-invite-name">', Mustache.escape(res.name), '</div>',
          '<div class="kifi-ti-dropdown-invite-sub">', res.id[0] === 'f' ? 'Facebook' : 'LinkedIn', '</div>',
          '</li>'].join('');
    } else if (res.email) {
      var html = ['<li class="kifi-ti-dropdown-invite-email', res.invited ? ' kifi-invited' : '', '">'];
      if (res.name) {
        html.push('<div class="kifi-ti-dropdown-invite-name">', Mustache.escape(res.name), '</div>');
      }
      html.push('<div class="kifi-ti-dropdown-invite-sub">', Mustache.escape(res.email), '</div></li>');
      return html.join('');
    } else if (res === 'tip') {
      return '<li class="kifi-ti-dropdown-tip"><span class="kifi-ti-dropdown-tip-invite">Invite friends</span> to message them on Kifi</li>';
    }
  }

  function onSelect($in, inviteSource, res, el) {
    if (!res.pictureName) {
      if (res.id || res.email) {
        handleInvite(res, el, $in);
      } else if (res === 'tip') {
        api.port.emit('invite_friends', inviteSource);
      }
      return false;
    }
  }

  function handleInvite(res, el, $in) {
    var $el = $(el);
    var bgImg = $el.css('background-image');
    $el.addClass('kifi-inviting').css('background-image', bgImg + ',url(' + api.url('images/spinner_32.gif') + ')');
    api.port.emit('invite_friend', {id: res.id, email: res.email, source: 'composePane'}, notBeforeMs(1000, function (data) {
      $el.removeClass('kifi-inviting').css('background-image', bgImg);
      if (data.url) {
        window.open(data.url, 'kifi-invite-' + (res.id || res.email), 'height=550,width=990');
      } else if (data.sent) {
        $el.addClass('kifi-invited');
        var $activeEl = $(document.activeElement);
        if ($activeEl.is('.kifi-ti-token-for-input>input')) {
          clearInputAfter($activeEl, 1500);
        }
      } else if (data.sent === false) {
        $el.addClass('kifi-invite-fail');
        setTimeout($.fn.removeClass.bind($el, 'kifi-invite-fail'), 2000);
      }
    }));
    $in.tokenInput('flushCache').tokenInput('deselectDropdownItem');
  }

  function clearInputAfter($in, ms) {
    var t = setTimeout(function () {
      $in.val('').triggerHandler('input');
    }, 1500);
    function cleanUp() {
      clearTimeout(t);
      $in.off('input blur', cleanUp);
    }
    $in.on('input blur', cleanUp);
  }

  function notBeforeMs(ms, f) {
    var t0 = Date.now();
    return function () {
      var elapsed = Date.now() - t0;
      if (elapsed >= ms) {
        f.apply(this, arguments);
      } else {
        setTimeout(f.apply.bind(f, this, arguments), ms - elapsed);
      }
    };
  }
}());
