// @require styles/notifier.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/render.js

var notifier = {
  show: function(data) {
    var o = data.details;
    switch (data.category) {
      case "comment":
        KifiNotification.add({
          title: o.author.firstName + " " + o.author.lastName,
          subtitle: "Wrote a new KiFi Comment",
          contentHtml: o.text,
          link: o.title,
          image: cdnBase + "/users/" + o.author.id + "/pics/100/0.jpg",
          sticky: false,
          showForMs: 60000,
          clickAction: function() {
            api.port.emit("open_deep_link", {nUri: o.page, locator: o.locator});
            return false;
          }
        });
        break;
      case "message":
        KifiNotification.add({
          title: o.authors[0].firstName + " " + o.authors[0].lastName,
          subtitle: "Sent you a new KiFi Message",
          contentHtml: o.text,
          link: o.title,
          image: cdnBase + "/users/" + o.authors[0].id + "/pics/100/0.jpg",
          sticky: false,
          showForMs: 60000,
          clickAction: function() {
            api.port.emit("open_deep_link", {nUri: o.page, locator: o.locator});
            return false;
          }
        });
        break;
      case "server_generated":
        KifiNotification.add({
          title: o.title,
          subtitle: o.subtitle,
          contentHtml: o.bodyHtml,
          link: o.linkText,
          image: o.image,
          sticky: o.sticky || false,
          showForMs: o.showForMs || 60000,
          clickAction: function() {
            if (o.url) {
              var win = window.open(o.url, "_blank");
              win.focus();
            }
          }
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
    closeOnClick: true
  },

  add: function(params) {
    params = $.extend(KifiNotification.defaultParams, params);

    if ($("#kifi-notify-notice-wrapper").length == 0) {
      $("body").append("<div id=kifi-notify-notice-wrapper>");
    }

    render("html/notify_box.html", {
      formatSnippet: getSnippetFormatter,
      title: params.title,
      subtitle: params.subtitle,
      contentHtml: params.contentHtml,
      image: params.image ? '<img src="' + params.image + '" class=kifi-notify-image>' : "",
      popupClass: params.popupClass,
      innerClass: params.image ? "kifi-notify-with-image" : "kifi-notify-without-image",
      link: params.link
    }, function(html) {
      var $item = $(html);

      $("#kifi-notify-notice-wrapper").addClass(params.wrapperClass).append($item);

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

    $item.data("beforeClose")($item, manualClose);

    if (unbindEvents) {
      $item.unbind("mouseenter mouseleave");
    }

    if (fade) {
      $item.animate({opacity: 0}, fadeOutMs).animate({height: 0}, 300, removeItem);
    } else {
      removeItem();
    }

    function removeItem() {
      $item.data("afterClose")($item, manualClose);
      $item.remove();
      if ($(".kifi-notify-item-wrapper").length == 0) {
        $("#kifi-notify-notice-wrapper").remove();
      }
    }
  },

  removeSpecific: function($item, params, unbindEvents) {
    KifiNotification.fadeItem($item, params || {}, unbindEvents);
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
      $(this).remove();
      (params.afterClose || $.noop)();
    });
  }
};
