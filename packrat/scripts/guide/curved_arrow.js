// @require scripts/lib/jquery.js
// @require scripts/lib/underscore.js

var CurvedArrow = CurvedArrow || (function (window, document) {
  'use strict';

  function CurvedArrow(tail, head, pos, parent) {
    this.$el = $('<div class="kifi-curved-arrow kifi-root"/>').css(pos).hide();
    this.$head = head.draw === false ? $() : $('<div class="kifi-curved-arrow-head"/>').appendTo(this.$el);
    this.$tail = $('<div class="kifi-curved-arrow-tail"/>').appendTo(this.$el);
    this.attach(parent);
    var headLength = parseFloat(this.$head.css('border-left-width') || 0);
    var tailWidth = parseFloat(this.$tail.css('width') || 0) / 2;
    var curve = computeCurve(tail, head, headLength);
    var x0 = curve.x(0);
    var y0 = curve.y(0);
    this.$head.css('transform', headTransform(x0, y0, curve.phi(0)));
    this.$tail.css('transform', translatePx(x0, y0));
    this.reveal = reveal.bind(this, curve, tail.spacing || tailWidth * 4.5);
  }

  CurvedArrow.prototype = {
    attached: false,
    attach: function (parent) {
      if (!this.attached) {
        this.$el.appendTo(parent || 'body');
        this.attached = true;
      }
      return this;
    },
    detach: function () {
      if (this.attached) {
        this.$el.remove();
        this.attached = false;
      }
      return this;
    },
    fadeAndDetach: function (duration) {
      if (this.attached) {
        this.$el.on('transitionend', this.detach.bind(this)).css({
          transition: 'opacity ' + (typeof duration === 'number' ? duration + 'ms' : duration) + ' linear',
          opacity: 0
        });
      }
      return this;
    }
  };

  function computeCurve(T, H, headLength) {
    /*
        o o o o
        T        o o o         <- dotted cubic Bézier curve
                       o
                    ---•---    <- arrowhead axis is tangent to arc where they meet at G
                     \ G /
                      \ /      <- orientation of head and tail are configurable
                       • H
    */
    var tailAngleRad = Math.PI / 180 * T.angle;
    var headAngleRad = Math.PI / 180 * H.angle;
    var Gx = H.x - headLength * Math.cos(headAngleRad);
    var Gy = H.y + headLength * Math.sin(headAngleRad);
    return chooseCurve(T, -tailAngleRad, {x: Gx, y: Gy}, -headAngleRad + Math.PI);
  }

  function reveal(curve, minDotSpacing, ms) {
    this.$el.css('display', '');
    var path = $svg('path').attr('d', curve.pathData()).el;
    var totalLen = path.getTotalLength();
    var totalNumDots = Math.max(totalLen ? this.$head ? 1 : 2 : 0, Math.floor(totalLen / minDotSpacing));
    var dotSpacing = (totalLen - (this.$head ? .8 * minDotSpacing : 0)) / (totalNumDots - 1);
    var ease = swing;
    var ms_1 = 1 / ms;
    var t0 = window.performance.now();
    var tN = t0 + ms;
    var tick = this.tick = (function (t) {
      if (this.tick === tick) {
        var alpha = t < tN ? t > t0 ? ease((t - t0) * ms_1) : 0 : 1;
        var len = alpha * totalLen;
        if (dotSpacing) {
          for (var lenNextDot = dotSpacing * this.$tail.length; lenNextDot <= len; lenNextDot = dotSpacing * this.$tail.length) {
            var P = path.getPointAtLength(lenNextDot);
            var $dot = $(this.$tail[0].cloneNode())
              .css('transform', translatePx(P.x, P.y))
              .appendTo(this.$el);
            this.$tail = this.$tail.add($dot);
          }
        }
        if (this.$head) {
          var P = path.getPointAtLength(len);
          this.$head.css('transform', headTransform(P.x, P.y, curve.phi(curve.t(P.x, P.y))));
        }
        if (t < tN) {
          window.requestAnimationFrame(tick);
        } else {
          this.tick = null;
        }
      }
    }).bind(this);
    window.requestAnimationFrame(tick);
  }

  function $svg(tagName) {
    if (this) {
      this.el = document.createElementNS('http://www.w3.org/2000/svg', tagName);
    } else {
      return new $svg(tagName);
    }
  }

  $svg.prototype = {
    attr: function (name, val) {
      // TODO: cache attr values to avoid unnecessary read and writes
      if (val === undefined) {
        return this.el.getAttributeNS(null, name);
      } else {
        this.el.setAttributeNS(null, name, val);
        return this;
      }
    }
  };

  function BezierCurve(A, B, C, D) {
    this.A = A;
    this.B = B;
    this.C = C;
    this.D = D;

    // calculate the inversion equation, from cagd.cs.byu.edu/~557/text/ch17.pdf
    var x0 = A.x, x1 = B.x, x2 = C.x, x3 = D.x;
    var y0 = A.y, y1 = B.y, y2 = C.y, y3 = D.y;
    var cd = (x1 * y2 + x3 * y1 + x2 * y3 - x3 * y2 - x1 * y3 - x2 * y1) * 3;
    var c1 = (x0 * y1 + x3 * y0 + x1 * y3 - x3 * y1 - x0 * y3 - x1 * y0) / cd;
    var c2 = (x0 * y2 + x3 * y0 + x2 * y3 - x3 * y2 - x0 * y3 - x2 * y0) / -cd;
    var l31 = [(y3 - y1) * 3, (x1 - x3) * 3, (x3 * y1 - y3 * x1) * 3];
    var l30 = [(y3 - y0) * 1, (x0 - x3) * 1, (x3 * y0 - y3 * x0) * 1];
    var l21 = [(y2 - y1) * 9, (x1 - x2) * 9, (x2 * y1 - y2 * x1) * 9];
    var l20 = [(y2 - y0) * 3, (x0 - x2) * 3, (x2 * y0 - y2 * x0) * 3];
    var l10 = [(y1 - y0) * 3, (x0 - x1) * 3, (x1 * y0 - y1 * x0) * 3];
    var la = [
      c1 * l31[0] + c2 * (l30[0] + l21[0]) + l20[0],
      c1 * l31[1] + c2 * (l30[1] + l21[1]) + l20[1],
      c1 * l31[2] + c2 * (l30[2] + l21[2]) + l20[2]];
    var lb = [
      c1 * l30[0] + c2 * l20[0] + l10[0],
      c1 * l30[1] + c2 * l20[1] + l10[1],
      c1 * l30[2] + c2 * l20[2] + l10[2]];
    this.t = function (x, y) {
      var a = la[0] * x + la[1] * y + la[2];
      var b = lb[0] * x + lb[1] * y + lb[2];
      return b / (b - a);
    };
  }

  BezierCurve.prototype = {
    x: function (t) {
      return cubicBezierCoordinate(this.A.x, this.B.x, this.C.x, this.D.x, t);
    },
    y: function (t) {
      return cubicBezierCoordinate(this.A.y, this.B.y, this.C.y, this.D.y,  t);
    },
    // Returns tangent angle measured from the positive x-axis in radians, CCW positive.
    phi: function (t) {
      var dx = cubicBezierDerivativeCoordinate(this.A.x, this.B.x, this.C.x, this.D.x, t);
      var dy = cubicBezierDerivativeCoordinate(this.A.y, this.B.y, this.C.y, this.D.y, t);
      return Math.atan2(dy, dx);
    },
    pathData: function () {
      var A = this.A, B = this.B, C = this.C, D = this.D;
      return ['M', A.x, A.y, 'C', B.x, B.y, C.x, C.y, D.x, D.y].join(' ');
    }
  };

  function Line(A, B) {
    this.A = A;
    this.B = B;
    var dx = B.x - A.x;
    var dy = B.y - A.y;
    this.phi_ = Math.atan2(dy, dx);
    this.t = Math.abs(dx) > Math.abs(dy)
      ? function (x, y) { return (x - A.x) / dx }
      : function (x, y) { return (y - A.y) / dy };
  }

  Line.prototype = {
    x: function (t) {
      return interpolate(this.A.x, this.B.x, t);
    },
    y: function (t) {
      return interpolate(this.A.y, this.B.y, t);
    },
    phi: function () {
      return this.phi_;
    },
    pathData: function () {
      var A = this.A, B = this.B;
      return ['M', A.x, A.y, 'L', B.x, B.y].join(' ');
    }
  };

  // Chooses middle two cubic Bézier control points given the two end points and
  // the directions from each to its adjacent control point measured from the
  // positive x-axis in radians, CCW positive, and returns a corresponding curve.
  function chooseCurve(A, thetaA, D, thetaD) {
    var lA = lineCoefficients(A, thetaA);
    var lD = lineCoefficients(D, thetaD);
    var P = intersectionOf(lA, lD);
    if (P) {
      var B = interpolatePoint(A, P, .7);
      var C = interpolatePoint(D, P, .7);
      return new BezierCurve(A, B, C, D);
    } else {
      return new Line(A, D);
    }
  }

  // Given a point P and an angle theta, returns coefficients [a,b,c] of the line
  // ax + by = c that crosses the point at the specified angle.
  function lineCoefficients(P, theta) {
    var d = Math.max(1, Math.abs(P.x), Math.abs(P.y));
    var Q = {
      x: P.x + d * Math.cos(theta),
      y: P.y + d * Math.sin(theta)
    };
    var a = Q.y - P.y;
    var b = P.x - Q.x;
    return [a, b, a * P.x + b * P.y];
  }

  function intersectionOf(lA, lB) {
    var det = lA[0] * lB[1] - lB[0] * lA[1];
    return Math.abs(det) > 1e-5 ? {
      x: (lB[1] * lA[2] - lA[1] * lB[2]) / det,
      y: (lA[0] * lB[2] - lB[0] * lA[2]) / det
    } : null;
  }

  function interpolatePoint(P, Q, alpha) {
    return {
      x: interpolate(P.x, Q.x, alpha),
      y: interpolate(P.y, Q.y, alpha)
    };
  }

  function interpolate(u, v, alpha) {
    return u + (v - u) * alpha;
  }

  function cubicBezierCoordinate(_1, _2, _3, _4, t) {
    var t2 = t * t;
    var oneMinusT = (1 - t);
    var oneMinusT2 = oneMinusT * oneMinusT;
    return (
      _1 * oneMinusT2 * oneMinusT +
      _2 * oneMinusT2 * t * 3 +
      _3 * oneMinusT * t2 * 3 +
      _4 * t2 * t);
  }

  function cubicBezierDerivativeCoordinate(_1, _2, _3, _4, t) {
    var oneMinusT = (1 - t);
    return (
      (_2 - _1) * oneMinusT * oneMinusT * 3 +
      (_3 - _2) * oneMinusT * t * 6 +
      (_4 - _3) * t * t * 3);
  }

  function headTransform(x, y, radians) {
    return ['translate(', x, 'px,', y, 'px) rotate(', Math.round(radians * 1000) / 1000, 'rad)'].join('');
  }

  function translatePx(x, y) {
    return ['translate(', x, 'px,', y, 'px)'].join('');
  }

  function swing(p) {
    return .5 - Math.cos(p * Math.PI) / 2;
  }

  return CurvedArrow;
}(window, document));
