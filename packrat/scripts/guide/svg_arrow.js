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
     |        |  . . . . . . . . . . . .    -,
     | elTail |  : o o o o .,          :     | visible portion
     |        |  : T         `o.,      :     | of elliptical arc
     '--------'  :               `o    :    -'
                 :              ---•---:    <- arrowhead axis is tangent to arc where they meet at G
                 :               \ G / :
                 :                \ /  :    <- arrowhead orientation is configurable (-90° shown)
                 : . . . . . . . . V . :         __
                   +y              H              |
     origin ->   - • +x            .              | tip space height
    for paths      -     ,---------.---------,   -'
    (center of           | elHead  .         |
     ellipse)            |         •C        |  <- arrowhead points to center of elHead
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
    var arc = this.arc = ellipseArcTo(G.x - T.x, T.y - G.y, Math.PI + angleHeadRad);
    this.svg = $svg('svg')
      .attr('class', 'kifi-svg-arrow kifi-root')
      .attr('style', [
        'width:', box.right - box.left, 'px;',
        'height:', box.bottom - box.top, 'px;',
        'right:', window.innerWidth - box.right, 'px;',
        'bottom:', window.innerHeight - box.bottom, 'px']);
    this.g = $svg('g')
      .attr('transform', ['translate(', T.x - box.left, ',', T.y - box.top + arc.b, ') scale(1,-1)'])
      .appendTo(this.svg.el);
    this.tail = $svg('path')
      .attr('class', 'kifi-svg-arrow-tail')
      .attr('d', ellipseArcPathData(arc), ' ')
      .attr('style', tailDashArrayStyle(0))
      .appendTo(this.g.el);
    this.head = $svg('path')
      .attr('class', 'kifi-svg-arrow-head')
      .attr('d', headPathData(), ' ')
      .attr('transform', headTransform(arc, arc.t0))
      .appendTo(this.g.el);
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
    var subArc = $svg('path');
    var ease = swing;
    var ms_1 = 1 / ms;
    var t0 = window.performance.now();
    var tN = t0 + ms;
    var tick = this.tick = (function (t) {
      if (this.tick === tick) {
        var arc = this.arc;
        var frac = t < tN ? t > t0 ? ease((t - t0) * ms_1) : 0 : 1;
        var arcT = arc.t0 * (1 - frac) + arc.t1 * frac;
        subArc.attr('d', ellipseArcPathData(new EllipseArc(arc.a, arc.b, arc.t0, arcT)), ' ');
        this.tail.attr('style', tailDashArrayStyle(Math.round(subArc.el.getTotalLength() / 9)));
        this.head.attr('transform', headTransform(arc, arcT));
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
    attr: function (name, val, sep) {
      if (val.join) {
        val = val.join(sep || '');
      }
      this.el.setAttributeNS(null, name, val);  // TODO: cache attr values to avoid writes
      return this;
    }
  };

  function EllipseArc(a, b, t0, t1) {  // axis-aligned, at origin
    this.a = a;
    this.b = b;
    this.t0 = t0;
    this.t1 = t1;
  }

  EllipseArc.prototype = {  // x(t) and y(t) return SVG coordinates
    x: function (t) {
      return this.a * Math.cos(t);
    },
    y: function (t) {
      return this.b * Math.sin(t);
    },
    phi: function (t) {
      return Math.atan2(-this.b, this.a * Math.tan(t));
    }
  };

  // Identifies axis-aligned ellipse from the origin (the top of the ellipse)
  // to point P (x,y) with tangent angle phi at P. phi is measured from the
  // positive x-axis in radians, CCW positive.
  function ellipseArcTo(x, y, phi) {
    // solving for parameter t and then radii a and b using formulæ from
    // en.wikipedia.org/wiki/Ellipse#Parametric_form_in_canonical_position
    // with b subtracted from Y(t) to account for vertical translation between ellipse's top and center
    //   X(t) === a * cos(t)
    //   Y(t) === b * sin(t) - b
    //   -tan(t) === b / (a * tan(phi))

    var t = Math.asin(y / (y + x * Math.tan(phi)));
    var a = x / Math.cos(t);
    var b = y / (Math.sin(t) - 1);

    return new EllipseArc(a, b, Math.PI / 2, t);
  }

  function ellipseArcPathData(arc) {
    return ['M', arc.x(arc.t0), arc.y(arc.t0), 'A', arc.a, arc.b, 0, 0, 0, arc.x(arc.t1), arc.y(arc.t1)];
  }

  function headPathData() {
    return ['M', 0, - HEAD_WIDTH / 2, 'l', 0, HEAD_WIDTH, 'l', HEAD_LENGTH, -HEAD_WIDTH / 2, 'z'];
  }

  function headTransform(arc, t) {
    return ['translate(', arc.x(t), ',', arc.y(t), ') rotate(', 180 / Math.PI * arc.phi(t), ')'];
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
