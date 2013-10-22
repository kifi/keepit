function getTextFormatter() {
 'use strict';
  return function(text, render) {
    // Careful... this is raw text (necessary for URL detection). Be sure to Mustache.escape untrusted portions!
    text = render(text);

    // linkify look-here links (from markdown)
    var parts = text.split(/\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:((?:\\\)|[^)])*)\)/);
    for (var i = 1; i < parts.length; i += 3) {
      parts[i] = "<a href='x-kifi-sel:" + parts[i+1].replace(/\\\)/g, ")") + "'>" + Mustache.escape(parts[i].replace(/\\\]/g, "]")) + "</a>";
      parts[i+1] = "";
    }

    for (i = 0; i < parts.length; i += 3) {
      // linkify URLs, use https://www.debuggex.com/ for help
      var bits = parts[i].split(/(?:\b|^)((?:(?:(https?|ftp):\/\/|www\d{0,3}[.])?(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|[a-z][a-z]\b))(?::[0-9]{1,5})?(?:\/(?:[^\s()<>]*[^\s`!\[\]{};:.'",<>?«»()“”‘’]|\((?:[^\s()<>]+|(?:\([^\s()<>]+\)))*\))*|\b))(?=[\s`!()\[\]{};:.'",<>?«»“”‘’]|$)/);
      for (var j = 1; j < bits.length; j += 3) {
        var escapedUri = Mustache.escape(bits[j]);
        bits[j] = '<a target=_blank href="' + (bits[j+1] ? ""  : "http://") + escapedUri + '">' + escapedUri + "</a>";
        bits[j+1] = "";
      }
      for (j = 0; j < bits.length; j += 3) {
        bits[j] = Mustache.escape(bits[j]);
      }
      parts[i] = bits.join("");
    }

    return "<p>" + parts.join("").replace(/\n(?:[ \t\r]*\n)*/g, "</p><p>") + "</p>";
  }
}

function getSnippetFormatter() {
 'use strict';
  return function(text, render) {
    // Careful... this is raw text (necessary for URL detection). Be sure to Mustache.escape untrusted portions!
    text = render(text);

    // plain-textify look-here links (from markdown)
    var parts = text.split(/\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)/);
    for (var i = 1; i < parts.length; i += 2) {
      parts[i] = parts[i].replace(/\\\]/g, "]");
    }

    var escaped = Mustache.escape(parts.join(""));
    return escaped.length > 200 ? escaped.substring(0, 190) + "…" : escaped;
  }
}

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

function auxDataFormatter() {
  var auxData = this.auxData;
  switch (auxData[0]) {
    case 'add_participants':
      return addParticipantsFormatter(auxData[1], auxData[2]);
  }
  return '';
}

function nameFormatter(user) {
  return Mustache.escape(user.firstName + ' ' + user.lastName);
}

function namesFormatter(users) {
  switch (users.length) {
  case 0:
    return '';
  case 1:
    return nameFormatter(users[0]);
  case 2:
    return nameFormatter(users[0]) + ' and ' + nameFormatter(users[1]);
  default:
    var lastIndex = users.length - 1;
    return users.slice(0, lastIndex).map(nameFormatter).join(', ') + ', and ' + nameFormatter(users[lastIndex]);
  }
}

function addParticipantsFormatter(actor, addedUsers) {
  return nameFormatter(actor) + ' added ' + namesFormatter(addedUsers) + ' to the conversation.';
}
