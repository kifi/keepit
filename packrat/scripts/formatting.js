// @require scripts/emoji.js
// @require scripts/lib/mustache.js

var formatMessage = (function () {
  'use strict';
  // tip: debuggex.com helps clarify regexes
  var kifiSelMarkdownToLinkRe = /\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:((?:\\\)|[^)])*)\)/;
  var kifiSelMarkdownToTextRe = /\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)/;
  var escapedBackslashOrRightParenRe = /\\([\)\\])/g;
  var escapedBackslashOrRightBracketRe = /\\([\]\\])/g;
  var emailAddrRe = /(?:\b|^)([a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+)(?:\b|$)/;
  var uriRe = /(?:\b|^)((?:(?:(https?|ftp):\/\/|www\d{0,3}[.])?(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|[a-z][a-z]\b))(?::[0-9]{1,5})?(?:\/(?:[^\s()<>]*[^\s`!\[\]{};:.'",<>?«»()“”‘’]|\((?:[^\s()<>]+|(?:\([^\s()<>]+\)))*\))*|\b))(?=[\s`!()\[\]{};:.'",<>?«»“”‘’]|$)/;
  var imageUrlRe = /^[^?#]*\.(?:gif|jpg|jpeg|png)$/i;
  var lineBreaksRe = /\n([ \t\r]*\n)?(?:[ \t\r]*\n)*/g;

  var formatAsHtml =
    processLineBreaksThen.bind(null,
      processKifiSelMarkdownToLinksThen.bind(null,
        processUrlsThen.bind(null,
          processEmailAddressesThen.bind(null,
            processEmoji)),
        processEmoji));

  var formatAsHtmlSnippet =
      processKifiSelMarkdownToTextThen.bind(null,
        processEmoji);

  function renderAndFormatFull(text, render) {
    // Careful... this is raw text with some markdown. Be sure to HTML-escape untrusted portions!
    return formatAsHtml(render(text));
  }

  function renderAndFormatSnippet(text, render) {
    // Careful... this is raw text with some markdown. Be sure to HTML-escape untrusted portions!
    var html = formatAsHtmlSnippet(render(text));
    // TODO: avoid truncating inside a multi-code-point emoji sequence or inside an HTML tag or entity
    return html.length > 200 ? html.substring(0, 190) + '…' : html;
  }

  function processLineBreaksThen(process, text) {
    var parts = text.split(lineBreaksRe);
    var html = ['<div class="kifi-message-p">', process(parts[0])];
    for (var i = 1; i < parts.length; i += 2) {
      html.push(parts[i] ?
        '</div><div class="kifi-message-p kifi-message-pp">' :
        '</div><div class="kifi-message-p">',
        process(parts[i+1]));
    }
    html.push('</div>');
    return html.join('');
  }

  function processKifiSelMarkdownToLinksThen(processBetween, processInside, text) {
    var parts = text.split(kifiSelMarkdownToLinkRe);
    for (var i = 1; i < parts.length; i += 3) {
      var selector = parts[i+1].replace(escapedBackslashOrRightParenRe, '$1');
      var titleAttr = '';
      if (selector.lastIndexOf('r|', 0) === 0) {
        titleAttr = ' title="' + Mustache.escape(formatKifiSelRangeText(selector)) + '"';
      }
      parts[i] = '<a href="x-kifi-sel:' + Mustache.escape(selector) + '"' + titleAttr + '>' +
        processInside(parts[i].replace(escapedBackslashOrRightBracketRe, '$1'));
      parts[i+1] = '</a>';
    }
    for (i = 0; i < parts.length; i += 3) {
      parts[i] = processBetween(parts[i]);
    }
    return parts.join('');
  }

  function processKifiSelMarkdownToTextThen(process, text) {
    var parts = text.split(kifiSelMarkdownToTextRe);
    for (var i = 1; i < parts.length; i += 2) {
      parts[i] = parts[i].replace(escapedBackslashOrRightBracketRe, '$1');
    }
    return process(parts.join(''));
  }

  function processEmailAddressesThen(process, text) {
    if (~text.indexOf('@', 1)) {
      var parts = text.split(emailAddrRe);
      for (var i = 1; i < parts.length; i += 2) {
        var escapedAddr = Mustache.escape(parts[i]);
        parts[i] = '<a href="mailto:' + escapedAddr + '">' + escapedAddr + '</a>';
      }
      for (var i = 0; i < parts.length; i += 2) {
        parts[i] = process(parts[i]);
      }
      return parts.join('');
    } else {
      return process(text);
    }
  }

  function processUrlsThen(process, text) {
    var parts = text.split(uriRe);
    for (var i = 1; i < parts.length; i += 3) {
      var uri = parts[i];
      var scheme = parts[i+1];
      if (!scheme && uri.indexOf('/') < 0 || parts[i-1].slice(-1) === '@') {
        var ambiguous = parts[i-1] + uri;
        var ambiguousProcessed = process(ambiguous);
        if (ambiguousProcessed.indexOf('</a>', ambiguousProcessed.length - 4) > 0) {
          parts[i] = ambiguousProcessed;
          parts[i-1] = parts[i+1] = '';
          continue;
        }
      }
      var escapedUri = Mustache.escape(uri);
      var escapedUrl = (scheme ? '' : 'http://') + escapedUri;
      parts[i] = '<a target="_blank" href="' + escapedUrl + '">' +
        (imageUrlRe.test(uri) ? '<img class="kifi-image-in-message" src="' + escapedUrl + '"/>' : escapedUri);
      parts[i+1] = '</a>';
    }
    for (i = 0; i < parts.length; i += 3) {
      parts[i] = process(parts[i]);
    }
    return parts.join('');
  }

  function processEmoji(text) {
    return Mustache.escape(emoji.supported() ? emoji.decode(text) : text);
  }

  return {
    full: function() {
      return renderAndFormatFull;
    },
    snippet: function () {
      return renderAndFormatSnippet;
    }
  };
}());

var formatKifiSelRangeText = (function () {
  'use strict';
  var replaceRe = /[\u001e\u001f]/g;
  var replacements = {'\u001e': '\n\n', '\u001f': ''};
  function replace(s) {
    return replacements[s];
  }
  return function (selector) {
    return decodeURIComponent(selector.split('|')[6]).replace(replaceRe, replace);
  };
}());

var formatKifiSelRangeTextAsHtml = (function () {
  'use strict';
  var replaceRe = /([\u001e\u001f])/g;
  function replace(replacements, ch) {
    return replacements[ch];
  }
  return function (selector, class1, class2) {
    var pp = '<div class="' + class1 + ' ' + class2 + '">';
    var p = '</div><div class="' + class1 + '">';
    var parts = decodeURIComponent(selector.split('|')[6]).split(replaceRe);
    var html = [pp, Mustache.escape(parts[0]).replace(/\n/g, p)];
    for (var i = 1; i < parts.length; i += 2) {
      if (parts[i] === '\u001e') {
        html.push('</div>', pp);
      }
      html.push(Mustache.escape(parts[i+1]).replace(/\n/g, p));
    }
    html.push('</div>');
    return html.join('');
  };
}());

function formatLocalDate() {
 'use strict';
  return function(text, render) {
    try {
      return new Date(render(text)).toString();
    } catch (e) {
      return '';
    }
  }
}

function formatParticipant(participant) {
  participant.isUser = !participant.kind || participant.kind === 'user';
  participant.isEmail = participant.kind === 'email';
  if (participant.isEmail) {
    participant.initial = participant.id[0].toUpperCase();
    // generate hashcode for background color
    var hash = 0, i, chr, len;
    for (i = 0, len = participant.id.length; i < len; i++) {
      chr = participant.id.charCodeAt(i);
      hash = ((hash << 5) - hash) + chr;
      hash |= 0;
    }
    var numColors = 4;
    switch (((hash%numColors)+numColors)%numColors) {
      case 0:
        participant.color = 'red';
        break;
      case 1:
        participant.color = 'orange';
        break;
      case 2:
        participant.color = 'green';
        break;
      case 3:
        participant.color = 'purple';
        break;
    }
  }
}

function convertHtmlDraftToMarkdown(html) {
  'use strict';
  var html2 = html
    .replace(/<div><br\s*[\/]?><\/div>/gi, '\n')
    .replace(/<br\s*[\/]?>/gi, '\n')
    .replace(/<\/div><div>/gi, '\n')
    .replace(/<div\s*[\/]?>/gi, '\n')
    .replace(/<\/div>/gi, '')
    .replace(/<a(?: [\w-]+="[^"]*")*? href="x-kifi-sel:([^"]*)"(?: [\w-]+="[^"]*")*>(.*?)<\/a>/gi, function($0, $1, $2) {
      return '[' + $2.replace(/([\]\\])/g, '\\$1') + '](x-kifi-sel:' + $1.replace(/([\)\\])/g, '\\$1') + ')';
    });
  html2 = emoji.encode(html2);
  return $('<div>').html(html2).text().trim();
}

function convertTextDraftToMarkdown(text) {
  'use strict';
  return emoji.encode(text).trim();
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
    },
    start_with_emails: function (actor, added) {
      if (isMe(actor)) {
        return 'You started a discussion with ' + boldNamesOf(added) + '.';
      }
      if (added.some(isMe)) {
        return boldNamesOf(meInFront(added)) + ' were invited to a discussion by ' + nameOf(actor) + '.';
      }
      return nameOf(actor) + ' started a discussion with ' + boldNamesOf(added) + '.';
    }
  };

  return function () {
    var arr = this.auxData, formatter = formatters[arr[0]];
    return formatter ? formatter.apply(null, arr.slice(1)) : '';
  };

  function isMe(user) {
    return user.id === k.me.id;
  }

  function bold(html) {
    return '<b>' + html + '</b>';
  }

  function nameOf(user) {
    if (user.kind === "email") {
      return Mustache.escape(user.id);
    } else {
      return isMe(user) ? 'You' : Mustache.escape(user.firstName + ' ' + user.lastName);
    }
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
