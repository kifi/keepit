// @require scripts/lib/mustache.js
// @require scripts/selectionchange.js

k.keepNote = k.keepNote || (function () {
  'use strict';

  var TEXT = document.TEXT_NODE;
  var ELEM = document.ELEMENT_NODE;
  var OK_ELEMS = {DIV: 1, BR: 1};

  // control codes, whitespace (including nbsp), punctuation (excluding #)
  var nonTagCharCodes = ('0000-"$-/:-@\\[-^`{-00A1' +
    '00A700AB00B600B700BB00BF037E0387055A-055F0589058A05BE05C005C305C605F305F40609060A060C060D061B061E061F066A-066D06D40700-070D07F7-07F90830-083E085E' +
    '0964096509700AF00DF40E4F0E5A0E5B0F04-0F120F140F3A-0F3D0F850FD0-0FD40FD90FDA104A-104F10FB1360-13681400166D166E169B169C16EB-16ED1735173617D4-17D617D8-17DA' +
    '1800-180A194419451A1E1A1F1AA0-1AA61AA8-1AAD1B5A-1B601BFC-1BFF1C3B-1C3F1C7E1C7F1CC0-1CC71CD32010-20272030-20432045-20512053-205E207D207E208D208E2329232A' +
    '2768-277527C527C627E6-27EF2983-299829D8-29DB29FC29FD2CF9-2CFC2CFE2CFF2D702E00-2E2E2E30-2E3B3001-30033008-30113014-301F3030303D30A030FBA4FEA4FFA60D-A60F' +
    'A673A67EA6F2-A6F7A874-A877A8CEA8CFA8F8-A8FAA92EA92FA95FA9C1-A9CDA9DEA9DFAA5C-AA5FAADEAADFAAF0AAF1ABEBFD3EFD3FFE10-FE19FE30-FE52FE54-FE61FE63FE68FE6AFE6B' +
    'FF01-FF03FF05-FF0AFF0C-FF0FFF1AFF1BFF1FFF20FF3B-FF3DFF3FFF5BFF5DFF5F-FF65FFE5').replace(/([\dA-F]{4})/g, '\\u$1');
  var hashTagCharsRe = new RegExp('^[^#' + nonTagCharCodes + ']+');
  var hashTagPattern = '#[^#' + nonTagCharCodes + ']*[^_\\d#' + nonTagCharCodes + '][^#' + nonTagCharCodes + ']*';
  var hashTagStartRe = new RegExp('^' + hashTagPattern + '(?=[' + nonTagCharCodes + ']|$)');
  var hashTagFindRe = new RegExp('(?:^|[#' + nonTagCharCodes + '])(' + hashTagPattern + ')(?=[' + nonTagCharCodes + ']|$)');
  var hashTagMarkdownRe = /\[#((?:\\.|[^\]])*)\]/g;
  var hashTagInHtmlRe = /<span(?: [\w-]+="[^"]*")*? class="[^"]*?kifi-keep-box-tag[^"]*"(?: [\w-]+="[^"]*")*>(.*?)<\/span>/gi;
  var allBrTagsRe = /<br[^>]*>/gi;  // for Firefox
  var multipleBlankLinesRe = /\n(?:\s*\n)+/g;
  var escapedLeftBracketHashOrAtRe = /\[\\([#@])/g;
  var backslashEscapeRe = /[(\]\\)]/g;
  var backslashUnescapeRe = /\\(.)/g;

  function noteTextToHtml(text) {
    var parts = text.split(hashTagMarkdownRe);
    for (var i = 1; i < parts.length; i += 2) {
      parts[i] = '<span class="kifi-keep-box-tag">#' + Mustache.escape(parts[i].replace(backslashUnescapeRe, '$1')) + '</span>';
    }
    for (i = 0; i < parts.length; i += 2) {
      parts[i] = Mustache.escape(parts[i].replace(escapedLeftBracketHashOrAtRe, '[$1'));
    }
    return parts.join('');
  }

  function noteHtmlToText(html) {
    var html2 = html.replace(allBrTagsRe, '\n').replace(multipleBlankLinesRe, '\n\n').replace(hashTagInHtmlRe, function($0, $1) {
        return '[' + $1.replace(backslashEscapeRe, '\\$1') + ']';
      });
    return $('<div>').html(html2).text().trim();
  }

  function isHashTag(node) {
    return node.nodeType === ELEM && node.classList.contains('kifi-keep-box-tag');
  }

  function inHashTag(node) {
    return isHashTag(node.parentNode);
  }

  function isStyled(node) {
    return $(node.parentNode).is('b,big,em,font,i,s,small,strong,sub,sup,u');
  }

  function isValidHashTagPredecessor(node) {
    return !node || (node.nodeType === TEXT ? !hashTagCharsRe.test(node.data.slice(-1)) : !isHashTag(node));
  }

  function isValidHashTagSuccessor(node) {
    return !node || (node.nodeType === TEXT ? !hashTagCharsRe.test(node.data[0]) && node.data[0] !== '#' : !isHashTag(node));
  }

  function areAllHashTagChars(text) {
    return !text || (hashTagCharsRe.exec(text) || {0: ''})[0].length === text.length;
  }

  var findPreviousTextNode = findNearestTextNode.bind(null, 'previousSibling', 'lastChild');
  var findNextTextNode = findNearestTextNode.bind(null, 'nextSibling', 'firstChild');

  function findNearestTextNode(siblingProp, childProp, node) {
    var near = node[siblingProp];
    while (!near) {
      node = node.parentNode;
      if (node.classList.contains('kifi-keep-box-keep-note')) {
        return null;
      }
      near = node[siblingProp];
    }
    while (near[childProp]) {
      near = near[childProp];
    }
    if (near.nodeType === TEXT) {
      return near;
    }
    return findNearestTextNode(siblingProp, childProp, near);
  }

  function normalizeCaretPositionToTextNode(node, idx, create) {
    if (node.nodeType === TEXT) {
      return {node: node, idx: idx};
    }
    // prefer end of preceding text
    var prev = idx > 0 && findPreviousTextNode(node.childNodes.item(idx));
    if (prev) {
      return {node: prev, idx: prev.length};
    }
    // resort to beginning of subsequent text
    var next = node;
    while (next.firstChild) {
      next = next.firstChild;
    }
    if (next.nodeType !== TEXT) {
      next = findNextTextNode(next);
    }
    if (next) {
      return {node: next, idx: 0};
    }
    if (create) {
      var textNode = createTextNode('');
      node.insertBefore(textNode, node.childNodes.item(idx + 1));
      return {node: textNode, idx: 0};
    }
  }

  function setSelection(node, offset) {
    if (offset <= 0) {
      var prev = findPreviousTextNode(node);
      if (prev) {
        return setSelection(prev, prev.length + offset);
      } else {
        offset = 0;
      }
    } else if (offset > node.length) {
      var next = findNextTextNode(node);
      if (next) {
        return setSelection(next, offset - node.length);
      } else {
        offset = node.length;
      }
    }
    var r = document.createRange();
    r.setStart(node, offset);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(r);
    return {node: node, idx: offset};
  }

  function createTextNode(text) {
    return document.createTextNode(text);
  }

  function insertTextBeforeEl(el, text) {
    var prev = el.previousSibling;
    if (prev && prev.nodeType === TEXT) {
      prev.data += text;
    } else {
      el.parentNode.insertBefore(createTextNode(text), el);
    }
  }

  function insertTextAfterEl(el, text) {
    var next = el.nextSibling;
    if (next && next.nodeType === TEXT) {
      next.data = text + next.data;
    } else {
      el.parentNode.insertBefore(createTextNode(text), next);
    }
  }

  function replaceParent(textNode, refTextNode) {
    var parent = textNode.parentNode;
    parent.parentNode.replaceChild(textNode, parent);
    return mergeAdjacentTextNodes(refTextNode || textNode);
  }

  function mergeAdjacentTextNodes(textNode) {
    var lenBefore = 0;
    var toRemove = [];
    var node = textNode.previousSibling;
    while (node && node.nodeType === TEXT) {
      lenBefore += node.length;
      toRemove.push(node);
      node = node.previousSibling;
    }
    node = textNode.nextSibling;
    while (node && node.nodeType === TEXT) {
      toRemove.push(node);
      node = node.nextSibling;
    }
    textNode.data = textNode.wholeText;
    toRemove.forEach(function (node) {
      node.remove();
    });
    return lenBefore;
  }

  // Moves valid leading hashtag chars from nextTextNode into hashtag. Returns the
  // hashtag's new next sibling. Caller must verify its validity as a hashtag successor.
  function growHashTag(hashTextNode, hashText, nextTextNode) {
    var nextText = nextTextNode.data;
    var match = hashTagCharsRe.exec(nextText);
    if (match) {
      var s = match[0];
      hashTextNode.data += s;
      if (s === nextText) {
        nextTextNode.remove();
      } else {
        nextTextNode.data = nextText.substr(s.length);
      }
      return s.length;
    }
    return 0;
  }

  // After this function returns, textNode will be in its original position and
  // begin with the same character(s), wrapped in a markup element if idx === 0.
  // Returns the new text node containing the text after the hashtag, if any.
  function createNewHashTag(textNode, text, idx, tag) {
    var parent = textNode.parentNode;
    var next = textNode.nextSibling;
    var tagEl = $('<span class="kifi-keep-box-tag"/>')[0];
    if (idx) {
      textNode.data = text.substr(0, idx);
      tagEl.textContent = tag;
    } else {
      textNode.data = tag;
      tagEl.appendChild(textNode);
    }
    parent.insertBefore(tagEl, next);
    var textAfter = text.substr(idx + tag.length);
    if (textAfter) {
      var nodeAfter = createTextNode(textAfter);
      parent.insertBefore(nodeAfter, next);
      return nodeAfter;
    }
  }

  // After this function returns, textNode will be in its original position and begin
  // with the same character(s), possibly wrapped in or followed by a markup element.
  // Returns whether any markup was added.
  function markUp(textNode) {
    var markedUp = false;
    var text = textNode.data;
    var match = hashTagFindRe.exec(text);
    while (match) {
      var tag = match[1];
      var i = match.index + (match[0] !== tag ? 1 : 0);  // skipping lookbehind char
      if (i === 0 && !isValidHashTagPredecessor(textNode.previousSibling)) {
        match = hashTagFindRe.exec('_' + text.substr(1));
        continue;
      }
      if (i + tag.length === text.length && !isValidHashTagSuccessor(textNode.nextSibling)) {
        break;
      }
      textNode = createNewHashTag(textNode, text, i, tag);
      markedUp = true;
      text = textNode && textNode.data;
      match = text && hashTagFindRe.exec(text);
    }
    return markedUp;
  }

  // node: a text node
  // idx: index of the input caret in the text node's text
  function handleHashTagTextNodeInput(node, idx, lastKeyedCharCode) {
    var text = node.data;
    var fixed = false;
    var match = hashTagFindRe.exec(text);
    if (match) {
      var tag = match[1];
      var i = match.index + (match[0] !== tag ? 1 : 0);  // skipping lookbehind char
      if (i > 0) {
        insertTextBeforeEl(node.parentNode, text.substr(0, i));
        node.data = text = text.substr(i);
        idx -= i;
        fixed = true;
      }
      var n = tag.length;
      if (n < text.length && idx > n && !hashTagCharsRe.test(text[idx - 1]) &&  // char to left of cursor is invalid
          (idx === text.length ||  // cursor is at the end of the text (e.g. deletion of last word of hashtag) or...
            lastKeyedCharCode && areAllHashTagChars(text.substr(idx)))) {  // invalid char was just typed in last word of hashtag
        insertTextAfterEl(node.parentNode, text.substr(idx - 1));
        node.data = text = text.substr(0, idx - 1);
        fixed = true;
      }
      if (idx <= 0) {  // prev node may have been modified
        var prev = node.parentNode.previousSibling;
        if (!isValidHashTagPredecessor(prev)) {
          if (isHashTag(prev)) {
            replaceParent(prev.firstChild);
          }
          idx += replaceParent(node);
          fixed = true;
        }
      } else if (idx >= text.length) {  // next node may have been modified
        var next = node.parentNode.nextSibling;
        if (next && next.nodeType === TEXT && growHashTag(node, text, next)) {
          next = node.parentNode.nextSibling;
          fixed = true;
        }
        if (!isValidHashTagSuccessor(next)) {
          if (isHashTag(next)) {
            replaceParent(next.firstChild);
          }
          idx += replaceParent(node);
          fixed = true;
        }
      }
    } else {
      idx += replaceParent(node);
      fixed = true;
    }
    if (fixed) {
      if (!inHashTag(node)) {
        markUp(node);
      }
      return setSelection(node, idx);
    }
    return {node: node, idx: idx};
  }

  function rectifyLeftOfSimpleTextNode(node) {
    var prev = node.previousSibling;
    if (prev) {
      if (prev.nodeType === TEXT) {
        return mergeAdjacentTextNodes(node);
      }
      if (isHashTag(prev) && !isValidHashTagSuccessor(node)) {
        return replaceParent(prev.firstChild, node);
      }
    }
  }

  function rectifyRightOfSimpleTextNode(node) {
    var next = node.nextSibling;
    if (next) {
      if (next.nodeType === TEXT) {
        return mergeAdjacentTextNodes(node);
      }
      if (isHashTag(next) && (!isValidHashTagPredecessor(node) || next.textContent[0] !== '#')) {
        return replaceParent(next.firstChild, node);
      }
    }
  }

  function handleSimpleTextNodeInput(node, idx) {
    var fixed = false;
    if (idx <= 1) {
      var deltaIdx = rectifyLeftOfSimpleTextNode(node);
      if (deltaIdx != null) {
        idx += deltaIdx;
        fixed = true;
      }
    }
    if (idx >= node.data.length - 1) {
      fixed = rectifyRightOfSimpleTextNode(node) != null || fixed;
    }
    var markedUp = markUp(node);
    if (markedUp || fixed) {
      return setSelection(node, idx);
    }
  }

  function updateSuggestions(noteEl, data, pos) {
    if (pos && inHashTag(pos.node)) {
      suggestTags(noteEl, pos.node);
    } else if (data.$suggestions) {
      stopSuggesting(data);
      data.seq++;
    }
  }

  function suggestTags(noteEl, node) {
    var data = $.data(noteEl);
    var seq = ++data.seq;
    api.port.emit('suggest_tags', {
      q: node.data.substr(1),
      n: 4,
      libraryId: data.libraryId,
      keepId: data.keepId,
      tags: []
    }, function (results) {
      if (seq === data.seq) {
        if (results.length) {
          if (!data.$suggestions) {
            data.$suggestions = $('<div class="kifi-keep-box-tag-suggestions"/>')
              .data({
                position: {my: 'left bottom', at: 'left-7 top-2', of: node.parentNode, collision: 'fit none', within: data.$bounds},
                tagTextNode: node
              })
              .on('mouseover', '.kifi-keep-box-tag-suggestion', data, onMouseOverSuggestion)
              .on('mousemove', '.kifi-keep-box-tag-suggestion', data, onMouseMoveSuggestion)
              .on('mousedown', '.kifi-keep-box-tag-suggestion', data, onMouseDownSuggestion)
              .insertBefore(noteEl);
            selectionchange.start();
            $(document).on('selectionchange', data, onDocSelectionChange);
          }
          data.$suggestions
            .html(results.map(formatTagSuggestion).join(''))
            .position(data.$suggestions.data('position'));
          selectSuggestion(data, data.$suggestions[0].firstChild);
        } else if (data.$suggestions) {
          stopSuggesting(data);
        }
      }
    });
    if (data.$suggestions) {
      data.$suggestions.position(data.$suggestions.data('position'));
    }
  }

  function formatTagSuggestion(item) {
    var html = ['<div class="kifi-keep-box-tag-suggestion">'];
    if (item.matches || item.freeTag) {
      pushWithBoldedMatches(html, item.tag, item.matches || [[0, item.tag.length]]);
    } else {
      html.push(Mustache.escape(item.tag));
    }
    html.push('</div>');
    return html.join('');
  }

  function pushWithBoldedMatches(html, text, matches) {
    var i = 0;
    for (var j = 0; j < matches.length; j++) {
      var match = matches[j];
      var pos = match[0];
      var len = match[1];
      if (pos >= i) {
        html.push(Mustache.escape(text.substring(i, pos)), '<b>', Mustache.escape(text.substr(pos, len)), '</b>');
        i = pos + len;
      }
    }
    html.push(Mustache.escape(text.substr(i)));
  }

  function selectSuggestion(data, el) {
    (data.suggestion || el).classList.remove('kifi-selected');
    (data.suggestion = el).classList.add('kifi-selected');
  }

  function acceptSuggestion(data, suggestion) {
    var node = data.$suggestions.data('tagTextNode');
    var text = '#' + (suggestion || data.suggestion).textContent;
    node.data = text;
    stopSuggesting(data);
    setSelection(node, text.length);
  }

  function stopSuggesting(data) {
    data.$suggestions.remove();
    data.$suggestions = data.suggestion = null;
    $(document).off('selectionchange', onDocSelectionChange);
    selectionchange.stop();
  }

  function onMouseOverSuggestion(e) {
    if (e.data.mousing) {  // FF immediately triggers mouseover on element inserted under mouse cursor
      selectSuggestion(e.data, this);
    }
  }

  function onMouseMoveSuggestion(e) {
    if (!e.data.mousing) {
      e.data.mousing = true;
      selectSuggestion(e.data, this);
    }
  }

  function onMouseDownSuggestion(e) {
    if (e.which === 1) {
      acceptSuggestion(e.data, this);
      return false;
    }
  }

  function onDocSelectionChange(e) {
    var node = e.data.$suggestions.data('tagTextNode');
    var sel = window.getSelection();
    var r = sel.rangeCount > 0 ? sel.getRangeAt(0) : null;
    if (!r || r.startContainer !== node || r.endContainer !== node) {
      stopSuggesting(e.data);
    }
  }

  function onKeyDown(e) {
    switch (e.which) {
      case 9: // tab
        if (e.data.suggestion) {
          acceptSuggestion(e.data);
          return false;
        }
      case 13: // enter
        if (e.data.suggestion) {
          acceptSuggestion(e.data);
          return false;
        }
        if (e.shiftKey + e.altKey + e.ctrlKey === 1) { // enter
          e.stopPropagation(); // avoids closing keep
        }
        break;
      case 38: // up
      case 40: // down
        if (e.data.$suggestions) {
          var up = e.which === 38;
          var el = e.data.suggestion &&
            e.data.suggestion[up ? 'previousSibling' : 'nextSibling'] ||
            e.data.$suggestions[0][up ? 'lastChild' : 'firstChild'];
          if (el) {
            selectSuggestion(e.data, el);
          }
          return false;
        }
        break;
    }
    e.data.mousing = false;
  }

  function onEsc(data) {
    if (data.$suggestions) {
      stopSuggesting(data);
      return false;
    }
  }

  function onPaste(e) {
    e.preventDefault();
    var cd = e.originalEvent.clipboardData;
    if (cd && e.originalEvent.isTrusted !== false) {
      var pasteText = cd.getData('text/plain').replace(multipleBlankLinesRe, '\n\n');
      if (pasteText) {
        var sel = window.getSelection();
        var r = sel.getRangeAt(0);
        r.deleteContents();
        var pos = normalizeCaretPositionToTextNode(r.endContainer, r.endOffset, true);
        var node = pos.node;
        var text = node.data;
        var idx = pos.idx;
        var atL = idx === 0;
        var atR = idx === text.length;
        node.data = text.substr(0, idx) + pasteText + text.substr(idx);
        if (inHashTag(node)) {
          if (idx === 0 || (hashTagCharsRe.exec(pasteText) || {0: ''})[0].length < pasteText.length) {
            idx += replaceParent(node);
            markUp(node);
          }
        } else {
          if (atL) {
            idx += rectifyLeftOfSimpleTextNode(node) || 0;
          }
          if (atR) {
            rectifyRightOfSimpleTextNode(node);
          }
          markUp(node);
        }
        var pos = setSelection(node, idx + pasteText.length);
        updateSuggestions(this, e.data, pos);
      }
    }
  }

  function onInput(e) {
    var sel = window.getSelection();
    var r = sel.getRangeAt(0);
    var node = r.endContainer;
    if (!r) {
      log('[input] no selection range');
    } else if (!r.collapsed) {
      if (r.startOffset === 0 && isStyled(node)) {
        // Depending on the selection, a large number of style tags may have been inserted.
        document.execCommand('undo');
      }
    } else if (this.contains(node)) {
      var pos = normalizeCaretPositionToTextNode(node, r.endOffset);  // for Firefox
      if (pos) {
        node = pos.node;
        var idx = pos.idx;
        if (isStyled(node)) {
          idx += replaceParent(node);
          setSelection(node, idx);
        }
        var pos = inHashTag(node) ?
          handleHashTagTextNodeInput(node, idx, e.data.lastKeyedCharCode) :
          handleSimpleTextNodeInput(node, idx);
        updateSuggestions(this, e.data, pos);
      } else {
        log('[input]', node.tagName || node.nodeType, idx);
      }
    }
    e.data.lastKeyedCharCode = null;
  }

  return {
    init: function ($note, $bounds, libraryId, keepId, noteText) {
      $note.html(noteTextToHtml(noteText))
      var data = $note.data({
        libraryId: libraryId,
        keepId: keepId,
        $bounds: $bounds,
        seq: 0
      }).data();

      // Realizations that inform our strategy for efficient (lag-free) hashtag identification and highlighting:
      // - Paste events can be intercepted and the pasted text pre-processed.
      // - Range styling (e.g. bold via Cmd-B) triggers an input event with a non-collapsed selection that
      //   can be undone via document.execCommand. If our undo gets redone, that will likewise be undone.
      // - All other input events result in a collapsed selection amenable to local examination/rectification.
      //   This is even true of arbitrary range deletion, of word/line deletion via keyboard shortcut, and of
      //   mode-styled character insertion (e.g. Cmd-B and then a character).

      $note
      .on('keydown', data, onKeyDown)
      .on('keypress', data, function (e) {
        e.data.lastKeyedCharCode = e.charCode;  // not using e.which b/c it is 8 for BKSP on Mac/Firefox
      })
      .on('dragenter dragover', function (e) {
        e.originalEvent.dataTransfer.dropEffect = 'none';
        e.originalEvent.dataTransfer.effectAllowed = 'none';
        e.preventDefault();
      })
      .on('drop', function (e) {
        e.preventDefault();  // TODO: handle like paste?
      })
      .on('paste', data, onPaste)
      .on('input', data, onInput)
      .on('blur', function () {  // removing any extranneous whitespace and/or empty elements to show placeholder again
        if (!this.textContent.trim()) {
          this.textContent = '';
        }
      });

      $(document).data('esc').add(data.onEsc = onEsc.bind($note[0], data));
    },
    done: function ($note) {
      $(document).data('esc').remove($note.data('onEsc'));
    },
    moveCursorToEnd: function ($note) {
      var node = $note[0];
      while (node.lastChild) {
        node = node.lastChild;
      }
      if (node.nodeType === TEXT) {
        setSelection(node, node.length);
      }
    },
    toText: function (noteEl) {
      return noteHtmlToText(noteEl.innerHTML);
    }
  };
}());
