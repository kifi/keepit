// @require styles/insulate.css
// @require styles/notifier.css
// @require styles/keeper/participant_colors.css
// @require scripts/api.js
// @require scripts/lib/jquery.js
// @require scripts/formatting.js
// @require scripts/title_from_url.js
// @require scripts/render.js
// @require scripts/html/notify_box.js

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
        var title = o.firstName !== "" ? o.author.firstName + ' ' + o.author.lastName : o.id;
        formatParticipant(o.author);
        if (o.author.kind === "email") {
          var iconElement = '<div class="kifi-notify-email-icon kifi-participant-background-' + o.author.color + '">' + o.author.initial + '</div>'
        } else {
          var image = k.cdnBase + '/users/' + o.author.id + '/pics/100/' + o.author.pictureName;
        }
        add({
          title: title,
          subtitle: 'Sent you a new Kifi Message',
          contentHtml: o.text,
          link: o.title || formatTitleFromUrl(o.url),
          image: image,
          iconElement: iconElement,
          sticky: false,
          showForMs: 60000,
          onClick: $.proxy(onClickMessage, null, o.url, o.locator),
          threadId: o.thread
        });
        break;
      case 'triggered':
        this.hide(o.thread);
        add({
          title: o.title,
          subtitle: o.subtitle,
          contentHtml: o.bodyHtml,
          triggered: true,
          link: o.linkText,
          image: o.image,
          sticky: o.isSticky,
          showForMs: o.showForMs || 60000,
          onClick: $.proxy(onClickGlobal, null, o.thread, o.id, o.url), // handled the same as globals
          threadId: o.thread
        });
        break;
      case 'global':
        this.hide(o.thread);
        add({
          title: o.title,
          subtitle: o.subtitle,
          contentHtml: o.bodyHtml,
          link: o.linkText,
          image: o.image,
          sticky: o.isSticky,
          showForMs: o.showForMs || 60000,
          onClick: $.proxy(onClickGlobal, null, o.thread, o.id, o.url),
          threadId: o.thread
        });
        break;
      }
    }
  };

  function add(params) {
    var $wrap = $('#kifi-notify-notice-wrapper');
    if (!$wrap.length) {
      $wrap = $('<kifi id="kifi-notify-notice-wrapper" class="kifi-root">').appendTo($('body')[0] || 'html');
    }
    var imageHtml;
    if (params.image) {
      imageHtml = '<img src="' + params.image + '" class="kifi-notify-image"/>';
    } else if (params.iconElement) {
      imageHtml = params.iconElement;
    } else {
      imageHtml = '';
    }
    var $item = $(k.render('html/notify_box', {
      formatSnippet: formatMessage.snippet,
      title: params.title,
      subtitle: params.subtitle,
      contentHtml: params.contentHtml,
      triggered: params.triggered,
      image: imageHtml,
      popupClass: '',
      innerClass: imageHtml ? 'kifi-notify-with-image' : 'kifi-notify-without-image',
      link: params.link,
      threadId: params.threadId
    }))
    .appendTo($wrap)
    .fadeIn(params.fadeInMs || 500)
    .click(function(e) {
      if (e.which !== 1) return;
      api.port.emit('remove_notification', $item.data('threadId'));
      $item.off('mouseenter mouseleave');
      fadeItem($item, params);
      if (!$(e.target).hasClass('kifi-notify-close')) {
        params.onClick.call(this, e);
      }
      return false;
    });

    if (!params.sticky) {
      $item.mouseenter(function() {
        clearTimeout($item.data('fadeTimer'));
        $item.stop().css({opacity: '', height: ''});
      }).mouseleave(function() {
        $item.data('fadeTimer', setTimeout(fadeItem.bind(null, $item, params), params.showForMs));
      }).triggerHandler('mouseleave');
    }
  }

  function onClickMessage(url, locator, e) {
    var inThisTab = e.metaKey || e.altKey || e.ctrlKey;
    api.port.emit('open_deep_link', {nUri: url, locator: locator, inThisTab: inThisTab});
    if (inThisTab && url !== document.URL) {
      window.location = url;
    }
  }

  function onClickGlobal(threadId, messageId, url, e) {
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

  function fadeItem($item, params) {
    $item.animate({opacity: 0}, params.fadeOutMs || 300).animate({height: 0}, 300, removeItem.bind(null, $item));
  }

  function removeItem($item) {
    $item.remove();
    $('#kifi-notify-notice-wrapper:empty').remove();
  }
}();
