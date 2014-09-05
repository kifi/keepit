// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/mustache.js
// @require scripts/render.js

var initFriendSearch = (function () {

  return function ($in, source, participants, includeSelf, options) {
    $in.tokenInput(search.bind(null, participants, includeSelf), $.extend({
      resultsLimit: 4,
      preventDuplicates: true,
      tokenValue: 'id',
      classPrefix: 'kifi-ti-',
      formatResult: formatResult,
      formatToken: formatToken,
      onSelect: onSelect.bind(null, $in, source),
      onRemove: function (item, replaceWith) {
        $('.kifi-ti-dropdown-item-waiting')
          .addClass('kifi-ti-dropdown-email')
          .css('background-image', 'url(' + api.url('images/wait.gif') + ')');
        var query = $in.tokenInput('getQuery');
        var items = $in.tokenInput('getItems');
        api.port.emit('delete_contact', item.email, function (success) {
          replaceWith(success ? null : item);
        });
      }
    }, options));
    // $('.kifi-ti-dropdown').css('background-image', 'url(' + api.url('images/wait.gif') + ')');
  };

  function search(participants, includeSelf, numTokens, query, withResults) {
    api.port.emit('search_contacts', {q: query, n: 6, participants: participants, includeSelf: includeSelf(numTokens)}, withResults);
  }

  function formatToken(item) {
    var html = ['<li>'];
    if (item.email) {
      html.push('<span class="kifi-ti-email-token-icon"></span>', Mustache.escape(item.email));
    } else {
      html.push(Mustache.escape(item.name));
    }
    html.push('</li>');
    return html.join('');
  }

  function formatResult(res) {
    if (res.pictureName) {
      var html = [
        '<li class="kifi-ti-dropdown-item-token" style="background-image:url(//', cdnBase, '/users/', res.id, '/pics/100/', res.pictureName, ')">'];
      appendParts(html, res.nameParts);
      html.push('</li>');
      return html.join('');
    } else if (res.q) {
      var html = [
        '<li class="', res.isValidEmail ? 'kifi-ti-dropdown-item-token ' : '', 'kifi-ti-dropdown-email kifi-ti-dropdown-new-email">',
        '<div class="kifi-ti-dropdown-contact-name">'];
      appendParts(html, ['', res.q]);
      html.push('</div></li>');
      return html.join('');
    } else if (res.email) {
      var html = [
        '<li class="kifi-ti-dropdown-item-token kifi-ti-dropdown-email kifi-ti-dropdown-contact-email">',
        '<a class="kifi-ti-dropdown-item-x" href="javascript:"></a>'];
      if (res.nameParts) {
        html.push('<div class="kifi-ti-dropdown-contact-name">');
        appendParts(html, res.nameParts);
        html.push('</div><div class="kifi-ti-dropdown-contact-sub">');
      } else {
        html.push('<div class="kifi-ti-dropdown-contact-name">');
      }
      appendParts(html, res.emailParts);
      html.push('</div></li>');
      return html.join('');
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
      return false;
    }
  }

  function getId(o) {
    return o.id;
  }
}());
