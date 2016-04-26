// @require scripts/lib/purify.js
// @require scripts/emoji.js
// @require scripts/lib/mustache.js

// TODO(carlos): Turn this into a legit module instead
// of just exposing globals all over the place

var k = k && k.kifi ? k : {kifi: true};

k.formatting = k.formatting || (function () {
  return {
    jsonDom: jsonDom,
    parseStringToElement: parseStringToElement,
    formatLibraryResult: formatLibraryResult
  };

  // Inspired by http://stackoverflow.com/questions/12980648
  function jsonDom(element, wrapper) {
    if (typeof element === 'string') {
      element = parseStringToElement(element, wrapper);
    }
    return treeHTML(element);
  }

  function parseStringToElement(htmlString, wrapper) {
    wrapper = wrapper || 'span';
    htmlString = DOMPurify.sanitize(htmlString, {
      ALLOW_UNKNOWN_PROTOCOLS: true // for x-kifi-sel "look here"s
    });

    var parser = new DOMParser();
    var docNode = parser.parseFromString(htmlString, 'text/html'); // wraps input in <html><body> tags
    var element;

    if (isParseError(docNode)) {
      throw new Error('Error parsing HTML: ' + element + '\n' + docNode.firstChild.innerHTML);
    }

    var body = docNode.body;
    var hasRootElement = (body.childNodes.length === 1 && body.firstChild.nodeType !== 3);
    if (hasRootElement) {
      element = body.firstChild;
    } else {
      element = (wrapper === 'span' ? document.createElement('span') : document.createElement('div'));
      Array.prototype.slice.apply(docNode.body.childNodes).forEach(function (n) {
        element.appendChild(n);
      });
    }

    return element;
  }

  // Recursively loop through DOM elements and assign properties to object
  function treeHTML(element) {
    var nodeList = (element.childNodes ? Array.prototype.slice.call(element.childNodes) : []);
    var attributeList = (element.attributes ? Array.prototype.slice.call(element.attributes) : []);
    var isLeaf = (nodeList.length === 0 || (nodeList.length === 1 && nodeList[0].nodeType === 3));

    return {
      tagName: element.nodeName,
      children: (nodeList.length > 0 ? nodeList.map(mapNode) : ''),
      attributes: attributeList.map(mapAttribute),
      leaf: isLeaf
    };
  }

  function mapNode(node, i, arr) {
    if (node.nodeType === 3) { // text node
      if (arr.length === 1) {
        return node.nodeValue;
      } else {
        return {
          tagName: 'span',
          children: node.nodeValue,
          attributes: [],
          leaf: true
        };
      }
    } else {
      return treeHTML(node);
    }
  }

  function mapAttribute(attribute) {
    return {
      name: attribute.nodeName,
      value: attribute.nodeValue
    };
  }

  // Inspired by http://stackoverflow.com/questions/11563554
  var PARSER_ERROR_NS = (function () {
    var parser = new DOMParser();
    var badParse = parser.parseFromString('<', 'text/xml');
    return badParse.getElementsByTagName('parsererror')[0].namespaceURI;
  }());

  function isParseError(parsedDocument) {
    if (PARSER_ERROR_NS === 'http://www.w3.org/1999/xhtml') {
      return parsedDocument.getElementsByTagName('parsererror').length > 0;
    } else {
      return parsedDocument.getElementsByTagNameNS(PARSER_ERROR_NS, 'parsererror').length > 0;
    }
  }
}());

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
          processUrlsThen.bind(null, // processBetween
            processEmailAddressesThen.bind(null,
              processHashtagsThen.bind(null, true, // yes, make tags into links
                processEmojiThen.bind(null, identity)))),
          processHashtagsThen.bind(null, true, // processInside
            processEmojiThen.bind(null, identity))));

  var formatAsHtmlSnippet =
      processKifiSelMarkdownToTextThen.bind(null,
        processHashtagsThen.bind(null, false, // no, do not make tags into links
          processEmojiThen.bind(null, identity)));

  function renderAndFormatFull(text, render) {
    text = text || '';

    if (render) {
      text = render(text);
    }
    // Careful... this is raw text with some markdown. Be sure to HTML-escape untrusted portions!
    return k.formatting.jsonDom(formatAsHtml(text));
  }

  function renderAndFormatSnippet(text, render) {
    text = text || '';

    if (render) {
      text = render(text);
    }
    // Careful... this is raw text with some markdown. Be sure to HTML-escape untrusted portions!
    var html = formatAsHtmlSnippet(text);
    // TODO: avoid truncating inside a multi-code-point emoji sequence or inside an HTML tag or entity
    html = (html.length > 200 ? html.substring(0, 190) + '…' : html);
    return k.formatting.jsonDom(html);
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
      var isTruncated = (escapedUri.length > 40);
      parts[i] = '<a target="_blank" href="' + escapedUrl + '">' +
        (imageUrlRe.test(uri) ? '<img class="kifi-image-in-message" onerror="this.outerHTML=this.src" src="' + escapedUrl + '"/>' : (isTruncated ? escapedUrl.slice(0, 40) + '…' : escapedUri));
      parts[i+1] = '</a>';
    }
    for (i = 0; i < parts.length; i += 3) {
      parts[i] = process(parts[i]);
    }
    return parts.join('');
  }

  function processEmojiThen(process, text) {
    return process(Mustache.escape(emoji.supported() ? emoji.decode(text) : text));
  }

  var hashTagMarkdownRe = /\[#((?:\\.|[^\]])*)\]/g;
  var multipleBlankLinesRe = /\n(?:\s*\n)+/g;
  var escapedLeftBracketHashOrAtRe = /\[\\([#@])/g;
  var backslashUnescapeRe = /\\(.)/g;
  function processHashtagsThen(doLink, process, text) {
    var parts = text.replace(multipleBlankLinesRe, '\n\n').split(hashTagMarkdownRe);
    var tag;
    for (var i = 1; i < parts.length; i += 2) {
      tag = Mustache.escape(parts[i].replace(backslashUnescapeRe, '$1'));
      if (doLink) {
        parts[i] = '<a class="kifi-tag" href="' + getTagUrl(tag) + '"target="_blank">#' + tag + '</a>';
      } else {
        parts[i] = '<span class="kifi-tag">#' + tag + '</span>';
      }
    }
    for (i = 0; i < parts.length; i += 2) {
      parts[i] = process(parts[i].replace(escapedLeftBracketHashOrAtRe, '[$1'));
    }
    return parts.join('');
  }

  function getTagUrl(tag) {
    return 'https://www.kifi.com/find?q=tag:' + tag;
  }

  function identity(o) { return o; }

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
  var newlineRe = /\n/g;

  function getPart(content, class1, class2) {
    return {
      content: content,
      class1: class1,
      class2: class2
    };
  }

  function pushLine(parts, class1, class2, c) {
    parts.push(getPart(c, class1, class2));
  }

  return function (selector, class1, class2) {
    var lookHereTextItems = decodeURIComponent(selector.split('|')[6]).split(replaceRe);
    var parts = [ getPart(lookHereTextItems[0], class1, class2) ];
    var lines;
    var isPP;
    var isP;

    for (var i = 1; i < lookHereTextItems.length; i += 2) {
      lines = lookHereTextItems[i + 1] && lookHereTextItems[i + 1].split(newlineRe);
      isPP = (lookHereTextItems[i] === '\u001e');
      isP = (lines.length > 1);

      if (!isP && !isPP) {
        parts[parts.length - 1].content += lines[0];
      } else {
        lines.forEach(pushLine.bind(null, parts, class1, isPP && class2));
      }
    }

    return parts;
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

function formatLibraryResult(options, lib) {
  lib = (lib && typeof lib !== 'object' ? options : lib);
  options = options || {};

  function smartlyListCollaboratorNames(collabs) {
    var names = [ 'Me' ]; // max 15 characters
    var charCount = 2;

    while (charCount < 15 && collabs.length > 0) {
      var collab = collabs.shift();
      charCount += (collab.firstName.length + 2); // 2 for space and comma
      names.push(collab.firstName);
    }

    if (collabs.length > 1) {
      names.push(collabs.length + ' others');
    } else if (collabs.length === 1) {
      names.push(collabs[0].firstName);
    }

    return names.join(', ');
  }

  function isOpenCollaborationCandidate(library) {
    var isOrgLibrary = !!library.orgAvatar;

    var isAlreadyJoined = (
      library && library.membership && (
        library.membership.access === 'owner' ||
        library.membership.access === 'read_write'
      )
    );

    return isOrgLibrary && !isAlreadyJoined;
  }

  // Add a property that the keep_box_lib template can use later on
  // to determine whether a lib is allowed in open collaboration
  if (options.showHintText && isOpenCollaborationCandidate(lib)) {
    lib.isOpenCollaborationCandidate = true;
  }

  if (options.showHintText && lib.system) {
    lib.extraInfo = MOD_KEYS.c + '-Shift-' + (lib.visibility === 'secret' ? MOD_KEYS.alt + '-' : '') + 'K';
  } else if (lib.hasCollaborators) {
    lib.extraInfo = smartlyListCollaboratorNames(lib.collaborators);
  }
}

function formatParticipant(participant) {
  participant.isEmail = participant.kind === 'email' || participant.email;
  participant.isUser = !participant.isEmail && (!participant.kind || participant.kind === 'user' || participant.kind === 'kifi');
  participant.isLibrary = participant.kind === 'library';

  var id = participant.id || participant.email;

  if (participant.isLibrary && !participant.color) {
    participant.color = '#808080';
  }

  if (participant.isEmail) {
    participant.initial = id[0].toUpperCase();
    // generate hashcode for background color
    var hash = 0, i, chr, len;
    for (i = 0, len = id.length; i < len; i++) {
      chr = id.charCodeAt(i);
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
    })
    .replace(/<span(?: [\w-]+="[^"]*")*? class="[^"]*?kifi-tag[^"]*"(?: [\w-]+="[^"]*")*>(.*?)<\/span>/gi, function($0, $1, $2) {
      return '[' + $1 + ']';
    });
  html2 = emoji.encode(html2);

  var elem = k.formatting.parseStringToElement(html2, 'div');
  return elem.textContent.trim();
}

function convertTextDraftToMarkdown(text) {
  'use strict';
  return emoji.encode(text).trim();
}
