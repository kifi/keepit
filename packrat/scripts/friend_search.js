// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/mustache.js
// @require scripts/render.js

var initFriendSearch = (function () {

  api.onEnd.push(function () {
    log('[friendSearch:onEnd]')();
    $('.kifi-ti-dropdown').remove();
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
    api.port.emit('search_friends', {q: query, n: 4, includeSelf: includeSelf(numTokens)}, function (results) {
      results.push('tip');
      withResults(results);
    });
  }

  function formatResult(res) {
    if (res.pictureName) {
      var html = [
        '<li class="kifi-ti-dropdown-item-token" style="background-image:url(//', cdnBase, '/users/', res.id, '/pics/100/', res.pictureName, ')">'];
      appendParts(html, res.parts);
      html.push('</li>');
      return html.join('');
    } else if (res === 'tip') {
      return '<li class="kifi-ti-dropdown-tip"><span class="kifi-ti-dropdown-tip-invite">Invite friends</span> to message them on Kifi</li>';
    }
  }

  function appendParts(html, parts) {
    for (var i = 0; i < parts.length; i++) {
      if (i % 2) {
        html.push('<b>', Mustache.escape(parts[i]), '</b>');
      } else {
        html.push(Mustache.escape(parts[i]));
      }
    }
  }

  function onSelect($in, inviteSource, res, el) {
    if (res === 'tip') {
      api.port.emit('invite_friends', inviteSource);
      return false;
    }
  }
}());
