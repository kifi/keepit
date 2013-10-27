// @require styles/insulate.css
// @require styles/notifier.css
// @require scripts/api.js
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/formatting.js
// @require scripts/render.js

var notifier = function() {
  'use strict';
  var defaultParams = {
    wrapperClass: "",
    fadeInMs: 500,
    fadeOutMs: 200,
    showForMs: 7000,
    image: "",
    link: "",
    contentHtml: "",
    sticky: false,
    popupClass: "",
    clickAction: $.noop,
    closeOnClick: true,
    associatedId: ""
  };

  return {
  removeByAssociatedId: removeByAssociatedId,
  show: function(o) {
    switch (o.category) {
      case "message":
        removeByAssociatedId(o.thread, {fade: false});
        add({
          title: o.author.firstName + " " + o.author.lastName,
          subtitle: "Sent you a new Kifi Message",
          contentHtml: o.text,
          link: o.title,
          image: cdnBase + "/users/" + o.author.id + "/pics/100/0.jpg",
          sticky: false,
          showForMs: 60000,
          clickAction: function(e) {
            var inThisTab = e.metaKey || e.altKey || e.ctrlKey;
            api.port.emit("open_deep_link", {nUri: o.url, locator: o.locator, inThisTab: inThisTab});
            if (inThisTab && o.url !== document.URL) {
              window.location = o.url;
            }
            return false;
          },
          associatedId: o.thread
        });
        break;
      case "global":
        removeByAssociatedId(o.id, {fade: false});
        add({
          title: o.title,
          subtitle: o.subtitle,
          contentHtml: o.bodyHtml,
          link: o.linkText,
          image: o.image,
          sticky: o.isSticky || false,
          showForMs: o.showForMs || 60000,
          clickAction: function(e) {
            api.port.emit("set_global_read", {noticeId: o.id});
            var inThisTab = e.metaKey || e.altKey || e.ctrlKey;
            if (o.url && o.url !== document.URL) {
              if (inThisTab) {
                window.location = o.url;
              } else {
                window.open(o.url, '_blank').focus();
              }
            }
          },
          associatedId: o.id
        });
        break;
    }
  }};

  function add(params) {
    params = $.extend(defaultParams, params);

    render("html/notify_box", {
      formatSnippet: getSnippetFormatter,
      title: params.title,
      subtitle: params.subtitle,
      contentHtml: params.contentHtml,
      image: params.image ? '<img src="' + params.image + '" class=kifi-notify-image>' : "",
      popupClass: params.popupClass,
      innerClass: params.image ? "kifi-notify-with-image" : "kifi-notify-without-image",
      link: params.link,
      associatedId: params.associatedId
    }, function(html) {
      var $item = $(html);

      var $wrap = $("#kifi-notify-notice-wrapper");
      if (!$wrap.length) {
        $wrap = $("<kifi id=kifi-notify-notice-wrapper class=kifi-root>").appendTo($("body")[0] || "html");
      }
      $wrap.addClass(params.wrapperClass).append($item);

      ["beforeOpen", "afterOpen", "beforeClose", "afterClose"].forEach(function(val) {
        $item.data(val, $.isFunction(params[val]) ? params[val] : $.noop);
      });

      $item.fadeIn(params.fadeInMs, function() {
        $item.data("afterOpen")();
      });

      if (!params.sticky) {
        startFadeTimer($item, params);
      }


      $item.bind("mouseenter", function(event) {
        if (!params.sticky) {
          clearTimeout($item.data("fadeOutTimer"));
          $item.stop().css({ opacity: "", height: "" });
        }
      }).bind("mouseleave", function(event) {
        if (!params.sticky) {
          startFadeTimer($item, params);
        }
      }).bind("click", function(event) {
        if (event.which !== 1) return;
        if (params.closeOnClick) {
          removeSpecific($item, {}, true);
        }
        return params.clickAction(event);
      });

      $item.find(".kifi-notify-close").click(function(event) {
        removeSpecific($item, {}, true);
        return false;
      });
    });

    return true;
  }

  function fadeItem($item, params, unbindEvents) {
    var params = params || {},
      fade = params.fade !== false,
      fadeOutMs = params.fadeOutMs || 300,
      manualClose = unbindEvents;

    ($item.data("beforeClose") || $.noop)($item, manualClose);

    if (unbindEvents) {
      $item.unbind("mouseenter mouseleave");
    }

    if (fade) {
      $item.animate({opacity: 0}, fadeOutMs).animate({height: 0}, 300, removeItem);
    } else {
      removeItem();
    }

    function removeItem() {
      ($item.data("afterClose") || $.noop)($item, manualClose);
      $item.remove();
      $("#kifi-notify-notice-wrapper").not(":has(.kifi-notify-item-wrapper)").remove();
    }
  }

  function removeSpecific($item, params, unbindEvents) {
    api.port.emit("remove_notification", {associatedId: $item.data("associated-id")});
    fadeItem($item, params || {}, unbindEvents);
  }

  function removeByAssociatedId(associatedId, params) {
    var $wrap = $("#kifi-notify-notice-wrapper");
    $wrap.find(".kifi-notify-item-wrapper[data-associated-id='" + associatedId + "']").each(function() {
      fadeItem($(this), params || {}, true);
    });
  }

  function startFadeTimer($item, params) {
    $item.data("fadeOutTimer", setTimeout(function() {
      fadeItem($item, params);
    }, params.showForMs));
  }

  function stop(params) {
    var $wrap = $("#kifi-notify-notice-wrapper");
    (params.beforeClose || $.noop)($wrap);
    $wrap.fadeOut(function() {
      $wrap.remove();
      (params.afterClose || $.noop)();
    });
  }
}();
