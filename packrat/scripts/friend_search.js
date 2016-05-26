// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/lib/mustache.js
// @require scripts/lib/q.min.js
// @require scripts/render.js
// @require scripts/formatting.js
// @require scripts/html/keeper/name_parts.js
// @require scripts/html/keeper/keep_box_lib.js
// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/friend_search_token.js
// @require scripts/repair_inputs.js

var initFriendSearch = (function () {

  var isSafari = (navigator.userAgent.indexOf('Safari') !== -1 && navigator.userAgent.indexOf('Chrome') === -1);
  if (isSafari) {
    // We need to use this due to weirdness stemming from how Safari
    // transitions the newly cloned elements being added to the document.
    $.fn.safariHeight = function (height) {
      return this.each(function () {
        setHeight(this, height);
      });
    };
  } else {
    $.fn.safariHeight = function (height) {
      return this.css('height', height);
    };
  }

  function setHeight(element, height) {
    element = (element instanceof Element ? element : element[0]);
    var classList = Array.prototype.slice.call(element.classList);

    var styleTag = document.getElementById('kifi-ti-style');
    if (!styleTag) {
      styleTag = document.createElement('style');
      styleTag.id = 'kifi-ti-style';
      document.body.appendChild(styleTag);
    }

    if (height === '') {
      if (!document.documentElement.contains(element)) {
        element.dataset.safariHeight = null;
        document.documentElement.appendChild(element);
        height = getComputedStyle(element).height.slice(0, -2);
        document.documentElement.removeChild(element);
      } else {
        height = getComputedStyle(element).height.slice(0, -2);
      }
      if (height !== '') {
        height = +height;
        setHeight(element, height);
      } else {
        // We just can't get the height, so give up
        return;
      }
    }

    height = (typeof height === 'number' ? height + 'px' : height);
    element.dataset.safariHeight = Math.random();
    var sheet = styleTag.sheet;
    var selector = classList.reduce(reduceClassList, '[data-safari-height="' + element.dataset.safariHeight + '"]');
    var rule = selector + ' { height: ' + height + '!important; }';

    sheet.insertRule(rule, sheet.rules.length);
    if (sheet.rules.length > 2) {
      sheet.deleteRule(0);
    }
  }

  function reduceClassList(acc, d) {
    return '.' + d + acc;
  }

  return function ($in, source, getParticipants, includeSelf, options, searchFor) {
    $in.tokenInput(search.bind(null, getParticipants, includeSelf, searchFor), $.extend({
      preventDuplicates: true,
      tokenValue: 'id',
      classPrefix: 'kifi-ti-',
      showResults: showResults,
      formatResult: formatResult,
      formatToken: formatToken,
      onBlur: onBlur,
      onSelect: onSelect.bind(null, source),
      onRemove: onRemove
    }, options));

    $in.parent()
    .find('.kifi-ti-dropdown')
    .addClass('kifi-scroll-inner')
    .preventAncestorScroll()
    .parent()
    .addClass('kifi-ti-dropdown-parent');

    $in.prev().find('.kifi-ti-token-for-input').repairInputs();
  };

  function makeScrollable($view) {
    $view.find('.kifi-scroll-inner').each(function () {
      var $this = $(this);
      $this.parent().antiscroll({x: false});
      if (!$this.data('wheelListener')) {
        $this.data('wheelListener', onScrollInnerWheel.bind(this));
      }
      $this.on('wheel', $this.data('wheelListener'));
    });
  }

  function unMakeScrollable($view) {
    $view.find('.kifi-scroll-inner').each(function () {
      var $this = $(this);
      $this.parent().data('antiscroll').destroy();
      $this.off('wheel', $this.data('wheelListener'));
    });
  }

  function onScrollInnerWheel(e) {
    var dY = e.originalEvent.deltaY;
    var sT = this.scrollTop;
    if (dY > 0 && sT + this.clientHeight < this.scrollHeight ||
        dY < 0 && sT > 0) {
      e.originalEvent.didScroll = true; // crbug.com/151734
    }
  }

  function search(getExcludeIds, includeSelf, searchFor, ids, query, offset, withResults) {
    searchFor = searchFor || { user: true, email: true, library: false };
    api.port.emit('search_recipients', {q: query, n: 10, offset: offset, exclude: getExcludeIds().map(getIdOrEmail).concat(ids), searchFor: searchFor}, function (recipients) {
      recipients = recipients || [];

      var libraries = recipients.filter(function (r) { return r.id && r.id[0] === 'l' && r.id.indexOf('@') === -1; });
      libraries.forEach(k.formatting.formatLibraryResult);

      withResults(recipients);
    });
  }

  function formatToken(item) {
    var itemClone = JSON.parse(JSON.stringify(item));
    itemClone.libKind = (item.kind === 'library');
    itemClone.orgKind = (item.kind === 'org');
    itemClone.userKind = !itemClone.orgKind && (item.pictureName || item.kind === 'user');
    itemClone.cdnBase = k.cdnBase;

    return k.render('html/keeper/friend_search_token', itemClone);
  }

  function formatResult(res) {
    var html;
    var pic;

    if (res.kind === 'library') {
      if (typeof res.nameParts[0] === 'string') {
        annotateNameParts(res);
      }
      res.keep = res.keep || {};
      res.keep.isSearchResult = true;
      res.extraInfo = 'Kifi library';
      html = $(k.render('html/keeper/keep_box_lib', res, {'name_parts': 'name_parts'}));
      html.addClass('kifi-ti-dropdown-item-token')
      return html.prop('outerHTML');
    } else if (res.kind === 'org') {
      pic = k.cdnBase + '/' + res.avatarPath;
      html = [
        '<li class="kifi-ti-dropdown-item-token kifi-ti-dropdown-org" style="background-image:url(', pic, ')">',
        '<div class="kifi-ti-dropdown-line-1">'];
      appendParts(html, res.nameParts);
      html.push(
        '</div>',
        '<div class="kifi-ti-dropdown-line-2">',
        'Send to everyone in this team',
        '</div></li>');
      return html.join('');
    } else if (res.pictureName || res.kind === 'user') {
      pic = k.cdnBase + '/users/' + res.id + '/pics/100/' + res.pictureName;
      html = [
        '<li class="kifi-ti-dropdown-item-token kifi-ti-dropdown-user" style="background-image:url(', pic, ')">',
        '<div class="kifi-ti-dropdown-line-1">'];
      appendParts(html, res.nameParts);
      html.push(
        '</div>',
        '<div class="kifi-ti-dropdown-line-2">',
        'Kifi user',
        '</div></li>');
      return html.join('');
    } else if (res.q) {
      html = [
        '<li class="', res.isValidEmail ? 'kifi-ti-dropdown-item-token ' : '', 'kifi-ti-dropdown-email kifi-ti-dropdown-new-email">',
        '<div class="kifi-ti-dropdown-line-1">'];
      appendParts(html, ['', res.q]);
      html.push(
        '</div>',
        '<div class="kifi-ti-dropdown-line-2">',
        res.isValidEmail ? 'An email address' : 'Keep typing the email address',
        '</div></li>');
      return html.join('');
    } else if (res.email) {
      html = [
        '<li class="kifi-ti-dropdown-item-token kifi-ti-dropdown-email kifi-ti-dropdown-contact-email">',
        '<a class="kifi-ti-dropdown-item-x" href="javascript:"></a>',
        '<div class="kifi-ti-dropdown-line-1">'];
      if (res.nameParts) {
        appendParts(html, res.nameParts);
        html.push('</div><div class="kifi-ti-dropdown-line-2">');
        appendParts(html, res.emailParts);
      } else {
        appendParts(html, res.emailParts);
        html.push('</div><div class="kifi-ti-dropdown-line-2">An email contact');
      }
      html.push('</div></li>');
      return html.join('');
    } else if (res === 'tip') {
      return [
        '<li class="kifi-ti-dropdown-tip">',
        '<div class="kifi-ti-dropdown-line-1">Import Gmail contacts</div>',
        '</li>'].join('');
    }
  }

  function annotateNameParts(lib) {
    lib.nameParts = lib.nameParts.map(function (part, i) {
      return {
        highlight: i % 2,
        part: part
      };
    });
    return lib;
  }

  function showResults($dropdown, els, done) {
    var n = Math.max(3, Math.min(8, Math.floor((window.innerHeight - 365) / 55)));  // quick rule of thumb
    var $dropdownElement = $dropdown[0];
    if ($dropdownElement.childElementCount === 0) {  // bringing entire list into view
      if (els.length) {
        $dropdown.safariHeight(0).append(els);
        $dropdown.off('transitionend').on('transitionend', function (e) {
          if (e.target === this && e.originalEvent.propertyName === 'height') {
            $dropdown.off('transitionend').safariHeight('');
            makeScrollable($dropdown.parent());
            done();
          }
        }).safariHeight(measureCloneHeight($dropdownElement, 'clientHeight'));
      } else {
        done();
      }
    } else if (els.length === 0) {  // hiding entire list
      var height = $dropdownElement.clientHeight;
      if (height > 0) {
        $dropdown.safariHeight(height).layout();
        $dropdown.off('transitionend').on('transitionend', function (e) {
          if (e.target === this && e.originalEvent.propertyName === 'height') {
            $dropdown.off('transitionend').empty().safariHeight(0);
            done();
          }
        }).safariHeight(0);
      } else {
        $dropdown.empty();
        done();
      }
      unMakeScrollable($dropdown.parent());
    } else {  // list is changing
      // fade in overlaid as height adjusts and old fades out
      var heightInitial = $dropdownElement.clientHeight;
      var scrollTop = $dropdownElement.scrollTop;
      var width = $dropdownElement.clientWidth;
      $dropdown.safariHeight(heightInitial);
      var $clone = $($dropdownElement.cloneNode(false))
        .addClass('kifi-ti-dropdown-clone kifi-instant')
        .css('width', width)
        .append(els)
        .css({visibility: 'hidden', opacity: 0})
        .safariHeight('')
        .preventAncestorScroll()
        .insertBefore($dropdown);
      var heightFinal = $clone[0].clientHeight;
      $dropdown.layout();
      $clone[0].scrollTop = scrollTop;
      $clone
        .css({visibility: 'visible'})
        .safariHeight(heightInitial)
        .layout()
        .on('transitionend', function (e) {
          if (e.target === this && e.originalEvent.propertyName === 'opacity') {
            $dropdown
              .empty()
              .append($clone.children())
              .css({opacity: '', transition: 'none'})
              .safariHeight('')
              .layout()
              .css('transition', '');
            $clone.remove();
            makeScrollable($dropdown.parent());
            done();
          }
        })
        .removeClass('kifi-instant')
        .css({opacity: 1})
        .safariHeight(heightFinal);
      $dropdown
        .css({opacity: 0})
        .safariHeight(heightFinal);
    }

  }

  function measureCloneHeight(el, heightProp) {
    var clone = el.cloneNode(true);
    $(clone).css({position: 'absolute', zIndex: -1, visibility: 'hidden'}).safariHeight('auto').insertBefore(el);
    var val = clone[heightProp];
    clone.remove();
    return val;
  }

  function appendParts(html, parts) {
    parts = parts || [];
    for (var i = 0; i < parts.length; i++) {
      if (i % 2) {
        html.push('<b>', Mustache.escape(parts[i]), '</b>');
      } else {
        html.push(Mustache.escape(parts[i]));
      }
    }
  }

  function getIdOrEmail(o) {
    return o.id || o.email;
  }

  function onBlur(item) {
    return !!item.isValidEmail;
  }

  function onSelect(source, res, el) {
    if (res.isValidEmail) {
      res.id = res.email = res.q;
    } else if (!res.pictureName && !res.email && res.kind !== 'library') {
      if (res === 'tip') {
        api.port.emit('import_contacts', {type: source, subsource: 'composeTypeahead'});
      }
      return false;
    }
  }

  function onRemove(item, replaceWith) {
    $('.kifi-ti-dropdown-item-waiting')
      .addClass('kifi-ti-dropdown-email')
      .css('background-image', 'url(' + api.url('images/wait.gif') + ')');
    api.port.emit('delete_contact', item.email, function (success) {
      replaceWith(success ? null : item);
    });
  }
}());
