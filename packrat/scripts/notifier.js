// @require styles/insulate.css
// @require styles/notifier.css
// @require styles/keeper/participant_colors.css
// @require scripts/api.js
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/formatting.js
// @require scripts/title_from_url.js
// @require scripts/render.js
// @require scripts/html/notify_box.js
// @require scripts/html/keeper/kifi_mustache_tags.js

var notifier = function () {
  'use strict';

  api.onEnd.push(function () {
    $('#kifi-notify-notice-wrapper').remove();
  });

  return {
    hide: function (threadId) {
      removeItem($('.kifi-notify-item-wrapper[data-thread-id="' + threadId + '"]'));
    },
    show: function (o) {
      switch (o.category) {
      case 'message':
        this.hide(o.thread);
        o.author = o.author || o.participants[0];
        formatParticipant(o.author);
        var email = o.author.kind === 'email';
        add(o.id, o.category, {
          title: email ? o.author.id : (o.author.firstName + ' ' + o.author.lastName).trim(),
          subtitle: 'Sent you a new Kifi Message',
          contentHtml: o.text,
          link: o.title || formatTitleFromUrl(o.url),
          imageHtml: email ?
            '<div class="kifi-notify-email-icon kifi-participant-background-' + o.author.color + '">' + o.author.initial + '</div>' :
             imgTag(k.cdnBase + '/users/' + o.author.id + '/pics/100/' + o.author.pictureName),
          sticky: false,
          showForMs: o.showForMs || 12000,
          onClick: $.proxy(onClickWithLocator, null, o.thread, o.id, o.url, o.locator, false),
          threadId: o.thread
        });
        break;
      case 'global':
      case 'triggered':
        this.hide(o.thread);
        var clickAction;
        if (o.locator) {
          clickAction = $.proxy(onClickWithLocator, null, o.thread, o.id, o.url, o.locator, true);
        } else {
          clickAction = $.proxy(onClickNoLocator, null, o.thread, o.id, o.url);
        }
        add(o.id, o.category, {
          title: o.title,
          subtitle: o.subtitle,
          contentHtml: o.bodyHtml,
          link: o.linkText,
          imageHtml: imgTag(o.image),
          sticky: o.isSticky,
          showForMs: o.showForMs || 12000,
          onClick: clickAction,
          threadId: o.thread
        });
        break;
      }
    }
  };

  function add(id, category, params) {
    var $wrap = $('#kifi-notify-notice-wrapper');
    if (!$wrap.length) {
      $wrap = $('<kifi id="kifi-notify-notice-wrapper" class="kifi-root">').appendTo($('body')[0] || 'html');
    }
    var $item = $(k.render('html/notify_box', {
      bodyHtmlTree: formatMessage.snippet()(params.contentHtml),
      title: params.title,
      subtitle: params.subtitle,
      image: k.formatting.jsonDom(params.imageHtml),
      popupClass: 'kifi-notify-' + category,
      pageTitle: params.link,
      threadId: params.threadId
    }, {
      'kifi_mustache_tags': 'keeper/kifi_mustache_tags'
    }))
    .appendTo($wrap)
    .fadeIn(params.fadeInMs || 500)
    .click($.proxy(onClick, null, category, params.onClick));

    if (!params.sticky) {
      $item.mouseenter(function() {
        clearTimeout($item.data('fadeTimer'));
        $item.stop().css({opacity: '', height: ''});
      }).mouseleave(function() {
        var timeout = setTimeout(function () {
          fadeItem($item);
        }, params.showForMs);

        $item.data('fadeTimer', timeout);
      }).triggerHandler('mouseleave');
    }

    api.port.emit('track_notification', {id: id, properties: {
      category: category,
      sticky: params.sticky || undefined
    }});
  }

  function onClick(category, visit, e) {
    if (e.which !== 1) return;
    var $item = $(this);
    api.port.emit('remove_notification', $item.data('threadId'));
    $item.off('mouseenter mouseleave');
    clearTimeout($item.data('fadeTimer'));
    fadeItem($item);
    var xClicked = e.target.classList.contains('kifi-notify-close');
    if (!xClicked) {
      visit.call(this, e);
    }
    api.port.emit('track_notification_click', {
      subsource: 'popup',
      category: category,
      action: xClicked ? 'closed' : 'clicked'
    });
    return false;
  }

  function onClickWithLocator(threadId, messageId, url, locator, markAsReadImmediately, e) {
    if (markAsReadImmediately) { // If the locator likely won't trigger a mark-as-read call
      api.port.emit('set_message_read', {threadId: threadId, messageId: messageId, from: 'notifier'});
    }
    var inThisTab = e.metaKey || e.altKey || e.ctrlKey;
    api.port.emit('open_deep_link', {nUri: url, locator: locator, inThisTab: inThisTab});
    if (inThisTab && url !== document.URL) {
      window.location = url;
    }
  }

  function onClickNoLocator(threadId, messageId, url, e) {
    api.port.emit('set_message_read', {threadId: threadId, messageId: messageId, from: 'notifier'});
    var inThisTab = e.metaKey || e.altKey || e.ctrlKey;
    if (url && url !== document.URL) {
      if (inThisTab) {
        window.location = url;
      } else {
        window.open(url, '_blank').focus();
      }
    }
  }

  function fadeItem($item) {
    $item.animate({opacity: 0}, 300).animate({height: 0}, 300, removeItem.bind(null, $item));
  }

  function removeItem($item) {
    $item.remove();
    $('#kifi-notify-notice-wrapper:empty').remove();
  }

  function imgTag(url) {
    return url && '<img src="' + Mustache.escape(url) + '" class="kifi-notify-image"/>';
  }
}();
