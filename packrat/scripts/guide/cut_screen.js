// @require scripts/lib/underscore.js

var CutScreen = CutScreen || (function (window, document) {
  'use strict';

  function CutScreen(holes) {
    this.holes = holes.map(copyHole);
    this.holesDrawn = [];
    var wd = this.wd = {w: window.innerWidth, h: window.innerHeight};
    var el = this.el = document.createElement('canvas');
    el.className = 'kifi-guide-screen kifi-root';
    var gc = this.gc = el.getContext('2d');
    gc.fillStyle = '#000';
    var scale = this.scale = (window.devicePixelRatio || 1) /  // html5rocks.com/en/tutorials/canvas/hidpi/
      (gc.webkitBackingStorePixelRatio ||
       gc.mozBackingStorePixelRatio ||
       gc.backingStorePixelRatio || 1);
    size.call(this);
    draw.call(this);
    this.onWinResize = _.throttle(onWinResize.bind(null, this), 100, {leading: false});
    this.attach();
  }

  CutScreen.prototype = {
    attached: false,
    attach: function () {
      if (!this.attached) {
        // TODO: call this.onWinResize if window has resized while detached
        document.body.appendChild(this.el);
        window.addEventListener('resize', this.onWinResize, true);
        this.attached = true;
      }
      return this;
    },
    detach: function () {
      if (this.attached) {
        this.el.remove();
        window.removeEventListener('resize', this.onWinResize, true);
        this.attached = false;
      }
      return this;
    },
    fadeAndDetach: function (ms) {
      if (this.attached) {
        var self = this;
        this.el.addEventListener('transitionend', function end() {
          this.removeEventListener('transitionend', end);
          self.detach();
        });
        this.el.style.transition = 'opacity ' + ms + 'ms linear';
        this.el.style.opacity = 0;
      }
      return this;
    },
    cut: function (hole, ms) {
      var i = this.holes.length;
      this.holes.push(copyHole(hole));
      if (ms > 0) {
        var ms_1 = 1 / ms;
        var t0 = window.performance.now();
        var tN = t0 + ms;
        var tick = (function (t) {
          if (t < tN) {
            window.requestAnimationFrame(tick);
            var alpha = t > t0 ? (t - t0) * ms_1 : 0;
            drawHole.call(this, i, 1 - alpha);
          } else {
            drawHole.call(this, i, 0);
          }
        }).bind(this);
        window.requestAnimationFrame(tick);
        return drawHole.call(this, i, 1);
      } else {
        return drawHole.call(this, i, 0);
      }
    },
    fill: function (ms) {
      var ms_1 = 1 / ms;
      var o0 = this.holesDrawn.map(function (h) {return h.opacity});
      var t0 = window.performance.now();
      var tN = t0 + ms;
      var tick = (function (t) {
        if (t < tN) {
          window.requestAnimationFrame(tick);
          var alpha = t > t0 ? (t - t0) * ms_1 : 0;
          for (var i = 0; i < o0.length; i++) {
            drawHole.call(this, i, o0[i] + (1 - o0[i]) * alpha);
          }
        } else {
          for (var i = 0; i < o0.length; i++) {
            drawHole.call(this, i, 1);
          }
        }
      }).bind(this);
      window.requestAnimationFrame(tick);
    }
  };

  function onWinResize(screen) {
    var w = window.innerWidth, h = window.innerHeight;
    if (w !== screen.wd.w || h !== screen.wd.h) {
      screen.wd.w = w;
      screen.wd.h = h;
      size.call(screen);
      draw.call(screen);
    }
  }

  function size() {
    var el = this.el;
    var gc = this.gc;
    var w = this.wd.w;
    var h = this.wd.h;
    var scale = this.scale;
    el.width = w * scale;
    el.height = h * scale;
    if (scale !== 1) {
      el.style.width = w + 'px';
      el.style.height = h + 'px';
      gc.scale(scale, scale);
    }
    gc.fillRect(0, 0, w, h);
    this.holesDrawn.forEach(function (h) { h.cleared = true; });
  }

  function draw() {
    for (var i = 0; i < this.holes.length; i++) {
      drawHole.call(this, i);
    }
  }

  function drawHole(i, opacity) {
    var gc = this.gc;
    var hole = this.holes[i];
    var holeDrawn = this.holesDrawn[i];
    if (opacity == null) {
      opacity = holeDrawn && holeDrawn.opacity || 0;
    }
    var els = document.querySelectorAll(hole.sel);
    var rect = bounds(Array.prototype.slice.call(els).map(getBoundingClientRect));
    var pad = hole.pad;
    var padT = pad[0];
    var padR = pad.length > 1 ? pad[1] : padT;
    var padB = pad.length > 2 ? pad[2] : padT;
    var padL = pad.length > 3 ? pad[3] : padR;
    var holeSpec = rectOnPxGrid({
      x: rect.x - padL,
      y: rect.y - padT,
      w: Math.max(hole.minWidth || 0, Math.min(hole.maxWidth || 1e9, rect.w + padL + padR)),
      h: Math.max(hole.minHeight || 0, Math.min(hole.maxHeight || 1e9, rect.h + padT + padB))
    });
    hole.opacity = holeSpec.opacity = opacity;
    var r = 6;
    if (!holeDrawn || holeNeedsRedraw(holeDrawn, holeSpec)) {
      if (holeDrawn && !holeDrawn.cleared) {
        gc.fillRect(holeDrawn.x - 1, holeDrawn.y - 1, holeDrawn.w + 2, holeDrawn.h + 2);
      }
      var x1 = holeSpec.x;
      var y1 = holeSpec.y;
      var x2 = x1 + holeSpec.w;
      var y2 = y1 + holeSpec.h;;
      gc.beginPath();
      gc.moveTo(x1 + r, y1);
      gc.arcTo(x2, y1, x2, y2, r);
      gc.arcTo(x2, y2, x1, y2, r);
      gc.arcTo(x1, y2, x1, y1, r);
      gc.arcTo(x1, y1, x2, y1, r);
      gc.closePath();
      gc.globalCompositeOperation = 'destination-out';
      gc.globalAlpha = 1 - opacity;
      gc.fill();
      gc.globalCompositeOperation = 'source-over';
      gc.globalAlpha = 1;
      this.holesDrawn[i] = holeSpec;
    }
    return holeSpec;
  }

  function getBoundingClientRect(el) {
    return el.getBoundingClientRect();
  }

  function bounds(rects) {
    var x = Math.min.apply(Math, rects.map(function (r) {return r.left}));
    var y = Math.min.apply(Math, rects.map(function (r) {return r.top}));
    return {
      x: x,
      y: y,
      w: Math.max.apply(Math, rects.map(function (r) {return r.right - x})),
      h: Math.max.apply(Math, rects.map(function (r) {return r.bottom - y}))
    };
  }

  function rectOnPxGrid(r) {
    return {
      x: Math.round(r.x),
      y: Math.round(r.y),
      w: Math.round(r.w),
      h: Math.round(r.h)
    };
  }

  function holeNeedsRedraw(holeDrawn, holeSpec) {
    return (
      holeDrawn.x !== holeSpec.x ||
      holeDrawn.y !== holeSpec.y ||
      holeDrawn.w !== holeSpec.w ||
      holeDrawn.h !== holeSpec.h ||
      holeDrawn.opacity !== holeSpec.opacity ||
      holeDrawn.cleared);
  }

  function copyHole(h) {
    return {
      sel: h.sel,
      pad: h.pad,
      maxWidth: h.maxWidth,
      maxHeight: h.maxHeight,
      opacity: h.opacity || 0
    };
  }

  return CutScreen;
}(window, document));
