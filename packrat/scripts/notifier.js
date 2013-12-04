// @require styles/insulate.css
// @require styles/notifier.css
// @require scripts/api.js
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/formatting.js
// @require scripts/render.js
// @require scripts/html/notify_box.js

var notifier = function () {
  'use strict';

  return {
    removeByAssociatedId: removeByAssociatedId,
    show: function (o) {
      switch (o.category) {
      case 'message':
        removeByAssociatedId(o.thread);
        o.author = o.author || o.participants[0];
        add({
          title: o.author.firstName + " " + o.author.lastName,
          subtitle: 'Sent you a new Kifi Message',
          contentHtml: o.text,
          link: o.title,
          image: cdnBase + '/users/' + o.author.id + '/pics/100/' + o.author.pictureName,
          sticky: false,
          showForMs: 60000,
          onClick: $.proxy(onClickMessage, null, o.url, o.locator),
          associatedId: o.thread
        });
        break;
      case 'global':
        removeByAssociatedId(o.id);
        add({
          title: o.title,
          subtitle: o.subtitle,
          contentHtml: o.bodyHtml,
          link: o.linkText,
          image: o.image,
          sticky: o.isSticky,
          showForMs: o.showForMs || 60000,
          onClick: $.proxy(onClickGlobal, null, o.id, o.url),
          associatedId: o.id
        });
        break;
      }
    }
  };

  function add(params) {
    var $wrap = $('#kifi-notify-notice-wrapper');
    if (!$wrap.length) {
      $wrap = $('<kifi id=kifi-notify-notice-wrapper class=kifi-root>').appendTo($('body')[0] || 'html');
    }

    var $item = $(render('html/notify_box', {
      formatSnippet: getSnippetFormatter,
      title: params.title,
      subtitle: params.subtitle,
      contentHtml: params.contentHtml,
      image: params.image ? '<img src="' + params.image + '" class=kifi-notify-image>' : '',
      popupClass: '',
      innerClass: params.image ? 'kifi-notify-with-image' : 'kifi-notify-without-image',
      link: params.link,
      associatedId: params.associatedId
    }))
    .appendTo($wrap)
    .fadeIn(params.fadeInMs || 500)
    .click(function(e) {
      if (e.which !== 1) return;
      api.port.emit('remove_notification', {associatedId: $item.data('associatedId')});
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

  function onClickGlobal(id, url, e) {
    api.port.emit('set_global_read', {noticeId: id});
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
    $item.animate({opacity: 0}, params.fadeOutMs || 300).animate({height: 0}, 300, removeItem);
  }

  function removeItem($item) {
    $item.remove();
    $('#kifi-notify-notice-wrapper:empty').remove();
  }

  function removeByAssociatedId(id) {
    removeItem($('.kifi-notify-item-wrapper[data-associated-id="' + id + '"]'));
  }
}();
