// @require scripts/lib/underscore.js

var CutScreen = CutScreen || (function (window, document) {
  'use strict';

  function CutScreen(holes) {
    this.holes = holes.slice();
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
    cut: function (hole) {
      this.holes.push(hole);
      return drawHole.call(this, this.holes.length - 1);
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
    this.holesDrawn.length = 0;
  }

  function draw() {
    for (var i = 0; i < this.holes.length; i++) {
      drawHole.call(this, i);
    }
  }

  function drawHole(i) {
    var gc = this.gc;
    var hole = this.holes[i];
    var holeDrawn = this.holesDrawn[i];
    var els = document.querySelectorAll(hole.sel);
    var rect = bounds(Array.prototype.slice.call(els).map(getBoundingClientRect));
    var pad = hole.pad;
    var padT = pad[0];
    var padR = pad.length > 1 ? pad[1] : padT;
    var padB = pad.length > 2 ? pad[2] : padT;
    var padL = pad.length > 3 ? pad[3] : padR;
    var holeRect = rectOnPxGrid({
      x: rect.x - padL,
      y: rect.y - padT,
      w: Math.max(hole.minWidth || 0, Math.min(hole.maxWidth || 1e9, rect.w + padL + padR)),
      h: Math.max(hole.minHeight || 0, Math.min(hole.maxHeight || 1e9, rect.h + padT + padB))
    });
    var r = 6;
    if (!holeDrawn || !sameRect(holeDrawn, holeRect)) {
      if (holeDrawn) {
        gc.fillRect(holeDrawn.x - 1, holeDrawn.y - 1, holeDrawn.w + 2, holeDrawn.h + 2);
      }
      var x1 = holeRect.x;
      var y1 = holeRect.y;
      var x2 = x1 + holeRect.w;
      var y2 = y1 + holeRect.h;;
      gc.globalCompositeOperation = 'destination-out';
      gc.beginPath();
      gc.moveTo(x1 + r, y1);
      gc.arcTo(x2, y1, x2, y2, r);
      gc.arcTo(x2, y2, x1, y2, r);
      gc.arcTo(x1, y2, x1, y1, r);
      gc.arcTo(x1, y1, x2, y1, r);
      gc.closePath();
      gc.fill();
      gc.globalCompositeOperation = 'source-over';
      this.holesDrawn[i] = holeRect;
    }
    return holeRect;
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
  };

  return CutScreen;
}(window, document));
