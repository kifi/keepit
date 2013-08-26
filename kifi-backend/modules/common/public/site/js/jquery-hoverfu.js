/*
jquery-hoverfu.js
home-grown at FortyTwo, not intended for distribution (yet)
*/

// Invoke on a link or other element that should trigger a hover element to display
// inside it (presumably absolutely positioned relative to the trigger) on mouseover
// (after a short delay) and to disappear on mouseout (after a short delay).
// Clicking the trigger can hide the hover element or toggle its visibility, with a small
// recovery period during which clicks are ignored.

!function($) {
  $.fn.hoverfu = function(selector, create) {
    if (selector == "destroy") {
      return this.each(function() {
        var data = getData(this) || {};
        clearTimeout(data.show || data.hide);
        data.$h && data.$h.remove();
      }).off(".hoverfu").removeData("hoverfu");
    }
    if (!create) create = selector, selector = null;
    if (typeof create != "function") create = createFromDataAttr(create);
    return this
    .on("mouseover.hoverfu", selector, $.proxy(onMouseOver, null, create))
    .on("mouseout.hoverfu", selector, onMouseOut)
    .on("mousedown.hoverfu", selector, function(e) {
      var data = getData(this);
      if (!data.opts || !data.opts.click || isFadingOut(data)) return;
      if (data.opts.click == "hide") {
        hide.call(this);
      } else if (data.opts.click == "toggle") {
        // Ignore clicks that occur very soon (160ms) after a show or hide begins
        // because the user's intent is likely already happening.
        if (e.isTrigger || Date.now() - Math.max(data.showTime || 0, data.fadeOutStartTime || 0) > 160) {
          if (data.showing) {
            hide.call(this);
          } else if (data.$h) {
            show.call(this);
          } else {
            createHover(data, create, true);
          }
        }
      }
    });
  };

  function createHover(data, create, showImmediately) {
    var createStartTime = Date.now(), a = data.$a[0];
    create.call(a, function configureHover(hover, opts) {
      if (data.$h) return;  // old attempt may have succeeded very slowly
      if (!opts && typeof hover == "object" && !hover.nodeType && !hover.jquery) {  // hover is really opts
        createFromDataAttr(hover).call(a, configureHover);
        return;
      }
      if (data.mouseoutTimeStamp > data.mouseoverTimeStamp) {
        console.log("[hoverfu.configureHover] left before could configure");
        return;
      }
      var $h = data.$h = $(hover).data("hoverfu", data);
      data.opts = opts = opts || {};
      if (opts.position) {
        $h.css({visibility: "hidden", display: "block"}).appendTo("body")
          .position(opts.position).css({visibility: "", display: ""}).detach();
      }
      if (opts.canLeaveFor) {
        $h.on("mouseover.hoverfu", $.proxy(onMouseOver, null, null))
          .on("mouseout.hoverfu", onMouseOut);
      }
      var ms = showImmediately ? 0 : (opts.mustHoverFor || 0) - (Date.now() - createStartTime);
      if (ms > 0) {
        data.show = setTimeout(show.bind(a), ms);
      } else {
        show.call(a);
      }
    });
  }
  function show() {
    var data = getData(this);
    clearTimeout(data.show);
    delete data.show;
    if (!data.$h || data.showing || isFadingOut(data)) return;
    data.$h.appendTo("body").each(function(){this.offsetHeight}).addClass("showing");
    data.showTime = Date.now();
    data.showing = true;
    if (data.opts.hideAfter) {
      data.hide = setTimeout(hide.bind(this), data.opts.hideAfter);
    }
  }
  function hide() {
    var data = getData(this);
    clearTimeout(data.show || data.hide);
    delete data.show, delete data.hide;
    $(document).off("mousemove.hoverfu");
    if (data.showing) {
      data.showing = false;
      data.fadeOutStartTime = Date.now();
      data.$h.removeClass("showing").on("transitionend", function end(e) {
        if (e.target === this && e.originalEvent.propertyName === "opacity") {
          delete data.fadeOutStartTime;
          data.$h.off("transitionend", end).remove();
          delete data.$h;
          if (data.mouseoverTimeStamp > data.mouseoutTimeStamp && this.contains(data.mouseoverEl)) {
            console.log("[hoverfu.hide:transitionend] faking mouseout");
            data.$a.trigger($.Event("mouseout", {relatedTarget: document}));
          }
        }
      });
    } else if (!isFadingOut(data)) {
      delete data.$h;
    }
  }
  function onMouseOver(create, e) {  // $a or $h
    if (e.relatedTarget && this.contains(e.relatedTarget)) return;
    var data = getData(this);
    data.mouseoverTimeStamp = e.timeStamp || Date.now();
    data.mouseoverEl = e.target;
    if (!data.$h) {
      createHover(data, create);
    } else if (data.showing) {
      clearTimeout(data.hide), delete data.hide;
      if (data.opts.canLeaveFor) {
        $(document).off("mousemove.hoverfu");
      }
      if (data.opts.hideAfter && this === data.$a[0]) {
        data.hide = setTimeout(hide.bind(this), data.opts.hideAfter);
      }
    }
  }
  function onMouseOut(e) {  // $a or $h
    if (e.relatedTarget && this.contains(e.relatedTarget)) return;
    var data = getData(this), a = data.$a[0], edge;
    clearTimeout(data.show), delete data.show;
    data.mouseoutTimeStamp = e.timeStamp || Date.now();
    if (data.showing) {
      if (data.opts.hideAfter) {
        clearTimeout(data.hide), delete data.hide;
      }
      if (data.opts.canLeaveFor && (edge = between(this, this === a ? data.$h[0] : a, e.clientX, e.clientY))) {
        data.hide = setTimeout(hide.bind(a), data.opts.canLeaveFor);
        data.x = e.clientX;
        data.y = e.clientY;
        $(document).on("mousemove.hoverfu", onMouseMoveMaybeHide.bind(a, edge, data));
      } else {
        hide.call(a);
      }
    } else if (!isFadingOut(data)) {
      delete data.$h;
    }
  }
  function onMouseMoveMaybeHide(edge, data, e) {  // $a (but bound to doc)
    if (toward(edge, data.x, data.y, e.clientX, e.clientY)) {
      data.x = e.clientX;
      data.y = e.clientY;
    } else if (data.x !== e.clientX && data.y !== e.clientY) {
      $(document).off("mousemove.hoverfu");
      hide.call(this);
    }
  }

  function isFadingOut(data) {  // recovers within .5 sec if property not cleared when done fading
    return Date.now() - (data.fadeOutStartTime || 0) < 500;
  }

  // Checks whether the viewport coords (x, y) are in a horizontal (preferred) or vertical gutter
  // between two non-overlapping elements. If so, returns the coordinates of the nearest edge of toEl
  // (order chosen for positive cross product from origin).
  function between(fromEl, toEl, x, y) {
    var f = fromEl.getBoundingClientRect(), t = toEl.getBoundingClientRect();
    return f.bottom < t.top && y >= f.bottom && y <= t.top ? {x1: t.left, x2: t.right, y1: t.top, y2: t.top} :       // ↓
           t.bottom < f.top && y >= t.bottom && y <= f.top ? {x1: t.right, x2: t.left, y1: t.bottom, y2: t.bottom} : // ↑
           f.right < t.left && x >= f.right && x <= t.left ? {x1: t.left, x2: t.left, y1: t.bottom, y2: t.top} :     // →
           t.right < f.left && x >= t.right && x <= f.left ? {x1: t.right, x2: t.right, y1: t.top, y2: t.bottom} :   // ←
          null;
  }

  // Returns whether a movement from (x1, y1) to (x2, y2) is "toward" an edge (line segment).
  // Edge points must be defined such that |A x B| > 0, where A is the vector from (x1, x2)
  // to (edge.x1, edge.y1) and B is the vector from (x1, y1) to (edge.x1, edge.y2).
  function toward(edge, x1, y1, x2, y2) {
    var Nx = x2 - x1, Ny = y2 - y1;
    // return |A x N| > 0 && |N x B| > 0
    return (edge.y1 - y1) * Nx > (edge.x1 - x1) * Ny &&
           (edge.x2 - x1) * Ny > (edge.y2 - y1) * Nx;
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

  function getData(el) {
    var o = $.data(el);
    return o.hoverfu || (o.hoverfu = {$a: $(el)});
  }
}(jQuery);
