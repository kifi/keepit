// @require styles/guide/svg_arrow.css

var SvgArrow = SvgArrow || (function (window, document) {
  'use strict';

  var HEAD_WIDTH = 20;
  var HEAD_LENGTH = HEAD_WIDTH / 2 * Math.sqrt(3);
  var TAIL_WIDTH = 4;

  function SvgArrow(elTail, elHead, angleTail, angleHead) {
    var rTail = elTail.getBoundingClientRect();
    var rHead = elHead.getBoundingClientRect();
    var x1, y1, x2, y2, xT, yT, xH, yH;
    switch (angleTail) {
      case 0:
        xT = 0;
        yT = HEAD_WIDTH / 2;
        x1 = rTail.right + 10;
        y1 = (rTail.top + rTail.bottom) / 2 - yT;
        break;
      default:
        throw Error('unsupported angleTail: ' + angleTail);
    }
    switch (angleHead) {
      case 0:
        var yTmp = (rHead.top + rHead.bottom) / 2;
        x2 = rHead.left - 10;
        y2 = yTmp + HEAD_WIDTH / 2;
        xH = x2 - x1;
        yH = yTmp - y1;
        break;
      case -45:
        x2 = rHead.left - 8;
        y2 = rHead.top - 8;
        xH = x2 - x1;
        yH = y2 - y1;
        break;
      case -90:
        var xTmp = (rHead.left + rHead.right) / 2;
        x2 = xTmp + HEAD_WIDTH / 2;
        y2 = rHead.top - 10;
        xH = xTmp - x1;
        yH = y2 - y1;
        break;
      default:
        throw Error('unsupported angleHead: ' + angleHead);
    }
    this.svg = $svg('svg')
      .attr('class', 'kifi-svg-arrow kifi-root')
      .attr('style', [
        'width:', x2 - x1, 'px;',
        'height:', y2 - y1, 'px;',
        'right:', window.innerWidth - x2, 'px;',
        'bottom:', window.innerHeight - y2, 'px']);
    this.tail = $svg('path')
      .attr('class', 'kifi-svg-arrow-tail')
      // .attr('d', 'M' + d3_svg_lineCardinalOpen([[xH, yH + 40], [xH, yH - HEAD_LENGTH - 6], [xT, yT], [xT - 90, yT]], 0))
      .attr('d', ['M', xH, yH - HEAD_LENGTH - 8, 'A', xH - xT, yH - HEAD_LENGTH - 8 - yT, 0, 0, 0, xT, yT].join(' '))
      .appendTo(this.svg.el);
    this.head = $svg('path')
      .attr('class', 'kifi-svg-arrow-head')
      .attr('d', triangle(xH, yH, angleHead))
      .appendTo(this.svg.el);
    // $svg('circle')
    //   .attr('cx', cp[0])
    //   .attr('cy', cp[1])
    //   .attr('r', 2)
    //   .attr('style', ['stroke:none;fill:yellow'])
    //   .appendTo(this.svg.el);
    this.attach();
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
      if (val.join) {
        val = val.join('');
      }
      this.el.setAttributeNS(null, name, val);
      return this;
    }
  };

  function triangle(x, y, angle) {
    return ['M', x, y, 'l', -HEAD_WIDTH / 2, -HEAD_LENGTH, 'l', HEAD_WIDTH, 0, 'z'].join(' ');
  }

  // github.com/mbostock/d3
  // Copyright (c) 2010-2014, Michael Bostock
  // All rights reserved.

  // Open cardinal spline interpolation; generates "C" commands.
  function d3_svg_lineCardinalOpen(points, tension) {
    return points[1] + d3_svg_lineHermite(
      points.slice(1, points.length - 1),
      d3_svg_lineCardinalTangents(points, tension));
  }

  // Hermite spline construction; generates "C" commands.
  function d3_svg_lineHermite(points, tangents) {
    if (tangents.length < 1
        || (points.length != tangents.length
        && points.length != tangents.length + 2)) {
      return d3_svg_lineLinear(points);
    }

    var quad = points.length != tangents.length,
        path = "",
        p0 = points[0],
        p = points[1],
        t0 = tangents[0],
        t = t0,
        pi = 1;

    if (quad) {
      path += "Q" + (p[0] - t0[0] * 2 / 3) + "," + (p[1] - t0[1] * 2 / 3)
          + "," + p[0] + "," + p[1];
      p0 = points[1];
      pi = 2;
    }

    if (tangents.length > 1) {
      t = tangents[1];
      p = points[pi];
      pi++;
      path += "C" + (p0[0] + t0[0]) + "," + (p0[1] + t0[1])
          + "," + (p[0] - t[0]) + "," + (p[1] - t[1])
          + "," + p[0] + "," + p[1];
      for (var i = 2; i < tangents.length; i++, pi++) {
        p = points[pi];
        t = tangents[i];
        path += "S" + (p[0] - t[0]) + "," + (p[1] - t[1])
            + "," + p[0] + "," + p[1];
      }
    }

    if (quad) {
      var lp = points[pi];
      path += "Q" + (p[0] + t[0] * 2 / 3) + "," + (p[1] + t[1] * 2 / 3)
          + "," + lp[0] + "," + lp[1];
    }

    return path;
  }

  // Generates tangents for a cardinal spline.
  function d3_svg_lineCardinalTangents(points, tension) {
    var tangents = [],
        a = (1 - tension) / 2,
        p0,
        p1 = points[0],
        p2 = points[1],
        i = 1,
        n = points.length;
    while (++i < n) {
      p0 = p1;
      p1 = p2;
      p2 = points[i];
      tangents.push([a * (p2[0] - p0[0]), a * (p2[1] - p0[1])]);
    }
    return tangents;
  }

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
