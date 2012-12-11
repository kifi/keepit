var snapshot = {

  // Regular expression that matches characters that must not be considered parts of identifiers.
  // Not using the official CSS grammar because the DOM APIs do not enforce it (e.g. el.id can be ":foo").
  delim: /[#\.:> ]/,

  // Generates a detailed CSS 2.1 selector for an element including, at a minimum, the tag name,
  // id (if present), and any classes on the element and all ancestors between it and the body.
  // e.g. "body>section#content>div.wrap>div#content-main.full>article.article>div#wikiArticle.page-content.boxed>table.standard-table>tbody>tr:nth-child(1)>td:nth-child(2)"
  generateSelector: function(el) {
    for (var parts = []; el; el = el.parentNode) {
      var sel = el.localName.toLowerCase();
      if (sel === "body") break;

      var id = el.id;
      if (id && !snapshot.delim.test(id)) sel += "#" + id;

      sel += toArray(el.classList).map(function(c) {return snapshot.delim.test(c) ? "" : "." + c}).join("");

      var children = toArray(el.parentNode.children);
      if (children.some(function(ch) {return ch !== el && ch.webkitMatchesSelector(sel)})) {
        sel += ":nth-child(" + (1 + children.indexOf(el)) + ")";
      }

      parts.unshift(sel);
    }
    parts.unshift("body");
    return parts.join(">");

    function toArray(o) {
      return Array.prototype.slice.apply(o);
    }
  },

  // Attempts to find an element in a document that corresponds to a selector returned by generateSelector
  // using some aribitrarily chosen fuzzy match heuristics. It would be fun to measure how each heuristic
  // performs and to collect information about real failures to gain insight into how to improve this.
  fuzzyFind: function(sel, doc) {
    doc = doc || document; //{querySelectorAll: function(s) { console.log("TRIED: " + s); return []}};
    var els = doc.querySelectorAll(sel);
    switch (els.length) {
      case 0: break;
      case 1: return els[0];
      default: return null;
    }

    // We loosen up the selector slowly because multiple matches is failure.

    // 1. Allow a new ancestor to have been inserted in the parent chain.
    var sel1 = sel.replace(/>/g, " "), ancDelim;
    els = doc.querySelectorAll(sel1);
    switch (els.length) {
      case 0: sel = sel1; ancDelim = " "; break;
      case 1: return els[0];
      default: ancDelim = ">";
    }

    // 2. Allow a single class on any ancestor to have been removed.
    for (var i = sel.indexOf("."); i > 0; i = sel.indexOf(".", i + 1)) {
      var len = sel.substring(i + 1).search(snapshot.delim);
      var sel2 = sel.substring(0, i) + (len < 0 ? "" : sel.substring(i + 1 + len));
      els = doc.querySelectorAll(sel2);
      if (els.length == 1) return els[0];
    }

    // 3. Allow a single tag name change. Skip the first ancestor (body).
    for (var i = sel.indexOf(ancDelim); i > 0; i = sel.indexOf(ancDelim, i + 1)) {
      var len = sel.substring(i + 1).search(snapshot.delim);
      var sel3 = sel.substring(0, i + 1) + "*" + (len < 0 ? "" : sel.substring(i + 1 + len));
      els = doc.querySelectorAll(sel3);
      if (els.length == 1) return els[0];
    }

    // 4. Allow a tag name change on any ancestor that also had a class or id. Skip the first (body).
    var sel4 = sel;
    for (var i = sel4.indexOf(ancDelim); i > 0; i = sel4.indexOf(ancDelim, i + 1)) {
      var len = sel4.substring(i + 1).search(snapshot.delim);
      if (len >= 0 && "#.".indexOf(sel4.charAt(i + 1 + len)) >= 0) {
        sel4 = sel4.substring(0, i + 1) + "*" + sel4.substring(i + 1 + len);
      }
    }
    els = doc.querySelectorAll(sel4);
    switch (els.length) {
      case 0: sel = sel4; break;
      case 1: return els[0];
    }

    // 5. Allow removal of any one ancestor except the first (body) and last (the element itself).
    for (var i = sel.indexOf(ancDelim), j = sel.indexOf(ancDelim, i + 1); j > 0; i = j, j = sel.indexOf(ancDelim, i + 1)) {
      els = doc.querySelectorAll(sel.substring(0, i) + sel.substring(j));
      if (els.length == 1) return els[0];
    }

    // 6. Allow removal of any number of selector parts, beginning at the front of the selector (body).
    var sel6 = sel;
    for (var i = sel6.search(snapshot.delim); i > 0; i = sel6.substring(1).search(snapshot.delim) + 1) {
      sel6 = sel6.substring(i + (sel6.charAt(i) == ancDelim ? 1 : 0));
      if (sel6.charAt(0) == "*") sel6 = sel6.substring(1);
      els = doc.querySelectorAll(sel6);
      switch (els.length) {
        case 0: break; // proceed
        case 1: return els[0];
        default: return null;  // a less specific selector will never find fewer matches
      }
    }

    return null;
  }
};
