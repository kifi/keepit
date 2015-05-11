// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/repair_inputs.js

var initFriendSearch = (function () {

  return function ($in, source, participants, includeSelf, options) {
    $in.tokenInput(search.bind(null, participants.map(getId), includeSelf), $.extend({
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
    $in.prev().find('.kifi-ti-token-for-input').repairInputs();
  };

  function search(participantIds, includeSelf, ids, query, withResults) {
    var n = Math.max(3, Math.min(8, Math.floor((window.innerHeight - 365) / 55)));  // quick rule of thumb
    api.port.emit('search_contacts', {q: query, n: n, includeSelf: includeSelf(ids.length), exclude: participantIds.concat(ids)}, function (contacts) {
      if (contacts.length < 3) {
        contacts.push('tip');
      }
      withResults(contacts);
    });
  }

  function formatToken(item) {
    var html = ['<li>'];
    if (item.email) {
      html.push('<span class="kifi-ti-email-token-icon"></span>', Mustache.escape(item.email));
    } else {
      html.push(Mustache.escape(item.name));
    }
    html.push('</li>');
    return html.join('');
  }

  function formatResult(res) {
    if (res.pictureName) {
      var html = [
        '<li class="kifi-ti-dropdown-item-token" style="background-image:url(//', k.cdnBase, '/users/', res.id, '/pics/100/', res.pictureName, ')">',
        '<div class="kifi-ti-dropdown-line-1">'];
      appendParts(html, res.nameParts);
      html.push(
        '</div>',
        '<div class="kifi-ti-dropdown-line-2">',
        res.id === 'aa345838-70fe-45f2-914c-f27c865bdb91' ? 'Kifi team' : 'Kifi user',
        '</div></li>');
      return html.join('');
    } else if (res.q) {
      var html = [
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
      var html = [
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

  function showResults($dropdown, els, done) {
    if ($dropdown[0].childElementCount === 0) {  // bringing entire list into view
      if (els.length) {
        $dropdown.css('height', 0).append(els);
        $dropdown.off('transitionend').on('transitionend', function (e) {
          if (e.target === this && e.originalEvent.propertyName === 'height') {
            $dropdown.off('transitionend').css('height', '');
            done();
          }
        }).css('height', measureCloneHeight($dropdown[0], 'clientHeight'));
      } else {
        done();
      }
    } else if (els.length === 0) {  // hiding entire list
      var height = $dropdown[0].clientHeight;
      if (height > 0) {
        $dropdown.css('height', height).layout();
        $dropdown.off('transitionend').on('transitionend', function (e) {
          if (e.target === this && e.originalEvent.propertyName === 'height') {
            $dropdown.off('transitionend').empty().css('height', '');
            done();
          }
        }).css('height', 0);
      } else {
        $dropdown.empty();
        done();
      }
    } else {  // list is changing
      // fade in overlaid as height adjusts and old fades out
      var heightInitial = $dropdown[0].clientHeight;
      var width = $dropdown[0].clientWidth;
      $dropdown.css('height', heightInitial);
      var $clone = $($dropdown[0].cloneNode(false)).addClass('kifi-ti-dropdown-clone kifi-instant').css('width', width)
          .append(els)
        .css({visibility: 'hidden', opacity: 0, height: ''})
        .insertBefore($dropdown);
      var heightFinal = $clone[0].clientHeight;
      $dropdown.layout();
      $clone
        .css({height: heightInitial, visibility: 'visible'})
        .layout()
        .on('transitionend', function (e) {
          if (e.target === this && e.originalEvent.propertyName === 'opacity') {
            $dropdown.empty().append($clone.children()).css({opacity: '', height: '', transition: 'none'}).layout().css('transition', '');
            $clone.remove();
            done();
          }
        })
        .removeClass('kifi-instant')
        .css({height: heightFinal, opacity: 1});
      $dropdown
        .css({height: heightFinal, opacity: 0});
    }
  }

  function measureCloneHeight(el, heightProp) {
    var clone = el.cloneNode(true);
    $(clone).css({position: 'absolute', zIndex: -1, visibility: 'hidden', height: 'auto'}).insertBefore(el);
    var val = clone[heightProp];
    clone.remove();
    return val;
  }

  function appendParts(html, parts) {
    for (var i = 0; i < parts.length; i++) {
      if (i % 2) {
        html.push('<b>', Mustache.escape(parts[i]), '</b>');
      } else {
        html.push(Mustache.escape(parts[i]));
      }
    }
  }

  function getId(o) {
    return o.id;
  }

  function onBlur(item) {
    return !!item.isValidEmail;
  }

  function onSelect(source, res, el) {
    if (res.isValidEmail) {
      res.id = res.email = res.q;
    } else if (!res.pictureName && !res.email) {
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
