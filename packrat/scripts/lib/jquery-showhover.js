/*
jquery-showhover.js
home-grown at FortyTwo, not intended for distribution (yet)
*/

// Invoke on a link or other element that should trigger a hover element to display
// inside it (presumably absolutely positioned relative to the trigger) on mouseenter
// (after a short delay) and to disappear on mouseleave (after a short delay).
// Clicking the trigger toggles visibility of the hover element, with a small
// recovery period during which clicks are ignored after each show.

!function($) {
  $.fn.showHover = function(opts) {
    return this.each(function() {
      enter.call(this, opts);
    });
  };

  function enter(opts) {
      var $a = $(this), data = $a.data("hover");
      opts = $.extend({
          create: createFromDataAttr,
          showDelay: 100,
          hideDelay: 0},
        typeof opts === "function" ? {create: opts} : opts);
      if (data) {
        if (opts.eventTime <= data.leaveTime) {
          api.log("[showHover.enter] already left");
        } else {
          onEnter(opts.showDelay);
        }
      } else {
        var t0 = +new Date;
        $a.data("hover", data = {lastShowTime: 0});
        setTimeout(opts.create.bind(this, function(hover, useSize) {  // async to allow create to reference return val
          var $h = $(hover);
          if (useSize) {
            $h.css({visibility: "hidden", display: "block"}).appendTo($a);
            var r = $h[0].getBoundingClientRect();
            $h.css({visibility: "", display: ""}).detach();
            useSize.call($h[0], r.width, r.height);
          }
          data.$h = $h;
          onEnter(opts.showDelay - (new Date - t0));
        }));
        $a.on("mouseout.showHover", function(e) {
          if (!e.relatedTarget || !this.contains(e.relatedTarget)) {
            onMouseLeave(opts.hideDelay, e);
          }
        });
        if (opts.click) $a.on("mousedown.showHover", function(e) {
          if (data.$h[0].contains(e.target) || isFadingOut()) return;
          if (opts.click == "hide") {
            hide();
          } else if (opts.click == "toggle" && (e.isTrigger || new Date - data.lastShowTime > 160)) {
            if ($a.hasClass("kifi-hover-showing")) {
              hide();
            } else {
              show();
            }
          }
        });
      }
      function onEnter(ms) {
        clearTimeout(data.hide), delete data.hide;
        if (!$a.hasClass("kifi-hover-showing") && !isFadingOut()) {
          if (ms > 0) {
            data.show = setTimeout(show, ms);
          } else {
            show();
          }
        }
      }
      function onMouseLeave(ms, e) {
        clearTimeout(data.show), delete data.show;
        data.leaveTime = e.timeStamp;
        if ($a.hasClass("kifi-hover-showing")) {
          if (ms && between(e.clientX, e.clientY)) {
            document.addEventListener("mousemove", onMouseMoveMaybeHide, true);
            $a.on("mouseover.showHover", onMouseOverDoNotHide);
            data.hide = setTimeout(hide, ms);
          } else {
            hide();
          }
        }
      }
      function onMouseMoveMaybeHide(e) {
        if (!between(e.clientX, e.clientY)) {
          document.removeEventListener("mousemove", onMouseMoveMaybeHide, true);
          $a.off("mouseover", onMouseOverDoNotHide);
          hide();
        }
      }
      function onMouseOverDoNotHide(e) {
        document.removeEventListener("mousemove", onMouseMoveMaybeHide, true);
        $a.off("mouseover", onMouseOverDoNotHide);
        clearTimeout(data.hide), delete data.hide;
      }
      function show() {
        delete data.show;
        if ($a.hasClass("kifi-hover-showing") || isFadingOut()) return;
        data.$h.appendTo($a).each(function(){this.offsetHeight});
        $a.addClass("kifi-hover-showing");
        data.lastShowTime = +new Date;
      }
      function hide() {
        api.log("[hide]");
        clearTimeout(data.show || data.hide);
        delete data.show, delete data.hide;
        if (!$a.hasClass("kifi-hover-showing")) return;
        api.log("[hide] really");
        $a.removeClass("kifi-hover-showing");
        data.fadeOutStartTime = +new Date;
        data.$h.on("transitionend webkitTransitionEnd", function end(e) {
          if (e.target === this && e.originalEvent.propertyName === "opacity") {
            delete data.fadeOutStartTime;
            data.$h.off("transitionend webkitTransitionEnd", end).remove();
            $a.unbind(".showHover").removeData("hover").trigger("hover:hide");
          }
        });
      }
      function isFadingOut() {  // recovers within .5 sec if property not cleared when done fading
        return new Date - (data.fadeOutStartTime || 0) < 500;
      }
      // Returns whether the viewport coords (x, y) are in the trapezoid between the top edge
      // of hover trigger element and the bottom edge of the hover element.
      function between(x, y) {
        var rT = $a[0].getBoundingClientRect(), rH = data.$h[0].getBoundingClientRect();
        return y >= rH.bottom && y <= rT.top &&
          !leftOf(x, y, rH.left, rH.bottom, rT.left, rT.top) &&
          leftOf(x, y, rH.right, rH.bottom, rT.right, rT.top);
      }
  }

  // Returns whether (x, y) is left of the line between (x1, y1) and (x2, y2).
  function leftOf(x, y, x1, y1, x2, y2) {
    return (x2 - x1) * (y - y1) > (y2 - y1) * (x - x1);
  }

  function createFromDataAttr(cb) {
    cb($("<div>", {"class": this.dataset.tipClass, "html": this.dataset.tipHtml}));
  }
}(jQuery);
