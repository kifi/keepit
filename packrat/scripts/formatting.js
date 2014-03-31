var getTextFormatter = (function () {
  'use strict';
  // tip: debuggex.com helps clarify regexes
  var kifiSelMarkdownLinkRe = /\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:((?:\\\)|[^)])*)\)/;
  var escapedRightParenRe = /\\\)/g;
  var escapedRightBracketRe = /\\\]/g;
  var emailAddrRe = /(?:\b|^)([a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*)(?:\b|$)/;
  var uriRe = /(?:\b|^)((?:(?:(https?|ftp):\/\/|www\d{0,3}[.])?(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|[a-z][a-z]\b))(?::[0-9]{1,5})?(?:\/(?:[^\s()<>]*[^\s`!\[\]{};:.'",<>?«»()“”‘’]|\((?:[^\s()<>]+|(?:\([^\s()<>]+\)))*\))*|\b))(?=[\s`!()\[\]{};:.'",<>?«»“”‘’]|$)/;
  var imageUrlRe = /^[^?#]*\.(?:gif|jpg|jpeg|png)$/i;
  var lineBreaksRe = /\n([ \t\r]*\n)?(?:[ \t\r]*\n)*/g;

  function format(text, render) {
    // Careful... this is raw text with some markdown. Be sure to HTML-escape untrusted portions!
    return processLineBreaks(processLookHereLinksEtc(render(text)));
  }

  function processLookHereLinksEtc(text) {
    var parts = text.split(kifiSelMarkdownLinkRe);
    for (var i = 1; i < parts.length; i += 3) {
      parts[i] = '<a href="x-kifi-sel:' + parts[i+1].replace(escapedRightParenRe, ')') + '">' +
        Mustache.escape(parts[i].replace(escapedRightBracketRe, ']'));
      parts[i+1] = '</a>';
    }
    for (i = 0; i < parts.length; i += 3) {
      parts[i] = processEmailAddressesEtc(parts[i]);
    }
    return parts.join('');
  }

  function processEmailAddressesEtc(text) {
    if (~text.indexOf('@', 1)) {
      var parts = text.split(emailAddrRe);
      for (var i = 1; i < parts.length; i += 2) {
        var escapedAddr = Mustache.escape(parts[i]);
        parts[i] = '<a href="mailto:' + escapedAddr + '">' + escapedAddr + '</a>';
      }
      for (var i = 0; i < parts.length; i += 2) {
        parts[i] = processUrlsEtc(parts[i]);
      }
      return parts.join('');
    } else {
      return processUrlsEtc(text);
    }
  }

  function processUrlsEtc(text) {
    var parts = text.split(uriRe);
    for (var i = 1; i < parts.length; i += 3) {
      var uri = parts[i];
      var scheme = parts[i+1];
      var escapedUri = Mustache.escape(uri);
      var escapedUrl = (scheme ? '' : 'http://') + escapedUri;
      parts[i] = '<a target="_blank" href="' + escapedUrl + '">' +
        (imageUrlRe.test(uri) ? '<img class="kifi-image-in-message" src="' + escapedUrl + '"/>' : escapedUri);
      parts[i+1] = '</a>';
    }
    for (i = 0; i < parts.length; i += 3) {
      parts[i] = Mustache.escape(parts[i]);
    }
    return parts.join('');
  }

  function processLineBreaks(html) {
    return '<div class="kifi-message-p">' + html.replace(lineBreaksRe, getLineBreakSubstitution) + '</div>';
  }

  function getLineBreakSubstitution(_, multiple) {
    return multiple ?
      '</div><div class="kifi-message-p kifi-message-pp">' :
      '</div><div class="kifi-message-p">';
  }

  return function() {
    return format;
  };
}());

var getSnippetFormatter = (function () {
  'use strict';
  var kifiSelMarkdownLinkRe = /\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)/;
  var escapedRightBracketRe = /\\\]/g;
  return function () {
    return function(text, render) {
      // Careful... this is raw text (necessary for URL detection). Be sure to Mustache.escape untrusted portions!
      text = render(text);

      // plain-textify look-here links (from markdown)
      var parts = text.split(kifiSelMarkdownLinkRe);
      for (var i = 1; i < parts.length; i += 2) {
        parts[i] = parts[i].replace(escapedRightBracketRe, ']');
      }

      var escaped = Mustache.escape(parts.join(''));
      return escaped.length > 200 ? escaped.substring(0, 190) + '…' : escaped;
    };
  };
}());

function getLocalDateFormatter() {
 'use strict';
  return function(text, render) {
    try {
      return new Date(render(text)).toString();
    } catch (e) {
      return "";
    }
  }
}

function convertDraftToText(html) {
 'use strict';
  html = html
    .replace(/<div><br\s*[\/]?><\/div>/gi, '\n')
    .replace(/<br\s*[\/]?>/gi, '\n')
    .replace(/<\/div><div>/gi, '\n')
    .replace(/<div\s*[\/]?>/gi, '\n')
    .replace(/<\/div>/gi, '')
    .replace(/<a [^>]*\bhref="x-kifi-sel:([^"]*)"[^>]*>(.*?)<\/a>/gi, function($0, $1, $2) {
      return "[" + $2.replace(/\]/g, "\\]") + "](x-kifi-sel:" + $1.replace(/\)/g, "\\)") + ")";
    });
  return $('<div>').html(html).text().trim();
}

var formatAuxData = (function () {
  'use strict';
  var formatters = {
    add_participants: function (actor, added) {
      if (isMe(actor)) {
        return 'You added ' + boldNamesOf(added) + '.';
      }
      if (added.some(isMe)) {
        return boldNamesOf(meInFront(added)) + ' were added by ' + nameOf(actor) + '.';
      }
      return nameOf(actor) + ' added ' + boldNamesOf(added) + '.';
    }
  };

  return function () {
    var arr = this.auxData, formatter = formatters[arr[0]];
    return formatter ? formatter.apply(null, arr.slice(1)) : '';
  };

  function isMe(user) {
    return user.id === me.id;
  }

  function bold(html) {
    return '<b>' + html + '</b>';
  }

  function nameOf(user) {
    return isMe(user) ? 'You' : Mustache.escape(user.firstName + ' ' + user.lastName);
  }

  function boldNamesOf(users) {
    var names = users.map(nameOf).map(bold);
    if (users.length <= 2) {
      return names.join(' and ');
    } else {
      var last = names.pop();
      return names.join(', ') + ' and ' + last;
    }
  }

  function meInFront(users) {
    var arr = users.slice();
    for (var i = 1; i < arr.length; i++) {
      var user = arr[i];
      if (isMe(user)) {
        arr.splice(i, 1);
        arr.unshift(user);
        break;
      }
    }
    return arr;
  }
}());
