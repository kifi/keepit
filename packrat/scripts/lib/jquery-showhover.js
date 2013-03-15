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

  var defaultOpts = {
    showDelay: 100,
    hideDelay: 200,
    recovery: 200,  // ms since last show before click will be honored
    reuse: true};
  var methods = {
    init: function(opts) {
      var $a = $(this), data = $a.data("hover");
      opts = $.extend(defaultOpts, typeof opts === "function" ? {create: opts} : opts);
      if (data) {
        onMouseEnter(opts.showDelay);
      } else {
        var t0 = +new Date;
        $a.data("hover", data = {lastShowTime: 0});
        setTimeout(opts.create.bind(this, function(hover, useSize) {
          var $h = $(hover);
          if (useSize) {
            $h.css({visibility: "hidden", display: "block"}).appendTo($a);
            var r = $h[0].getBoundingClientRect();
            $h.css({visibility: "", display: ""});
            useSize.call($h[0], r.width, r.height);
          } else {
            $h.appendTo($a);
          }
          data.$h = $h;
          onMouseEnter(Math.max(0, opts.showDelay - (new Date - t0)));
        }));
        $a.on("mouseleave.showHover", function(e) {
          if (!e.toElement || !this.contains(e.toElement)) {
            onMouseLeave(opts.hideDelay, opts.reuse);
          }
        }).on("click.showHover", function(e) {
          if (!data.$h[0].contains(e.target) && new Date - data.lastShowTime > opts.recovery) {
            if ($a.hasClass("kifi-hover-showing")) {
              onMouseLeave(0, opts.reuse);
            } else {
              onMouseEnter(0);
            }
          }
        });
      }
      function onMouseEnter(ms) {
        clearTimeout(data.t);
        data.t = setTimeout(function() {
          data.$h.show();
          $a.addClass("kifi-hover-showing");
          data.lastShowTime = +new Date;
        }, ms);
      }
      function onMouseLeave(ms, reuse) {
        clearTimeout(data.t);
        data.t = setTimeout(function() {
          $a.removeClass("kifi-hover-showing");
          if (reuse) {
            data.$h.css("display", "");
          } else {
            $a.showHover("destroy");
          }
        }, ms);
      }
    },
    destroy: function() {
      var $a = $(this);
      $(($a.data("hover") || 0).$h).remove();
      $a.unbind(".showHover").removeData("hover");
    }};
}(jQuery);
