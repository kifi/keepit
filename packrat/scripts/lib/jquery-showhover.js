/*
jquery-showhover.js
home-grown at FortyTwo, not intended for distribution (yet)
*/

// Invoke on a link or other element that should trigger a hover element to display
// inside it (presumably absolutely positioned relative to the trigger) on mouseenter
// (after a short delay) and to disappear on mouseleave (after a short delay).
// Clicking the trigger toggles visibility of the hover element, with a small
// recovery period during which clicks are ignored after each enter/exit.

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

  var defaultOpts = {
    showDelay: 100,
    hideDelay: 200,
    recovery: 200};  // ms since last enter/leave before click will be honored
  var methods = {
    init: function(opts) {
      var $a = $(this), data = $a.data("hover");
      opts = $.extend(defaultOpts, typeof opts === "function" ? {create: opts} : opts);
      if (data) {
        onMouseEnter(opts.showDelay);
      } else {
        var t0 = +new Date;
        $a.data("hover", data = {lastEnterTime: Infinity});
        setTimeout(opts.create.bind(this, function(hover) {
          data.$h = $(hover).appendTo($a);
          onMouseEnter(Math.max(0, opts.showDelay - (new Date - t0)));
        }));
        $a.on("mouseleave.showHover", function(e) {
          if (!e.toElement || !this.contains(e.toElement)) {
            onMouseLeave(opts.hideDelay);
          }
        }).on("click.showHover", function(e) {
          if (!data.$h[0].contains(e.target) && new Date - data.lastEnterTime > opts.recovery) {
            if ($a.hasClass("kifi-hover-showing")) {
              onMouseLeave(0);
            } else {
              onMouseEnter(0);
            }
          }
        });
      }
      function onMouseEnter(ms) {
        clearTimeout(data.t);
        data.t = setTimeout(function() {
          api.log("[onMouseEnter] showing");
          data.$h.show();
          $a.addClass("kifi-hover-showing");
          data.lastEnterTime = +new Date;
        }, ms);
      }
      function onMouseLeave(ms) {
        clearTimeout(data.t);
        data.t = setTimeout(function() {
          api.log("[onMouseLeave] hiding");
          $a.removeClass("kifi-hover-showing");
          data.$h.css("display", "");
        }, ms);
      }
    },
    destroy: function() {
      $(this).unbind(".showHover");
      $(data.$h).remove();
    }};
}(jQuery);
