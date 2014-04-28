// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/mustache.js
// @require scripts/render.js

var initFriendSearch = (function () {
  var searchCallbacks = {};

  api.onEnd.push(function () {
    log('[friendSearch:onEnd]')();
    $('.kifi-ti-dropdown').remove();
  });

  api.port.on({
    contacts: function (o) {
      var withResults = searchCallbacks[o.searchId];
      if (withResults) {
        delete searchCallbacks[o.searchId];
        withResults(o.contacts.concat(['tip']), false, o.error);
      }
    }
  });

  return function ($in, source, includeSelf, options) {
    $in.tokenInput(search.bind(null, includeSelf), $.extend({
      resultsLimit: 4,
      preventDuplicates: true,
      tokenValue: 'id',
      classPrefix: 'kifi-ti-',
      classForRoots: 'kifi-root',
      formatResult: formatResult,
      onSelect: onSelect.bind(null, $in, source)
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
        '<li class="kifi-ti-dropdown-item-token" style="background-image:url(//', cdnBase, '/users/', res.id, '/pics/100/', res.pictureName, ')">'];
      appendParts(html, res.parts);
      html.push('</li>');
      return html.join('');
    } else if (res.email) {
      var html = ['<li class="kifi-ti-dropdown-contact-email">'];
      if (res.nameParts) {
        html.push('<div class="kifi-ti-dropdown-contact-name">');
        appendParts(html, res.nameParts);
        html.push('</div>');
      }
      html.push('<div class="kifi-ti-dropdown-contact-sub">');
      appendParts(html, res.emailParts);
      html.push('</div></li>');
      return html.join('');
    } else if (res === 'tip') {
      return '<li class="kifi-ti-dropdown-tip">Import Gmail contacts to message them on Kifi</li>';
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

  function onSelect($in, source, res, el) {
    if (!res.pictureName) {
      if (res.email) {
        // TODO: add email address participant to token list
      } else if (res === 'tip') {
        api.port.emit('import_contacts', source);
      }
      return false;
    }
  }
}());
