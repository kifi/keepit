/*
jquery-bindhover.js
home-grown at FortyTwo, not intended for distribution (yet)
*/

// Invoke on a link or other element that should trigger a hover element to display
// inside it (presumably absolutely positioned relative to the trigger) on mouseover
// (after a short delay) and to disappear on mouseout (after a short delay).
// Clicking the trigger can hide the hover element or toggle its visibility, with a small
// recovery period during which clicks are ignored.

!function($) {
  $.fn.bindHover = function(selector, create) {
    if (selector == "destroy") {
      return this.each(function() {
        $(($(this).data("hover") || {}).$h).remove();
      }).unbind(".bindHover").removeData("hover");
    }
    if (!create) create = selector, selector = null;
    if (typeof create != "function") create = createFromDataAttr(create);
    return this.on("mouseover.bindHover", selector, function(e) {
      if (e.relatedTarget && this.contains(e.relatedTarget)) return;
      var $a = $(this), data = getOrCreateData($a);
      data.mouseoverTimeStamp = e.timeStamp || +new Date;
      data.mouseoverEl = e.target;
      if (!data.$h) {  // fresh start
        createHover($a, data, create);
      } else { // hover currently showing or at least initiated
        if (data.opts && data.opts.hideDelay) {
          clearTimeout(data.hide), delete data.hide;
          document.removeEventListener("mousemove", data.move, true);
          delete data.move;
        }
      }
    }).on("mouseout.bindHover", selector, function(e) {
      if (e.relatedTarget && this.contains(e.relatedTarget)) return;
      var $a = $(this), data = getOrCreateData($a);
      clearTimeout(data.show), delete data.show;
      data.mouseoutTimeStamp = e.timeStamp || +new Date;
      if ($a.hasClass("kifi-hover-showing")) {
        if (data.opts && data.opts.hideDelay && between(this, data.$h[0], e.clientX, e.clientY)) {
          data.hide = setTimeout(hide.bind(this), data.opts.hideDelay);
          data.move = onMouseMoveMaybeHide.bind(this);
          document.addEventListener("mousemove", data.move, true);
        } else {
          hide.call(this);
        }
      } else if (!isFadingOut(data)) {
        delete data.$h;
      }
    }).on("mousedown.bindHover", selector, function(e) {
      var $a = $(this), data = getOrCreateData($a);
      if (!data.opts || !data.opts.click || data.$h && data.$h[0].contains(e.target) || isFadingOut(data)) return;
      if (data.opts.click == "hide") {
        hide.call(this);
      } else if (data.opts.click == "toggle") {
        if (e.isTrigger || new Date - Math.max(data.showTime || 0, data.fadeOutStartTime || 0) > 160) {
          if ($a.hasClass("kifi-hover-showing")) {
            hide.call(this);
          } else if (data.$h) {
            show.call(this);
          } else {
            createHover($a, data, create, true);
          }
        }
      }
    });
  };

  function createHover($a, data, create, showImmediately) {
    var createStartTime = +new Date;
    create.call($a[0], function configureHover(hover, opts) {
      if (!opts && typeof hover == "object" && !hover.nodeType && !hover.jquery) {  // hover is really opts
        createFromDataAttr(hover).call($a[0], configureHover);
        return;
      }
      if (data.mouseoutTimeStamp > data.mouseoverTimeStamp) {
        api.log("[bindHover.configureHover] left before could configure");
        return;
      }
      if (data.$h) return;  // old attempt may have succeeded very slowly
      var $h = data.$h = $(hover);
      data.opts = opts = opts || {};
      if (opts.position) {
        $h.css({visibility: "hidden", display: "block"}).appendTo($a);
        var r = $h[0].getBoundingClientRect();
        $h.css({visibility: "", display: ""}).detach();
        opts.position.call($h[0], r.width, r.height);
      }
      var ms = showImmediately ? 0 : (opts.showDelay || 0) - (new Date - createStartTime);
      if (ms > 0) {
        data.show = setTimeout(show.bind($a[0]), ms);
      } else {
        show.call($a[0]);
      }
    });
  }
  function show() {
    var $a = $(this), data = getOrCreateData($a);
    clearTimeout(data.show);
    delete data.show;
    if (!data.$h || $a.hasClass("kifi-hover-showing") || isFadingOut(data)) return;
    data.$h.appendTo($a).each(function(){this.offsetHeight});
    $a.addClass("kifi-hover-showing");
    data.showTime = +new Date;
  }
  function hide() {
    var $a = $(this), data = getOrCreateData($a);
    clearTimeout(data.show || data.hide);
    delete data.show, delete data.hide;
    if (data.move) {
      document.removeEventListener("mousemove", data.move, true);
      delete data.move;
    }
    if ($a.hasClass("kifi-hover-showing")) {
      $a.removeClass("kifi-hover-showing");
      data.fadeOutStartTime = +new Date;
      data.$h.on("transitionend webkitTransitionEnd", function end(e) {
        if (e.target === this && e.originalEvent.propertyName === "opacity") {
          delete data.fadeOutStartTime;
          data.$h.off("transitionend webkitTransitionEnd", end).remove();
          delete data.$h;
          if (data.mouseoverTimeStamp > data.mouseoutTimeStamp && this.contains(data.mouseoverEl)) {
            api.log("[bindHover.hide:transitionend] faking mouseout");
            $a.trigger($.Event("mouseout", {relatedTarget: document}));
          }
        }
      });
    } else if (!isFadingOut(data)) {
      delete data.$h;
    }
  }
  function onMouseMoveMaybeHide(e) {
    var $a = $(this), data = getOrCreateData($a);
    if (!between(this, data.$h[0], e.clientX, e.clientY)) {
      document.removeEventListener("mousemove", data.move, true);
      delete data.move;
      hide.call(this);
    }
  }

  function isFadingOut(data) {  // recovers within .5 sec if property not cleared when done fading
    return new Date - (data.fadeOutStartTime || 0) < 500;
  }

  // Returns whether the viewport coords (x, y) are in the trapezoid between the top edge
  // of first element and the bottom edge of the second element.
  function between(elBelow, elAbove, x, y) {
    var rB = elBelow.getBoundingClientRect(), rA = elAbove.getBoundingClientRect();
    return y >= rA.bottom && y <= rB.top &&
      !leftOf(x, y, rA.left, rA.bottom, rB.left, rB.top) &&
      leftOf(x, y, rA.right, rA.bottom, rB.right, rB.top);
  }

  // Returns whether (x, y) is left of the line between (x1, y1) and (x2, y2).
  function leftOf(x, y, x1, y1, x2, y2) {
    return (x2 - x1) * (y - y1) > (y2 - y1) * (x - x1);
  }

  function createFromDataAttr(opts) {
    return function(configureHover) {
      configureHover($("<div>", {"class": this.dataset.tipClass, "html": this.dataset.tipHtml}), opts);
    };
  }

  function getOrCreateData($el) {
    var data = $el.data("bindHover");
    if (!data) {
      $el.data("bindHover", data = {});
    }
    return data;
  }
}(jQuery);
