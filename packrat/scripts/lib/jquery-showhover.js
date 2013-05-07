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
  $.fn.showHover = function(method) {
    if (this.length > 1) {
      $.error("jQuery.showHover invoked on " + this.length + " elements");
    }
    if (typeof method === "string") {
      var f = methods[method];
      if (f) {
        f.apply(this[0], Array.prototype.slice.call(arguments, 1));
      } else {
        $.error("jQuery.showHover has no method '" +  method + "'");
      }
    } else {
      methods.init.apply(this[0], arguments);
    }
    return this;
  }

  var methods = {
    init: function(opts) {
      var $a = $(this), data = $a.data("hover");
      opts = $.extend({
          create: createFromDataAttr,
          showDelay: 100,
          hideDelay: 0,
          recovery: 160,  // ms since last show before click will be honored
          reuse: true},
        typeof opts === "function" ? {create: opts} : opts);
      if (data) {
        onMouseEnter(opts.showDelay);
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
          onMouseEnter(Math.max(0, opts.showDelay - (new Date - t0)));
        }));
        $a.on("mouseout.showHover", function(e) {
          if (!e.relatedTarget || !this.contains(e.relatedTarget)) {
            onMouseLeave(opts.hideDelay, e);
          }
        }).on("click.showHover", function(e) {
          if (!data.$h[0].contains(e.target) && (e.isTrigger || new Date - data.lastShowTime > opts.recovery)) {
            if ($a.hasClass("kifi-hover-showing")) {
              onMouseLeave();
            } else {
              onMouseEnter(0);
            }
          }
        });
      }
      function onMouseEnter(ms) {
        data.inEither = true;
        clearTimeout(data.t);
        if (ms) {
          data.t = setTimeout(show, ms);
        } else {
          show();
        }
      }
      function onMouseLeave(ms, e) {
        data.inEither = false;
        clearTimeout(data.t);
        if (ms && between(e.clientX, e.clientY)) {
          document.addEventListener("mousemove", onMouseMove, true);
          data.t = setTimeout(hide, ms);
        } else {
          hide();
        }
      }
      function onMouseMove(e) {
        if (!between(e.clientX, e.clientY)) {
          if (!data.inEither) {
            clearTimeout(data.t);
            hide();
          }
          document.removeEventListener("mousemove", onMouseMove, true);
        }
      }
      function show() {
        data.$h.appendTo($a).each(function(){this.offsetHeight});
        $a.addClass("kifi-hover-showing");
        data.lastShowTime = +new Date;
      }
      function hide() {
        $a.removeClass("kifi-hover-showing");
        if (opts.fadesOut) {
          data.$h.on("transitionend webkitTransitionEnd", function f(e) {
            if (e.originalEvent.propertyName === "opacity") {
              data.$h.off("transitionend webkitTransitionEnd", f);
              if (!$a.hasClass("kifi-hover-showing")) {
                finishHiding();
              }
            }
          });
        } else {
          finishHiding();
        }
        function finishHiding() {
          if (opts.reuse) {
            data.$h.detach();
          } else {
            $a.showHover("destroy");
          }
          $a.trigger("hover:hide");
        }
      }
      // Returns whether the viewport coords (x, y) are in the trapezoid between the top edge
      // of hover trigger element and the bottom edge of the hover element.
      function between(x, y) {  // TODO: fix "Cannot read property '0' of undefined" from onMouseLeave (from keeper)
        var rT = $a[0].getBoundingClientRect(), rH = data.$h[0].getBoundingClientRect();
        return y >= rH.bottom && y <= rT.top &&
          !leftOf(x, y, rH.left, rH.bottom, rT.left, rT.top) &&
          leftOf(x, y, rH.right, rH.bottom, rT.right, rT.top);
      }
    },
    destroy: function() {
      var $a = $(this);
      $(($a.data("hover") || 0).$h).remove();
      $a.unbind(".showHover").removeData("hover");
    }
  };

  // Returns whether (x, y) is left of the line between (x1, y1) and (x2, y2).
  function leftOf(x, y, x1, y1, x2, y2) {
    return (x2 - x1) * (y - y1) > (y2 - y1) * (x - x1);
  }

  function createFromDataAttr(cb) {
    cb($("<div>", {"class": this.dataset.tipClass, "html": this.dataset.tipHtml}));
  }
}(jQuery);
