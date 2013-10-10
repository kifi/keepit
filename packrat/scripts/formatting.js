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
      // linkify URLs, from http://regex.info/listing.cgi?ed=3&p=207
      var bits = parts[i].split(/\b(\(?(?:(https?|ftp):\/\/)?(?:(?:(?:[^\W\s]|\.|-|[:]{1})+)@{1})?(?:(?:www.)?(?:[^\W\s]|\.|-)+[\.][^\W\s]{2,4}|\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?::(?:\d*))?(?:[\/]?[^\s\?]*[\/]{1})*(?:\/?(?:[^\s\n\?\[\]\{\}\#]*(?:(?=\.)){1}|[^\s\n\?\[\]\{\}\.\#]*)?(?:[\.]{1}[^\s\?\#]*)?)?(?:\?{1}(?:[^\s\n\#\[\]]*))?(?:[\#][^\s\n]*)?\)?[^\.\s\n])/);
      for (var j = 1; j < bits.length; j += 3) {
        var escapedUri = Mustache.escape(bits[j]);
        if (escapedUri.charAt(0) == '(') {
          bits[j-1] = bits[j-1] + '(';
          escapedUri = escapedUri.slice(1);
          if (escapedUri.charAt(escapedUri.length - 1) == ')') {
            bits[j+2] = ')' + bits[j+2];
            escapedUri = escapedUri.slice(0,-1);
          }
        }
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
    .replace(/\u200b/g, '')  // zero-width spaces (we insert one as a webkit focus bug workaround)
    .replace(/<a [^>]*\bhref="x-kifi-sel:([^"]*)"[^>]*>(.*?)<\/a>/gi, function($0, $1, $2) {
      return "[" + $2.replace(/\]/g, "\\]") + "](x-kifi-sel:" + $1.replace(/\)/g, "\\)") + ")";
    });
  return $('<div>').html(html).text().trim();
}
