var snapshot = {

  // Characters that must not be considered part of names/tokens.
  delim: /[#\.:> ]/,

  // HTML IDs and class names may not contain spaces.
  // http://www.w3.org/TR/html5/global-attributes.html#the-id-attribute
  // http://www.w3.org/TR/html5/global-attributes.html#classes
  // http://www.w3.org/TR/html5/common-microsyntaxes.html#space-character
  space: /\s/,

  // Names in selectors must have all ASCII characters except [_a-zA-Z0-9-] escaped.
  // Non-ASCII characters do not require escaping.
  // http://www.w3.org/TR/selectors/#lex
  escapeChar: /[\0-,.\/:-@[-^`{-~]/g,

  // Escapes a selector name/token.
  escape: function(name) {
    // The first character has extra restrictions. It must be escaped if not [_a-zA-Z].
    var code = name.charCodeAt(0);
    if (code == 45) // a leading hyphen (-) must be escaped, can be backslash-escaped
      return "\\" + name.replace(snapshot.escapeChar, snapshot.escapeReplace);
    if (code >= 48 && code <= 57) // a leading digit (0-9) must be unicode-escaped
      return snapshot.escapeReplace(name[0]) + name.substring(1).replace(snapshot.escapeChar, snapshot.escapeReplace);
    return name.replace(snapshot.escapeChar, snapshot.escapeReplace);
  },

  // Uses unicode escapes instead of simple backslash escapes to keep selector parsing simpler.
  escapeReplace: function(ch) {
    return "\\0000" + ch.charCodeAt(0).toString(16);
  },

  // Generates a detailed CSS selector for an element including, at a minimum, the tag name,
  // id (if present), and any classes on the element and all ancestors between it and the body.
  // e.g. "body>section#content>div.wrap>div#content-main.full>article.article>div#wikiArticle.page-content.boxed>table.standard-table>tbody>tr:nth-child(1)>td:nth-child(2)"
  generateSelector: function(el) {
    var matchesSelector = el.mozMatchesSelector ? "mozMatchesSelector" : "webkitMatchesSelector";
    for (var parts = []; el; el = el.parentNode) {
      var sel = el.localName.toLowerCase();
      if (sel === "body") break;

      var id = el.id;
      if (id && !snapshot.space.test(id)) sel += "#" + snapshot.escape(id);

      sel += toArray(el.classList).map(function(c) {return "." + snapshot.escape(c)}).join("");

      var children = toArray(el.parentNode.children);
      if (children.some(function(ch) {return ch !== el && ch[matchesSelector](sel)})) {
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
    doc = doc || document; //{querySelectorAll: function(s) { api.log("TRIED: " + s); return []}};
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
  },

  take: function(composeTypeName, onExit) {
    document.documentElement.classList.add("kifi-snapshot-mode");
    document.body.classList.add("kifi-snapshot-root");

    var sel = {}, cX, cY;
    var $shades = $(["t","b","l","r"].map(function(s) {
      return $("<div class='kifi-snapshot-shade kifi-snapshot-shade-" + s + "'>")[0];
    }));
    var $glass = $("<div class=kifi-snapshot-glass>");
    var $selectable = $shades.add($glass).appendTo("body").on("mousemove", function(e) {
      updateSelection(cX = e.clientX, cY = e.clientY, e.pageX - e.clientX, e.pageY - e.clientY);
    });
    render("html/comments/snapshot_bar.html", {"type": composeTypeName}, function(html) {
      api.require("scripts/lib/jquery-ui-1.9.1.custom.min.js", function() {  // for draggable
        $(html).appendTo("body")
          .draggable({cursor: "move", distance: 10, handle: ".kifi-snapshot-bar", scroll: false})
          .on("click", ".kifi-snapshot-cancel", exitSnapshotMode)
          .add($shades).css("opacity", 0).animate({opacity: 1}, 300);
        key("esc", "snapshot", exitSnapshotMode);
        key.setScope("snapshot");
      });
    });
    $(window).scroll(function() {
      if (sel) updateSelection(cX, cY);
    });
    $glass.click(function() {
      exitSnapshotMode(snapshot.generateSelector(sel.el));
    });
    function exitSnapshotMode(selector) {
      document.documentElement.classList.remove("kifi-snapshot-mode");
      $selectable.add(".kifi-snapshot-bar-wrap").animate({opacity: 0}, 400, function() {
        $(this).remove();
        document.body.classList.remove("kifi-snapshot-root");
      });
      key.setScope();
      key.deleteScope("snapshot");
      setTimeout(onExit.bind(null, selector));
    }
    function updateSelection(clientX, clientY, scrollLeft, scrollTop) {
      $selectable.hide();
      var el = document.elementFromPoint(clientX, clientY);
      $selectable.show();
      if (!el) return;
      if (scrollLeft == null) scrollLeft = document.body.scrollLeft;
      if (scrollTop == null) scrollTop = document.body.scrollTop;
      var pageX = scrollLeft + clientX;
      var pageY = scrollTop + clientY;
      if (el === sel.el) {
        // track the latest hover point over the current element
        sel.x = pageX; sel.y = pageY;
      } else {
        var r = el.getBoundingClientRect();
        var dx = Math.abs(pageX - sel.x);
        var dy = Math.abs(pageY - sel.y);
        if (!sel.el ||
            (dx == 0 || r.width < sel.r.width * 2 * dx) &&
            (dy == 0 || r.height < sel.r.height * 2 * dy) &&
            (dx == 0 && dy == 0 || r.width * r.height < sel.r.width * sel.r.height * Math.sqrt(dx * dx + dy * dy))) {
          // if (sel.el) api.log(
          //   r.width + " < " + sel.r.width + " * 2 * " + dx + " AND " +
          //   r.height + " < " + sel.r.height + " * 2 * " + dy + " AND " +
          //   r.width * r.height + " < " + sel.r.width * sel.r.height + " * " + Math.sqrt(dx * dx + dy * dy));
          var yT = scrollTop + r.top - 2;
          var yB = scrollTop + r.bottom + 2;
          var xL = scrollLeft + r.left - 3;
          var xR = scrollLeft + r.right + 3;
          $shades.eq(0).css({height: yT});
          $shades.eq(1).css({top: yB, height: document.documentElement.scrollHeight - yB});
          $shades.eq(2).css({top: yT, height: yB - yT, width: xL});
          $shades.eq(3).css({top: yT, height: yB - yT, left: xR});
          $glass.css({top: yT, height: yB - yT, left: xL, width: xR - xL});
          sel.el = el; sel.r = r; sel.x = pageX; sel.y = pageY;
        }
      }
    }
  }
};
