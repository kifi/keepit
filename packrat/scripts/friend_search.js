// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/mustache.js
// @require scripts/render.js

var initFriendSearch = (function () {
  var searchCallbacks = {};

  api.onEnd.push(function () {
    log('[friendSearch:onEnd]');
    $('.kifi-ti-dropdown').remove();
  });

  api.port.on({
    contacts: function (o) {
      var withResults = searchCallbacks[o.searchId];
      if (withResults) {
        delete searchCallbacks[o.searchId];
        if (o.refresh) {
          var results = o.friends.concat(o.contacts);
        } else {
          var results = o.contacts;
        }
        withResults(results.concat(['tip']), false, o.error, o.refresh);
      }
    }
  });

  return function ($in, source, participants, includeSelf, options) {
    $in.tokenInput(search.bind(null, participants, includeSelf), $.extend({
      resultsLimit: 4,
      preventDuplicates: true,
      tokenValue: 'id',
      classPrefix: 'kifi-ti-',
      classForRoots: 'kifi-root',
      formatResult: formatResult,
      onSelect: onSelect.bind(null, $in, source),
      onRemove: function (item, replaceWith) {
        $('.kifi-ti-dropdown-item-waiting')
          .addClass('kifi-ti-dropdown-email')
          .css('background-image', 'url(' + api.url('images/wait.gif') + ')');
        // Search for another contact to be used as a replacement
        var query = $in.tokenInput('getQuery');
        var items = $in.tokenInput('getItems');
        var emailSuggestions = (items || []).reduce(function (prev, curr) {
          if (curr.email) {
            prev.push(curr.email);
          }
          return prev;
        }, []);
        api.port.emit('delete_contact', item.email, function (status) {
          if (!status) {
            return replaceWith(item); // put old item back into place
          }
          api.port.emit('search_contacts', {q: query, n: emailSuggestions.length + 1}, function (contacts) {
            for (var i = 0; i < contacts.length; i++) {
              var candidate = contacts[i];
              if (emailSuggestions.indexOf(candidate.email) === -1) {
                return replaceWith(candidate);
              }
            }
            return replaceWith();
          });
        });
      }
    }, options));
    $('.kifi-ti-dropdown').css('background-image', 'url(' + api.url('images/wait.gif') + ')');
  };

  function search(participants, includeSelf, numTokens, query, withResults) {
    api.port.emit('search_friends', {q: query, n: 4, participants: participants, includeSelf: includeSelf(numTokens)}, function (o) {
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
      var html = [];
      if (res.isNew) {
        html.push('<li class="kifi-ti-dropdown-item-token kifi-ti-dropdown-email kifi-ti-dropdown-new-email">');
      } else {
        html.push('<li class="kifi-ti-dropdown-item-token kifi-ti-dropdown-email kifi-ti-dropdown-contact-email">')
      }
      if (res.nameParts) {
        html.push('<div class="kifi-ti-dropdown-contact-name">');
        appendParts(html, res.nameParts);
        html.push('</div><div class="kifi-ti-dropdown-contact-sub">');
      } else {
        html.push('<div class="kifi-ti-dropdown-contact-name">');
      }
      appendParts(html, res.emailParts);
      html.push('</div>');
      if (!res.isNew) {
        html.push('<a class="kifi-ti-dropdown-item-x" href="javascript:"></a>');
      }
      html.push('</li>');
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
    if (!res.pictureName && !res.email) {
      if (res === 'tip') {
        api.port.emit('import_contacts', source);
      }
      return false;
    }
  }
}());
