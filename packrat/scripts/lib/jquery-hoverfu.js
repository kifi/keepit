// jquery-hoverfu.js
// home-grown at FortyTwo, not for distribution

// Invoke on a link or other element that should trigger a hover element to display
// on mouseover (after a short delay) and to disappear on mouseout (after a short delay).

// Features:
//  - Allows the hover element to be built and configured asynchronously.
//  - Optionally waits for some period of time before showing the hover element.
//  - Optionally auto-hides the hover element after some period of time.
//  - Optionally toggles or hides the hover element when trigger element is clicked
//    with a brief refractory period during which subsequent clicks are ignored.
//  - Optionally avoids hiding the hover element if the mouse leaves the
//    trigger element to go to the hover element (see the between function).
//  - Optionally avoids hiding the hover element if the mouse leaves it
//    and then returns within some period of time.
//  - Optionally allows caller to position the hover relative to another element
//    using the jQuery UI position API (http://api.jqueryui.com/position/).
//  - Optional configuration via data- attributes.

// TODO:
//  - Keep a global stack of hover elements currently showing. When it’s time to
//    show a new hover element, pop and hide every element on the stack first if
//    the new one’s trigger element is not a descendant of the hover element at
//    the top of the stack.
//  - Introduce a custom event to make the API a bit cleaner and to allow
//    auto-removal of an element's hover when the element is removed.
//    http://benalman.com/news/2010/03/jquery-special-events/

!function ($, doc) {
  'use strict';

  $.fn.hoverfu = function (m) {
    if ((m = methods[m])) {
      return m.apply(this, Array.prototype.slice.call(arguments, 1));
    } else {
      return methods.init.apply(this, arguments);
    }
  };

  var methods = {
    init: function (selector, create) {
      if (!create) create = selector, selector = null;
      if (typeof create != "function") create = createFromDataAttr(create);
      return this
      .on("mouseover.hoverfu", selector, $.proxy(onMouseOver, null, create))
      .on("mouseout.hoverfu", selector, onMouseOut)
      .on("mousedown.hoverfu", selector, function (e) {
        if (e.which !== 1) return;
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
      })
      .on("hoverfu:show.hoverfu", selector, function () {
        var data = getData(this);
        if (data.$h) {
          show.call(this);
        } else {
          createHover(data, create, true);
        }
      });
    },
    show: function () {
      return this.trigger("hoverfu:show");
    },
    hide: function () {
      return this.each(hide);
    },
    destroy: function () {
      return this.each(function () {
        var data = $.data(this);
        var o = data.hoverfu;
        if (o) {
          delete data.hoverfu;
          clearTimeout(o.show || o.hide);
          if (o.$a) {
            o.$a.off('.hoverfu');
            if (o.$h) {
              o.$h.remove();
            }
          }
        }
      });
    }};

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
  function attach($h, opts) {
    if (opts.position) {
      $h.css({visibility: 'hidden', display: 'block'});
    }
    if (opts.insertBefore) {
      $h.insertBefore(opts.insertBefore);
    } else {
      $h.appendTo(opts.parent || (doc.body.tagName.toLowerCase() === 'body' ? doc.body : doc.documentElement));
    }
    if (opts.position) {
      $h.position(opts.position).css({visibility: '', display: ''});
    }
  }
  function show() {
    var data = getData(this);
    clearTimeout(data.show);
    delete data.show;
    var $h = data.$h;
    if (!$h || data.showing || isFadingOut(data) || data.opts.suppressed && data.opts.suppressed()) return;
    attach($h, data.opts);
    $h[0].offsetHeight;  // force layout
    $h.addClass('kifi-showing');
    data.showTime = Date.now();
    data.showing = true;
    if (data.opts.hideAfter) {
      data.hide = setTimeout(hide.bind(this), data.opts.hideAfter);
    }
    $(doc).on('mousewheel.hoverfu', hide.bind(this));
  }
  function hide() {
    var data = getData(this);
    clearTimeout(data.show || data.hide);
    delete data.show, delete data.hide;
    $(doc).off('mousewheel.hoverfu mousemove.hoverfu');
    if (data.showing) {
      data.showing = false;
      data.fadeOutStartTime = Date.now();
      data.$h.removeClass("kifi-showing").on("transitionend", function (e) {
        if (e.target === this && e.originalEvent.propertyName === "opacity") {
          delete data.fadeOutStartTime;
          data.$h.remove();
          delete data.$h;
          if (data.mouseoverTimeStamp > data.mouseoutTimeStamp && this.contains(data.mouseoverEl)) {
            console.log("[hoverfu.hide:transitionend] faking mouseout");
            data.$a.trigger($.Event("mouseout", {relatedTarget: doc, originalEvent: {}, hoverfu: 'fake'}));
          }
        }
      });
    } else if (data.$h && !isFadingOut(data)) {
      data.$h.remove();
      delete data.$h;
    }
  }
  function onMouseOver(create, e) {  // $a or $h
    var data = getData(this), a = data.$a[0], h = (data.$h || [])[0], rT = e.relatedTarget;
    if (rT && (a.contains(rT) || h && data.opts.canLeaveFor && h.contains(rT)) || e.originalEvent.isTrusted === false) return;
    if (e.originalEvent.hoverfu === data) return;  // e.g. mouseover $h from containing $a propagated up to $a
    e.originalEvent.hoverfu = data;
    data.mouseoverTimeStamp = e.timeStamp || Date.now();
    data.mouseoverEl = e.target;
    if (!h) {
      createHover(data, create);
    } else if (data.showing) {
      clearTimeout(data.hide), delete data.hide;
      if (data.opts.canLeaveFor) {
        $(doc).off("mousemove.hoverfu");
      }
      if (data.opts.hideAfter && this === a) {
        data.hide = setTimeout(hide.bind(this), data.opts.hideAfter);
      }
    }
  }
  function onMouseOut(e) {  // $a or $h
    var data = getData(this), a = data.$a[0], h = (data.$h || [])[0], rT = e.relatedTarget, edge;
    if (rT && (a.contains(rT) || h && data.opts.canLeaveFor && h.contains(rT)) || e.originalEvent.isTrusted === false) return;
    clearTimeout(data.show), delete data.show;
    data.mouseoutTimeStamp = e.timeStamp || Date.now();
    if (data.showing) {
      if (data.opts.hideAfter) {
        clearTimeout(data.hide), delete data.hide;
      }
      if (data.opts.canLeaveFor && (edge = between(this, this === a ? h : a, e.clientX, e.clientY))) {
        data.hide = setTimeout(hide.bind(a), data.opts.canLeaveFor);
        data.x = e.clientX;
        data.y = e.clientY;
        $(doc).on("mousemove.hoverfu", onMouseMoveMaybeHide.bind(a, edge, data));
      } else {
        hide.call(a);
      }
    } else if (h && !isFadingOut(data)) {
      data.$h.remove();
      delete data.$h;
    }
  }
  function onMouseMoveMaybeHide(edge, data, e) {  // $a (but bound to doc)
    if (toward(edge, data.x, data.y, e.clientX, e.clientY)) {
      data.x = e.clientX;
      data.y = e.clientY;
    } else if (data.x !== e.clientX && data.y !== e.clientY) {
      $(doc).off("mousemove.hoverfu");
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
    return function (configureHover) {
      configureHover($("<div>", {"class": this.dataset.tipClass, "html": this.dataset.tipHtml}), opts);
    };
  }

  function getData(el) {
    var o = $.data(el);
    return o.hoverfu || (o.hoverfu = {$a: $(el)});
  }
}(jQuery, document);
