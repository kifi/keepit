var snapshot = function () {
  'use strict';

  // Characters that must not be considered part of names/tokens when parsing a generated selector.
  // ' ' is not here because it terminates a unicode escape and because generated selectors
  // never use the ancestor combinator.
  var delimRe = /[#\.:>\t]/;

  // HTML IDs and class names may not contain any space characters.
  // www.w3.org/TR/html5/dom.html#the-id-attribute
  // www.w3.org/TR/html5/dom.html#classes
  // www.w3.org/TR/html5/infrastructure.html#space-character
  var spaceRe = /\s/;

  // Names in selectors must have all ASCII characters except [_a-zA-Z0-9-] escaped.
  // Non-ASCII characters do not require escaping.
  // www.w3.org/TR/selectors/#lex
  var escapeCharRe = /[\0-,.\/:-@[-^`{-~]/g;

  // Uses unicode escapes instead of simple backslash escapes to keep selector parsing simpler.
  function escapeCharReplace(ch) {
    // terminating space used because any trailing space is greedily assumed to be part of the
    // escape sequence and we don't want a descendant combinator to be mistaken for part of a sequence
    return '\\' + ch.charCodeAt(0).toString(16) + ' ';
  }

  // Escapes a selector name/token.
  function escapeToken(token) {
    // The first character has extra restrictions. It must be escaped if not [_a-zA-Z].
    var code = token.charCodeAt(0);
    if (code == 45) { // a leading hyphen (-) must be escaped, can be backslash-escaped
      return '\\' + token.replace(escapeCharRe, escapeCharReplace);
    }
    if (code >= 48 && code <= 57) { // a leading digit (0-9) must be unicode-escaped
      return escapeCharReplace(token[0]) + token.substr(1).replace(escapeCharRe, escapeCharReplace);
    }
    return token.replace(escapeCharRe, escapeCharReplace);
  }

  // Generates a detailed CSS selector for an element including, at a minimum, the tag name,
  // id (if present), and any classes on the element and all ancestors between it and scopeEl.
  // e.g. "body>section#content>div.wrap>div#content-main.full>article.article>div#wikiArticle.page-content.boxed>table.standard-table>tbody>tr:nth-child(1)>td:nth-child(2)"
  function generateSelector(el, scopeEl, scopeName) {
    scopeEl = scopeEl || document.body;
    scopeName = scopeName === undefined ? scopeEl.localName.toLowerCase() : scopeName;
    var matchesSelector = el.mozMatchesSelector ? 'mozMatchesSelector' : 'webkitMatchesSelector';
    for (var parts = []; el && el !== scopeEl; el = el.parentNode) {
      var sel = el.localName.toLowerCase();
      var id = el.id;
      if (id && !spaceRe.test(id)) sel += '#' + escapeToken(id);

      sel += slice(el.classList).map(function (c) {return '.' + escapeToken(c)}).join('');

      var children = slice(el.parentNode.children);
      if (children.some(function (ch) {return ch !== el && ch[matchesSelector](sel)})) {
        sel += ':nth-child(' + (1 + children.indexOf(el)) + ')';
      }

      parts.unshift(sel);
    }
    if (scopeName != null) {
      parts.unshift(scopeName);
    }
    return parts.join('>');
  }

  function elementSelfOrParent(node) {
    return node.nodeType === 1 ? node : node.parentNode;
  }

  var slice = Array.slice || Function.call.bind(Array.prototype.slice);
  var indexOf = Array.indexOf || Function.call.bind(Array.prototype.indexOf);
  var scopeChild = ':scope>';
  try {
    document.querySelector(scopeChild + '*'); // bugzil.la/528456
  } catch (e) {
    scopeChild = '';
  }

  return {
    // Attempts to find an element in a document that corresponds to a selector returned by generateSelector
    // using some aribitrarily chosen fuzzy match heuristics. It would be fun to measure how each heuristic
    // performs and to collect information about real failures to gain insight into how to improve this.
    fuzzyFind: function (sel, doc) {
      doc = doc || document; //{querySelectorAll: function(s) { log('TRIED: ' + s); return []}}();
      var els = doc.querySelectorAll(sel);
      switch (els.length) {
        case 0: break;
        case 1: return els[0];
        default: return null;
      }

      // use tab instead of space to terminate unicode escape sequences so that space can always signify
      // the descendant combinator
      sel = sel.replace(/ /g, '\t');

      // We loosen up the selector slowly because multiple matches is failure.

      // 1. Allow a new ancestor to have been inserted in the parent chain.
      //    Note: using \t for descendant combinator and space to terminate unicode escape sequences
      var sel1 = sel.replace(/>/g, '\t'), ancDelim;
      els = doc.querySelectorAll(sel1);
      switch (els.length) {
        case 0: sel = sel1; ancDelim = '\t'; break;
        case 1: return els[0];
        default: ancDelim = '>';
      }

      // 2. Allow a single class on any ancestor to have been removed.
      for (var i = sel.indexOf('.'); i > 0; i = sel.indexOf('.', i + 1)) {
        var len = sel.substr(i + 1).search(delimRe);
        var sel2 = sel.substr(0, i) + (len < 0 ? '' : sel.substr(i + 1 + len));
        els = doc.querySelectorAll(sel2);
        if (els.length == 1) return els[0];
      }

      // 3. Allow a single tag name change. Skip the first ancestor (body).
      for (var i = sel.indexOf(ancDelim); i > 0; i = sel.indexOf(ancDelim, i + 1)) {
        var len = sel.substr(i + 1).search(delimRe);
        var sel3 = sel.substr(0, i + 1) + '*' + (len < 0 ? '' : sel.substr(i + 1 + len));
        els = doc.querySelectorAll(sel3);
        if (els.length == 1) return els[0];
      }

      // 4. Allow a tag name change on any ancestor that also had a class or id. Skip the first (body).
      var sel4 = sel;
      for (var i = sel4.indexOf(ancDelim); i > 0; i = sel4.indexOf(ancDelim, i + 1)) {
        var len = sel4.substr(i + 1).search(delimRe);
        if (len >= 0 && '#.'.indexOf(sel4.charAt(i + 1 + len)) >= 0) {
          sel4 = sel4.substr(0, i + 1) + '*' + sel4.substr(i + 1 + len);
        }
      }
      els = doc.querySelectorAll(sel4);
      switch (els.length) {
        case 0: sel = sel4; break;
        case 1: return els[0];
      }

      // 5. Allow removal of any one ancestor except the first (body) and last (the element itself).
      for (var i = sel.indexOf(ancDelim), j = sel.indexOf(ancDelim, i + 1); j > 0; i = j, j = sel.indexOf(ancDelim, i + 1)) {
        els = doc.querySelectorAll(sel.substr(0, i) + sel.substr(j));
        if (els.length == 1) return els[0];
      }

      // 6. Allow removal of any number of selector parts, beginning at the front of the selector (body).
      var sel6 = sel;
      for (var i = sel6.search(delimRe); i > 0; i = sel6.substr(1).search(delimRe) + 1) {
        sel6 = sel6.substr(i + (sel6.charAt(i) == ancDelim ? 1 : 0));
        if (sel6.charAt(0) == '*') sel6 = sel6.substr(1);
        els = doc.querySelectorAll(sel6);
        switch (els.length) {
          case 0: break; // proceed
          case 1: return els[0];
          default: return null;  // a less specific selector will never find fewer matches
        }
      }

      return null;
    },

    ofRange: function (r, text) {
      var an = r.commonAncestorContainer;
      var sc = r.startContainer;
      var so = r.startOffset;
      var ec = r.endContainer;
      var eo = r.endOffset;

      var ane = elementSelfOrParent(an);
      var sce = elementSelfOrParent(sc);
      var ece = elementSelfOrParent(ec);
      return ['r',
        generateSelector(ane),
        sce === ane ? '' : generateSelector(sce, ane, null).replace(/ /g, escape), // most ASCII already \-unicode-escaped
        sc === sce ? so : indexOf(sce.childNodes, sc) + ':' + so,
        ece === ane ? '' : generateSelector(ece, ane, null).replace(/ /g, escape), // most ASCII already \-unicode-escaped
        ec === ece ? eo : indexOf(ece.childNodes, ec) + ':' + eo,
        text.replace(/[ \t\r\n\f%|]/g, escape)   // TODO: identify text node boundaries?
      ].join('|');
    },

    findRange: function (selector) {
      var parts = selector.split('|');
      var ane = snapshot.fuzzyFind(parts[1]);
      if (ane) {
        var sce = parts[2] ? ane.querySelector(scopeChild + parts[2]) : ane;
        var ece = parts[4] ? ane.querySelector(scopeChild + parts[4]) : ane;
        if (sce && ece) {
          var sos = parts[3].split(':');
          var eos = parts[5].split(':');
          var sc = sos.length > 1 ? sce.childNodes[sos[0]] : sce;
          var ec = eos.length > 1 ? ece.childNodes[eos[0]] : ece;
          try {
            var r = document.createRange();
            r.setStart(sc, sos.pop());
            r.setEnd(ec, eos.pop());
            return r;
          } catch (e) {
            log('[snapshot.findRange]', e)();
          }
        }
      }
      return null;
    }
  }
}();
