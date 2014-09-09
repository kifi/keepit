// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/mustache.js
// @require scripts/render.js

var initFriendSearch = (function () {

  return function ($in, source, participants, includeSelf, options) {
    $in.tokenInput(search.bind(null, participants, includeSelf), $.extend({
      preventDuplicates: true,
      tokenValue: 'id',
      classPrefix: 'kifi-ti-',
      formatResult: formatResult,
      formatToken: formatToken,
      onSelect: onSelect.bind(null, source),
      onRemove: onRemove
    }, options));
  };

  function search(participants, includeSelf, numTokens, query, withResults) {
    var n = Math.max(3, Math.min(8, Math.floor((window.innerHeight - 365) / 55)));  // quick rule of thumb
    api.port.emit('search_contacts', {q: query, n: n, participants: participants, includeSelf: includeSelf(numTokens)}, function (contacts) {
      if (contacts.length < 3) {
        contacts.push('tip');
      }
      withResults(contacts);
    });
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
        '<li class="kifi-ti-dropdown-item-token" style="background-image:url(//', cdnBase, '/users/', res.id, '/pics/100/', res.pictureName, ')">',
        '<div class="kifi-ti-dropdown-line-1">'];
      appendParts(html, res.nameParts);
      html.push(
        '</div>',
        '<div class="kifi-ti-dropdown-line-2">',
        res.id === 'aa345838-70fe-45f2-914c-f27c865bdb91' ? 'Kifi team' : 'Kifi user',
        '</div></li>');
      return html.join('');
    } else if (res.q) {
      var html = [
        '<li class="', res.isValidEmail ? 'kifi-ti-dropdown-item-token ' : '', 'kifi-ti-dropdown-email kifi-ti-dropdown-new-email">',
        '<div class="kifi-ti-dropdown-line-1">'];
      appendParts(html, ['', res.q]);
      html.push(
        '</div>',
        '<div class="kifi-ti-dropdown-line-2">',
        res.isValidEmail ? 'An email address' : 'Keep typing the email address',
        '</div></li>');
      return html.join('');
    } else if (res.email) {
      var html = [
        '<li class="kifi-ti-dropdown-item-token kifi-ti-dropdown-email kifi-ti-dropdown-contact-email">',
        '<a class="kifi-ti-dropdown-item-x" href="javascript:"></a>',
        '<div class="kifi-ti-dropdown-line-1">'];
      if (res.nameParts) {
        appendParts(html, res.nameParts);
        html.push('</div><div class="kifi-ti-dropdown-line-2">');
        appendParts(html, res.emailParts);
      } else {
        appendParts(html, res.emailParts);
        html.push('</div><div class="kifi-ti-dropdown-line-2">An email contact');
      }
      html.push('</div></li>');
      return html.join('');
    } else if (res === 'tip') {
      return [
        '<li class="kifi-ti-dropdown-tip">',
        '<div class="kifi-ti-dropdown-line-1">Import Gmail contacts</div>',
        '</li>'].join('');
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

  function onSelect(source, res, el) {
    if (res.isValidEmail) {
      res.id = res.email = res.q;
    } else if (!res.pictureName && !res.email) {
      if (res === 'tip') {
        api.port.emit('import_contacts', source);
      }
      return false;
    }
  }

  function onRemove(item, replaceWith) {
    $('.kifi-ti-dropdown-item-waiting')
      .addClass('kifi-ti-dropdown-email')
      .css('background-image', 'url(' + api.url('images/wait.gif') + ')');
    api.port.emit('delete_contact', item.email, function (success) {
      replaceWith(success ? null : item);
    });
  }
}());
