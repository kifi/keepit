// @require styles/notifier.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/render.js

var notifier = {
  removeByAssociatedId: function(associatedId) {
    KifiNotification.removeByAssociatedId(associatedId);
  },
  show: function(data) {
    var o = data;
    switch (data.category) {
      case "message":
        KifiNotification.removeByAssociatedId(o.thread, {fade: false});
        KifiNotification.add({
          title: o.author.firstName + " " + o.author.lastName,
          subtitle: "Sent you a new Kifi Message",
          contentHtml: o.text.trim().length > 200 ? o.text.substring(0, 200).trim() + "…" : o.text,
          link: o.title,
          image: cdnBase + "/users/" + o.author.id + "/pics/100/0.jpg",
          sticky: false,
          showForMs: 60000,
          clickAction: function() {
            api.port.emit("open_deep_link", {nUri: o.url, locator: o.locator});
            return false;
          },
          associatedId: o.thread
        });
        break;
      case "global":
        KifiNotification.removeByAssociatedId(o.id, {fade: false});
        KifiNotification.add({
          title: o.title,
          subtitle: o.subtitle,
          contentHtml: o.bodyHtml,
          link: o.linkText,
          image: o.image,
          sticky: o.isSticky || false,
          showForMs: o.showForMs || 60000,
          clickAction: function() {
            api.port.emit("set_global_read", {noticeId: data.id});
            if (o.url) {
              var win = window.open(o.url, "_blank");
              win.focus();
            }
          },
          associatedId: o.id
        });
        break;
    }
  }
};

var KifiNotification = {
  defaultParams: {
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
  },

  add: function(params) {
    params = $.extend(KifiNotification.defaultParams, params);

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
        $wrap = $("<div id=kifi-notify-notice-wrapper>").appendTo($("body")[0] || "html");
      }
      $wrap.addClass(params.wrapperClass).append($item);

      ["beforeOpen", "afterOpen", "beforeClose", "afterClose"].forEach(function(val) {
        $item.data(val, $.isFunction(params[val]) ? params[val] : $.noop);
      });

      $item.fadeIn(params.fadeInMs, function() {
        $item.data("afterOpen")();
      });

      if (!params.sticky) {
        KifiNotification.startFadeTimer($item, params);
      }


      $item.bind("mouseenter", function(event) {
        if (!params.sticky) {
          clearTimeout($item.data("fadeOutTimer"));
          $item.stop().css({ opacity: "", height: "" });
        }
      }).bind("mouseleave", function(event) {
        if (!params.sticky) {
          KifiNotification.startFadeTimer($item, params);
        }
      }).bind("click", function(event) {
        if (params.closeOnClick) {
          KifiNotification.removeSpecific($item, {}, true);
        }
        params.clickAction();
      });

      $item.find(".kifi-notify-close").click(function(event) {
        KifiNotification.removeSpecific($item, {}, true);
        return false;
      });
    });

    return true;
  },

  fadeItem: function($item, params, unbindEvents) {
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
  },

  removeSpecific: function($item, params, unbindEvents) {
    api.port.emit("remove_notification", {associatedId: $item.data("associated-id")});
    KifiNotification.fadeItem($item, params || {}, unbindEvents);
  },

  removeByAssociatedId: function(associatedId, params) {
    var $wrap = $("#kifi-notify-notice-wrapper");
    $wrap.find(".kifi-notify-item-wrapper[data-associated-id='" + associatedId + "']").each(function() {
      KifiNotification.fadeItem($(this), params || {}, true);
    });
  },

  startFadeTimer: function($item, params) {
    $item.data("fadeOutTimer", setTimeout(function() {
      KifiNotification.fadeItem($item, params);
    }, params.showForMs));
  },

  stop: function(params) {
    var $wrap = $("#kifi-notify-notice-wrapper");
    (params.beforeClose || $.noop)($wrap);
    $wrap.fadeOut(function() {
      $wrap.remove();
      (params.afterClose || $.noop)();
    });
  }
};
