// @require styles/guide/svg_arrow.css

var SvgArrow = SvgArrow || (function (window, document) {
  'use strict';

  var HEAD_WIDTH = 20;
  var HEAD_LENGTH = HEAD_WIDTH / 2 * Math.sqrt(3);
  var TAIL_WIDTH = 4;

  function SvgArrow(elTail, elHead, angleTail, angleHead, revealMs) {
    if (angleTail !== 0) {
      throw Error('angleTail value not yet supported: ' + angleTail);
    }
    if (angleHead > 0 || angleHead < -90) {
      throw Error('angleHead value not yet supported: ' + angleHead);
    }
    var rTail = elTail.getBoundingClientRect();
    var rHead = elHead.getBoundingClientRect();
    /*
     ,--------,  svg bounding rect (box)
     |        |  . . . . . . . . . . . .
     | elTail |  : o o o o .,          :
     |        |  : T         `o.,      :   <- dotted cubic Bézier curve
     '--------'  :               `o    :
                 :              ---•---:   <- arrowhead axis is tangent to arc where they meet at G
                 :               \ G / :
                 :                \ /  :   <- arrowhead orientation is configurable (-90° shown)
                 : . . . . . . . . V . :         __
                                   H              |
                                   .              | tip space height
                         ,---------.---------,   -'
                         | elHead  .         |
                         |         •C        |  <- arrowhead points to center of elHead
                         |                   |
                         '-------------------'
    */
    var T = {
      x: Math.ceil(rTail.right + 10 + TAIL_WIDTH / 2),
      y: Math.round((rTail.top + rTail.bottom) / 2 + 2)
    };
    var C = {
      x: (rHead.left + rHead.right) / 2,
      y: (rHead.top + rHead.bottom) / 2
    };
    var angleHeadRad = Math.PI / 180 * angleHead;
    var tipSpace = 10;
    var tipSpaceHeight = tipSpace * Math.sin(-angleHeadRad);
    var H = {
      x: C.x + (tipSpaceHeight + rHead.height / 2) / Math.tan(angleHeadRad),
      y: rHead.top - tipSpaceHeight
    };
    var G = {
      x: H.x - HEAD_LENGTH * Math.cos(angleHeadRad),
      y: H.y + HEAD_LENGTH * Math.sin(angleHeadRad)
    };
    var box = {
      left: -1 + Math.floor(T.x - TAIL_WIDTH / 2),
      top: -1 + Math.floor(T.y - HEAD_WIDTH / 2),
      right: 1 + Math.ceil(H.x + Math.max(0, -HEAD_LENGTH * Math.sin(angleHeadRad + Math.PI / 3))),
      bottom: 1 + Math.ceil(H.y + Math.max(0, HEAD_LENGTH * Math.sin(angleHeadRad + Math.PI / 6)))
    };
    var curve = this.curve = chooseCurve(
      {x: T.x - box.left, y: T.y - box.top}, Math.PI / 180 * angleTail,
      {x: G.x - box.left, y: G.y - box.top}, Math.PI - angleHeadRad);
    this.svg = $svg('svg')
      .attr('class', 'kifi-svg-arrow kifi-root')
      .attr('style', [
        'width:', box.right - box.left, 'px;',
        'height:', box.bottom - box.top, 'px;',
        'right:', window.innerWidth - box.right, 'px;',
        'bottom:', window.innerHeight - box.bottom, 'px'
      ].join(''));
    this.tail = $svg('path')
      .attr('class', 'kifi-svg-arrow-tail')
      .attr('d', curvePathData(curve))
      .attr('style', tailDashArrayStyle(0))
      .appendTo(this.svg.el);
    this.head = $svg('path')
      .attr('class', 'kifi-svg-arrow-head')
      .attr('d', headPathData())
      .attr('transform', headTransform(curve.x(0), curve.y(0), curve.phi(0)))
      .appendTo(this.svg.el);
    // $svg('circle')
    //   .attr('r', 3)
    //   .attr('cx', curve.B.x)
    //   .attr('cy', curve.B.y)
    //   .appendTo(this.svg.el);
    // $svg('circle')
    //   .attr('r', 3)
    //   .attr('cx', curve.C.x)
    //   .attr('cy', curve.C.y)
    //   .appendTo(this.svg.el);
    this.attach();
    reveal.call(this, revealMs);
  }

  SvgArrow.prototype = {
    attached: false,
    attach: function () {
      if (!this.attached) {
        document.body.appendChild(this.svg.el);
        this.attached = true;
      }
      return this;
    },
    fadeAndDetach: function () {
      if (this.attached) {
        // TODO: fade first
        this.svg.el.remove();
        this.attached = false;
      }
      return this;
    }
  };

  function reveal(ms) {
    var totalLen = this.tail.el.getTotalLength();
    var ease = swing;
    var ms_1 = 1 / ms;
    var t0 = window.performance.now();
    var tN = t0 + ms;
    var tick = this.tick = (function (t) {
      if (this.tick === tick) {
        var alpha = t < tN ? t > t0 ? ease((t - t0) * ms_1) : 0 : 1;
        var len = alpha * totalLen;
        var P = this.tail.el.getPointAtLength(len);
        this.tail.attr('style', tailDashArrayStyle(Math.round(len / 9)));
        this.head.attr('transform', headTransform(P.x, P.y, this.curve.phi(this.curve.t(P.x, P.y))));
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
    appendTo: function (el) {
      el.appendChild(this.el);
      return this;
    },
    attr: function (name, val) {
      this.el.setAttributeNS(null, name, val);  // TODO: cache attr values to avoid writes
      return this;
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
    }
  };

  // Chooses middle two cubic Bézier control points given the two end points and
  // the directions from each to its adjacent control point measured from the
  // positive x-axis in radians, CCW positive, and returns a corresponding curve.
  function chooseCurve(A, thetaA, D, thetaD) {
    var lA = lineCoefficients(A, thetaA);
    var lD = lineCoefficients(D, thetaD);
    var P = intersectionOf(lA, lD);
    var B = interpolatePoint(A, P, .5);
    var C = interpolatePoint(D, P, .5);
    return new BezierCurve(A, B, C, D);
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
    return det ? {
      x: (lB[1] * lA[2] - lA[1] * lB[2]) / det,
      y: (lA[0] * lB[2] - lB[0] * lA[2]) / det
    } : null;
  }

  function interpolatePoint(P, Q, alpha) {
    return {
      x: P.x * (1 - alpha) + Q.x * alpha,
      y: P.y * (1 - alpha) + Q.y * alpha
    };
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
  function curvePathData(c) {
    return ['M', c.A.x, c.A.y, 'C', c.B.x, c.B.y, c.C.x, c.C.y, c.D.x, c.D.y].join(' ');
  }

  function headPathData() {
    return ['M', 0, - HEAD_WIDTH / 2, 'l', 0, HEAD_WIDTH, 'l', HEAD_LENGTH, -HEAD_WIDTH / 2, 'z'].join(' ');
  }

  function headTransform(x, y, phi) {
    return ['translate(', x, ',', y, ') rotate(', 180 / Math.PI * phi, ')'].join('');
  }

  function tailDashArrayStyle(numDots) {
    if (numDots > 0) {
      var arr = new Array(2 * numDots + 1);
      arr[0] = 'stroke-dasharray:';
      for (var i = 1; i < arr.length; i += 2) {
        arr[i] = .001;
        arr[i+1] = 9;
      }
      arr[arr.length - 1] = 9999;
      return arr.join(' ');
    } else {
      return 'stroke-dasharray:0 9999';
    }
  }

  function swing(p) {
    return .5 - Math.cos(p * Math.PI) / 2;
  }

  // github.com/mbostock/d3
  // Copyright (c) 2010-2014, Michael Bostock
  // All rights reserved.

  var d3_interpolate_numberA = /[-+]?(?:\d+\.?\d*|\.?\d+)(?:[eE][-+]?\d+)?/g,
      d3_interpolate_numberB = new RegExp(d3_interpolate_numberA.source, 'g');

  function d3_interpolateNumber(a, b) {
    b -= a = +a;
    return function(t) { return a + b * t; };
  }

  function d3_interpolateString(a, b) {
    var bi = d3_interpolate_numberA.lastIndex = d3_interpolate_numberB.lastIndex = 0, // scan index for next number in b
        am, // current match in a
        bm, // current match in b
        bs, // string preceding current number in b, if any
        i = -1, // index in s
        s = [], // string constants and placeholders
        q = []; // number interpolators

    // Coerce inputs to strings.
    a = a + '', b = b + '';

    // Interpolate pairs of numbers in a & b.
    while ((am = d3_interpolate_numberA.exec(a))
        && (bm = d3_interpolate_numberB.exec(b))) {
      if ((bs = bm.index) > bi) { // a string precedes the next number in b
        bs = b.substring(bi, bs);
        if (s[i]) s[i] += bs; // coalesce with previous string
        else s[++i] = bs;
      }
      if ((am = am[0]) === (bm = bm[0])) { // numbers in a & b match
        if (s[i]) s[i] += bm; // coalesce with previous string
        else s[++i] = bm;
      } else { // interpolate non-matching numbers
        s[++i] = null;
        q.push({i: i, x: d3_interpolateNumber(am, bm)});
      }
      bi = d3_interpolate_numberB.lastIndex;
    }

    if (bi < b.length) {
      bs = b.substring(bi);
      if (s[i]) s[i] += bs; // coalesce with previous string
      else s[++i] = bs;
    }

    return s.length < 2
        ? (q[0] ? (b = q[0].x, function(t) { return b(t) + ''; })
        : function() { return b; })
        : (b = q.length, function(t) {
            for (var i = 0, o; i < b; ++i) s[(o = q[i]).i] = o.x(t);
            return s.join('');
          });
  }

  return SvgArrow;
}(window, document));
