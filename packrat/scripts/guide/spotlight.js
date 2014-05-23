// @require styles/guide/spotlight.css

var Spotlight = Spotlight || (function (window, document) {
  'use strict';

  function Spotlight(rectToCircumscribe, opts) {
    var ri = this.ri = rectToCircumscribe;
    var ro = this.ro = opts.outer || {x: 0, y: 0, w: 9000, h: 9000};
    var el = this.el = [0,0,0,0].map(div.bind(null, 'kifi-spotlight kifi-root'));
    if ('opacity' in opts) {
      setOpacity(el, this.opacity = sanitizeOpacity(opts.opacity));
    }
    position(el, ro, ri);
    this.attach();
  }

  Spotlight.prototype = {
    attached: false,
    attach: function () {
      if (!this.attached) {
        var f = document.createDocumentFragment();
        this.el.forEach(f.appendChild.bind(f));
        document.body.appendChild(f);
        this.attached = true;
      }
      return this;
    },
    detach: function () {
      if (this.attached) {
        this.el.forEach(function (el) {el.remove()});
        this.attached = false;
      }
      return this;
    },
    animateTo: function (rectToCircumscribe, opts) {
      var o0 = this.opacity;
      var oN = 'opacity' in opts ? sanitizeOpacity(opts.opacity) : null;
      var ease = opts.ease || swing;
      var r0 = this.ri;
      var rN = rectToCircumscribe;
      var ms = calcDurationMs(r0, rN);
      var ms_1 = 1 / ms;
      var t0 = window.performance.now();
      var tN = t0 + ms;
      var tick = this.tick = (function (t) {
        if (this.tick === tick) {
          if (t < tN) {
            window.requestAnimationFrame(tick);
            var alpha = t > t0 ? (t - t0) * ms_1 : 0;
            position(this.el, this.ro, this.ri = interpolateRect(ease(alpha), r0, rN));
            if (oN != null) {
              setOpacity(this.el, this.opacity = o0 + (oN - o0) * alpha);
            }
          } else {
            this.tick = null;
            if (opts.detach) {
              this.detach();
            } else {
              position(this.el, this.ro, this.ri = rN);
            }
            if (oN != null) {
              setOpacity(this.el, this.opacity = oN);
            }
          }
        }
      }).bind(this);
      window.requestAnimationFrame(tick);
      return ms;
    }
  };

  function div(className) {
    var el = document.createElement('div');
    el.className = className;
    return el;
  }

  function position(el, ro, ri) {
    var r = Math.round(Math.sqrt(ri.w * ri.w + ri.h * ri.h) / 2);
    var cx = Math.round(ri.x + ri.w / 2);
    var cy = Math.round(ri.y + ri.h / 2);
    positionOne(el[0], r, cx, cy, ro.x, ro.y, ro.w, ri.y - ro.y);
    positionOne(el[1], r, cx, cy, ro.x, ri.y, ri.x - ro.x, ri.h);
    positionOne(el[2], r, cx, cy, ri.x + ri.w, ri.y, ro.x + ro.w - ri.x - ri.w, ri.h);
    positionOne(el[3], r, cx, cy, ro.x, ri.y + ri.h, ro.w, ro.y + ro.h - ri.y - ri.h);
  }

  function positionOne(el, r, cx, cy, x, y, w, h) {
    var s = el.style;
    if (w > 0 && h > 0) {
      s.display = '';
      s.left = Math.round(x) + 'px';
      s.top = Math.round(y) + 'px';
      s.width = Math.round(w) + 'px';
      s.height = Math.round(h) + 'px';
      s.backgroundImage = 'radial-gradient(circle ' + (r + 9) + 'px at ' + (cx - x) + 'px ' + (cy - y) + 'px, rgba(0,0,0,0) ' + r + 'px, rgba(0,0,0,.85) ' + (r + 4) + 'px)';
    } else {
      s.display = 'none';
    }
  }

  function sanitizeOpacity(val) {
    return typeof val === 'number' ? (val < 0 ? 0 : (val > 1 ? 1 : val)) : 1;
  }

  function setOpacity(el, val) {
    for (var i = 0; i < el.length; i++) {
      el[i].style.opacity = val;
    }
  }

  function calcDurationMs(r1, r2) {
    var px = Math.max.apply(null, [r1.x - r2.x, r1.y - r2.y, r1.x + r1.w - r2.x - r2.w, r1.y + r1.h - r2.y - r2.h].map(Math.abs));
    return 200 * Math.log((px + 80) / 60) | 0;
  }

  function interpolateRect(alpha, r1, r2) {
    return {
      x: r1.x + (r2.x - r1.x) * alpha,
      y: r1.y + (r2.y - r1.y) * alpha,
      w: r1.w + (r2.w - r1.w) * alpha,
      h: r1.h + (r2.h - r1.h) * alpha
    };
  }

  function swing(p) {
    return .5 - Math.cos(p * Math.PI) / 2;
  }

  return Spotlight;
}(window, document));
