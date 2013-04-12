function getTextFormatter() {
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
      var bits = parts[i].split(/(\b(?:(ftp|https?):\/\/[-\w]+(?:\.\w[-\w]*)+|(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|[a-z][a-z]\b))(?::[0-9]{1,5})?(?:\/[^.!,?;"'<>()\[\]{}\s\x7F-\xFF]*(?:[.!,?]+[^.!,?;"'<>()\[\]{}\s\x7F-\xFF]+)*)?)/);
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
  return function(text, render) {
    // Careful... this is raw text (necessary for URL detection). Be sure to Mustache.escape untrusted portions!
    text = render(text);

    // plain-textify look-here links (from markdown)
    var parts = text.split(/\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)/);
    for (var i = 1; i < parts.length; i += 2) {
      parts[i] = parts[i].replace(/\\\]/g, "]");
    }

    return Mustache.escape(parts.join(""));
  }
}

function getLocalDateFormatter() {
  return function(text, render) {
    try {
      return new Date(render(text)).toString();
    } catch (e) {
      return "";
    }
  }
}

function getIsoDateFormatter() {
  return function(text, render) {
    try {
      return new Date(render(text)).toISOString();
    } catch (e) {
      return "";
    }
  }
}

function convertDraftToText(html) {
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
