// @require scripts/lib/underscore.js

var Spotlight = Spotlight || (function (window, document) {
  'use strict';

  function Spotlight(rectToCircumscribe, opts) {
    var ri = this.ri = rectOnPxGrid(rectToCircumscribe);
    var wd = this.wd = {w: window.innerWidth, h: window.innerHeight};
    var dc = this.dc = [0,0,0,0].map(newDarkCanvas.bind(null, wd.w, wd.h, 'kifi-spotlight kifi-root'));
    this.onResize = _.throttle(onResize.bind(null, this), 100, {leading: false});
    this.maxOpacity = clamp01(opts.maxOpacity);
    this.brightness = clamp01(opts.brightness);
    show(dc, wd, ri, Math.min(this.maxOpacity, clamp01(opts.opacity)), this.brightness);
  }

  Spotlight.prototype = {
    attached: false,
    attach: function (attach) {
      if (!this.attached) {
        // TODO: call this.onResize if window has resized while detached
        var f = document.createDocumentFragment();
        this.dc.forEach(function (dc) {
          f.appendChild(dc.el);
        });
        if (attach) {
          attach(f)
        } else {
          document.body.appendChild(f);
        }
        window.addEventListener('resize', this.onResize, true);
        this.attached = true;
      }
      return this;
    },
    detach: function () {
      if (this.attached) {
        this.dc.forEach(function (dc) {dc.el.remove()});
        window.removeEventListener('resize', this.onResize, true);
        this.attached = false;
      }
      return this;
    },
    animateTo: function (rectToCircumscribe, opts) {
      var b0 = this.brightness;
      var bN = opts.brightness == null ? b0 : clamp01(opts.brightness);
      var o0 = this.dc[0].styleCache.opacity;
      var oN = Math.min(this.maxOpacity, clamp01(opts.opacity));
      var ease = opts.ease || swing;
      var r0 = this.ri;
      var rN = rectOnPxGrid(rectToCircumscribe || centeredInWindow(this.ri, this.wd));
      var ms = opts.ms || calcDurationMs(r0, rN);
      var ms_1 = 1 / ms;
      var t0 = window.performance.now();
      var tN = t0 + ms;
      var tick = this.tick = (function (t) {
        if (this.tick === tick) {
          if (t < tN) {
            window.requestAnimationFrame(tick);
            var alpha = t > t0 ? (t - t0) * ms_1 : 0;
            var easedAlpha = ease(alpha);
            show(this.dc, this.wd, this.ri = interpolateRect(easedAlpha, r0, rN), o0 + (oN - o0) * alpha, this.brightness = b0 + (bN - b0) * easedAlpha);
          } else {
            this.tick = null;
            if (opts.detach) {
              this.detach();
              this.dc.forEach(function (dc) {
                dc.setOpacity(oN);
              });
            } else {
              show(this.dc, this.wd, this.ri = rN, oN, this.brightness = bN);
            }
          }
        }
      }).bind(this);
      window.requestAnimationFrame(tick);
      return ms;
    }
  };

  function newDarkCanvas(w, h, className) {
    return new DarkCanvas(w, h, className);
  }

  function DarkCanvas(w, h, className) {
    var el = document.createElement('canvas');
    el.className = className;
    var gc = el.getContext('2d');
    gc.fillStyle = '#000';
    this.el = el;
    this.gc = gc;
    this.scale = (window.devicePixelRatio || 1) /  // html5rocks.com/en/tutorials/canvas/hidpi/
      (gc.webkitBackingStorePixelRatio ||
       gc.mozBackingStorePixelRatio ||
       gc.backingStorePixelRatio || 1);
    this.size(w, h);
    this.x = 0;
    this.y = 0;
    this.styleCache = {
      display: '',
      opacity: 1
    };
  }

  DarkCanvas.prototype = {
    size: function(w, h) {
      var scale = this.scale;
      this.el.width = w * scale;
      this.el.height = h * scale;
      if (scale !== 1) {
        this.el.style.width = w + 'px';
        this.el.style.height = h + 'px';
        this.gc.scale(scale, scale);
      }
      this.gc.fillRect(0, 0, w, h);
      this.spotBounds = {x: 0, y: 0, w: 0, h: 0};

      this.clipHeight = null;
    },
    draw: function(wd, r, cx, cy, x, y, opacity, brightness, clipHeight) {
      if (x + wd.w > 0 && y + (clipHeight || wd.h) > 0 && x < wd.w && y < wd.h) {
        var cs = this.styleCache;
        var es = this.el.style;
        var xOld = cs.left;
        var yOld = cs.top;
        updateStylePx(es, cs, 'left', x);
        updateStylePx(es, cs, 'top', y);
        updateStyle(es, cs, 'display', '');
        if (opacity != null) {
          updateStyle(es, cs, 'opacity', opacity);
        }
        var gc = this.gc;
        var sB = this.spotBounds;
        var fillHeight = Math.min(clipHeight || wd.h, sB.y + sB.h) - sB.y;
        if (fillHeight > 0) {
          gc.fillRect(sB.x, sB.y, sB.w, fillHeight);
        }
        var cH = this.clipHeight;
        if (clipHeight != null && (clipHeight < cH || cH == null)) {
          gc.clearRect(0, clipHeight, this.el.width, (cH || this.el.height) - clipHeight);
        } else if (cH != null && (clipHeight == null || clipHeight > cH)) {
          gc.fillRect(0, cH, this.el.width, (clipHeight || this.el.height) - cH);
        }
        this.clipHeight = clipHeight;
        sB.x = cx - x - r - 2,
        sB.y = cy - y - r - 2,
        sB.w = 2 * r + 4,
        sB.h = 2 * r + 4;
        gc.globalCompositeOperation = 'destination-out';
        gc.globalAlpha = brightness;
        gc.beginPath();
        gc.arc(cx - x, cy - y, r, 0, 2 * Math.PI);
        gc.closePath();
        gc.fill();
        gc.globalCompositeOperation = 'source-over';
        gc.globalAlpha = 1;
      } else {
        this.hide();
      }
    },
    setOpacity: function (opacity) {
      updateStyle(this.el.style, this.styleCache, 'opacity', opacity);
    },
    hide: function () {
      updateStyle(this.el.style, this.styleCache, 'display', 'none');
    }
  };

  function show(dc, wd, ri, opacity, brightness) {
    var r = Math.round(Math.sqrt(ri.w * ri.w + ri.h * ri.h) / 2);
    var cx = Math.round(ri.x + ri.w / 2);
    var cy = Math.round(ri.y + ri.h / 2);
    if (r > 0 && brightness === 1) {
      dc[0].draw(wd, r, cx, cy, 0, ri.y - wd.h, opacity, brightness);
      dc[1].draw(wd, r, cx, cy, ri.x - wd.w, ri.y, opacity, brightness, ri.h);
      dc[2].draw(wd, r, cx, cy, ri.x + ri.w, ri.y, opacity, brightness, ri.h);
      dc[3].draw(wd, r, cx, cy, 0, ri.y + ri.h, opacity, brightness);
    } else {
      dc[0].draw(wd, r, cx, cy, 0, 0, opacity, brightness);
      dc[1].hide();
      dc[2].hide();
      dc[3].hide();
    }
  }

  function onResize(spotlight) {
    var wd = {w: window.innerWidth, h: window.innerHeight};
    spotlight.dc.forEach(function (dc) {
      dc.size(wd.w, wd.h);
    });
    show(spotlight.dc, spotlight.wd = wd, spotlight.ri, null, spotlight.brightness);
  }

  function updateStyle(elementStyle, styleCache, name, val) {
    if (styleCache[name] !== val) {
      elementStyle[name] = styleCache[name] = val;
    }
  }

  function updateStylePx(elementStyle, styleCache, name, val) {
    if (styleCache[name] !== val) {
      elementStyle[name] = styleCache[name] = val + 'px';
    }
  }

  function clamp01(val) {
    return typeof val === 'number' ? (val < 0 ? 0 : (val > 1 ? 1 : val)) : 1;
  }

  function calcDurationMs(r1, r2) {
    var px = Math.max.apply(null, [r1.x - r2.x, r1.y - r2.y, r1.x + r1.w - r2.x - r2.w, r1.y + r1.h - r2.y - r2.h].map(Math.abs));
    return 200 * Math.log((px + 80) / 60) | 0;
  }

  function interpolateRect(alpha, r1, r2) {
    return rectOnPxGrid({
      x: r1.x + (r2.x - r1.x) * alpha,
      y: r1.y + (r2.y - r1.y) * alpha,
      w: r1.w + (r2.w - r1.w) * alpha,
      h: r1.h + (r2.h - r1.h) * alpha
    });
  }

  function centeredInWindow(r, wd) {
    var w = Math.min(200, r.w);
    var h = Math.min(200, r.h);
    return {
      x: (wd.w - w) / 2,
      y: (wd.h - h) / 2,
      w: w,
      h: h
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

  function swing(p) {
    return .5 - Math.cos(p * Math.PI) / 2;
  }

  return Spotlight;
}(window, document));
