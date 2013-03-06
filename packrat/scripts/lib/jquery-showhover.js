/*
jquery-showhover.js
home-grown at FortyTwo, not intended for distribution (yet)
*/

// Invoke on a link or other element that should trigger a hover element to display
// inside it (presumably absolutely positioned relative to the trigger) on mouseenter
// (after a short delay) and to disappear on mouseleave (after a short delay).
// Clicking the trigger toggles visibility of the hover element, with a small
// refractory period after each show/hide.

!function($) {
  $.fn.showHover = function(method) {
    if (this.length > 1) {
      $.error("jQuery.showHover invoked on too many elements: " + this.length);
    }
    if (typeof method === "string") {
      var f = methods[method];
      if (f) {
        f.apply(this[0], Array.prototype.slice.call(arguments, 1));
      } else {
        $.error("No method " +  method + " on jQuery.showHover");
      }
    } else {
      methods.init.apply(this[0], arguments);
    }
    return this;
  }

  var methods = {
    init: function(createHover) {
      var $a = $(this), data = $a.data();
      if (!data.hover) {
        data.hover = {lastEventTime: 0};
        var t, $h = $(createHover.call(this)).hide().appendTo($a);
        $a.on("mouseover.showHover", function(e) {
          api.log("[mouseover.showHover]", e.target);
          clearTimeout(t);
          t = setTimeout(function() {
            $h.show(); $a.addClass("kifi-hover");
            data.hover.lastEventTime = +new Date;
          }, 200);
        }).on("mouseout.showHover", function(e) {
          api.log("[mouseout.showHover]", e);
          if (e.toElement && this.contains(e.toElement)) return;
          clearTimeout(t);
          t = setTimeout(function() {
            $h.hide(); $a.removeClass("kifi-hover");
            data.hover.lastEventTime = +new Date;
          }, 500);
        }).on("click.showHover", function(e) {
          if ($h[0].contains(e.target)) return;
          api.log("[click.showHover]", this);
          if (new Date - data.hover.lastEventTime > 200) {
            clearTimeout(t);
            $h.toggle(); $a.toggleClass("kifi-hover");
          }
        }).on("mousedown.showHover", function(e) {
          api.log("[mousedown.showHover]");
          e.preventDefault();  // prevents selection via drag or double-click
        });
      }
    },
    enter: function() {
      $(this).triggerHandler("mouseover.showHover");
    },
    unbind: function() {
      $(this).unbind(".showHover");
      // TODO: destroy hover?
    }};
}(jQuery);
