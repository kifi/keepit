// @require styles/guide/step_4.css
// @require styles/guide/guide.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/guide/cut_screen.js
// @require scripts/guide/curved_arrow.js
// @require scripts/html/guide/step_4.js

k.guide.step4 = k.guide.step4 || function () {
  'use strict';
  var $stage, cutScreen, $feats, arrows, $steps, timeout;
  var holes = [
    {sel: '.kifi-guide-1', pad: [8]},
    {sel: '.kifi-guide-2', pad: [-9, 10, -9, -14]}
  ];
  var arcs = [
    {dx: 0, dy: -48, from: {angle: 90, gap: 12, along: [.4, 0], spacing: 7}, to: {angle: 90, gap: 12, along: [.4, 1]}},
    {dx: 0, dy: -48, from: {angle: 90, gap: 12, along: [.4, 0], spacing: 7}, to: {angle: 90, gap: 12, along: [.37, 1]}}
  ];
  return {show: show, remove: removeAll};

  function show($guide) {
    if (!$stage) {
      show2($guide);
    }
  }

  function show2($guide) {
    $('html').off('transitionend.guideStep4');
    $stage = $(k.render('html/guide/step_4', k.me)).appendTo('body');
    cutScreen = new CutScreen([], $stage[0], $stage[0].firstChild);
    $steps = $guide.appendTo('body');
    $steps.layout().data().updateProgress(.2);
    $feats = $stage.find('.kifi-guide-feature');
    $(document).data('esc').add(hide);
    arrows = [];
    clearTimeout(timeout);
    timeout = setTimeout(welcome, 600);
    api.port.emit('track_guide', [4, 0]);
  }

  function hide(e) {
    if ($stage) {
      $stage.one('transitionend', remove).addClass('kifi-gone');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      var ms = 340;
      cutScreen.fadeAndDetach(ms);
      arrows.forEach(function (arrow) {
        arrow.fadeAndDetach(ms);
      });
      if (timeout) {
        clearTimeout(timeout);
      }
      api.port.emit('end_guide', [4, $stage.find('.kifi-guide-farewell').hasClass('kifi-opaque') ? 1 : 0]);
      $stage = cutScreen = $feats = arrows = $steps = timeout = null;
      $(document).data('esc').remove(hide);

      if (e && $(e.target).hasClass('kifi-guide-4-import')) {
        api.port.emit('count_bookmarks', function (n) {
          window.postMessage({type: 'import_bookmarks', count: n}, location.origin);
        });
      }
    }
  }

  function welcome() {
    $stage.find('.kifi-guide-4-welcome')
      .show()
      .each(layout)
      .addClass('kifi-opaque');
    clearTimeout(timeout);
    timeout = setTimeout(cutHole, 800);
  }

  function removeAll() {
    if ($stage) {
      $stage.remove();
      $steps.remove();
      cutScreen.detach();
      arrows.forEach(function (arrow) {
        arrow.detach();
      });
      if (timeout) {
        clearTimeout(timeout);
      }
      $stage = cutScreen = $feats = arrows = $steps = timeout = null;
      $(document).data('esc').remove(hide);
    }
  }

  function cutHole() {
    var i = arrows.length;
    var hole = holes[i];
    var anchor = createAnchor(hole.anchor || 'tl');
    var rHead = anchor.translate(toClientRect(cutScreen.cut(hole, 200)));
    var arc = arcs[i];

    var headAngleRad = Math.PI / 180 * arc.to.angle;
    var H = pointOutsideRect(rHead, arc.to.along, headAngleRad + Math.PI, arc.to.gap);
    var T = {x: H.x - arc.dx, y: H.y - arc.dy};

    // compute T_ (T with stage positioned at appropriate window corner)
    var $feat = $feats.eq(i)
      .css(anchor.css)
      .css('display', 'block');
    var rTail = anchor.translate($feat[0].getBoundingClientRect());
    var tailAngleRad = Math.PI / 180 * arc.from.angle;
    var T_ = pointOutsideRect(rTail, arc.from.along, tailAngleRad, arc.from.gap);

    $feat
      .css(translatePos(anchor.css, T.x - T_.x, T.y - T_.y))
      .each(layout)
      .one('transitionend', function () {
        var arrow = new CurvedArrow(
          {x: T.x, y: T.y, angle: arc.from.angle, spacing: arc.from.spacing},
          {x: H.x, y: H.y, angle: arc.to.angle, draw: false},
          anchor.css);
        arrow.reveal(400);
        arrows.push(arrow);
        timeout = setTimeout(arrows.length < holes.length ? cutHole : drumRoll, 1200);
        $steps.data().updateProgress((i + 2) / (arcs.length + 2));
      })
      .addClass('kifi-opaque');
  }

  function drumRoll() {
    $stage.find('.kifi-guide-4-welcome>.kifi-guide-4-next')
      .addClass('kifi-opaque')
      .one('click', farewell);
  }

  function farewell() {
    cutScreen.fill(260);
    arrows.forEach(function (arrow) {
      arrow.fadeAndDetach(260);
    });
    var farewellShown;
    $feats.add('.kifi-guide-4-welcome')
      .on('transitionend', function () {
        $(this).remove();
        if (!farewellShown) {
          farewellShown = true;
          $stage.find('.kifi-guide-farewell')
            .show()
            .each(layout)
            .addClass('kifi-opaque')
          .find('.kifi-guide-4-next')
            .one('click', hide);
        }
      })
      .removeClass('kifi-opaque');
    $steps.data().updateProgress(1);
    api.port.emit('track_guide', [4, 1]);
  }

  function toClientRect(r) {
    return {
      top: r.y,
      left: r.x,
      right: r.x + r.w,
      bottom: r.y + r.h,
      width: r.w,
      height: r.h
    };
  }

function createAnchor(code) {  // also in step.js
    var dx = code[1] === 'r' ? -window.innerWidth : 0;
    var dy = code[0] === 'b' ? -window.innerHeight : 0;
    return {
      translate: function (o) {
        var o2 = {};
        for (var name in o) {
          var val = o[name];
          if (typeof val === 'number') {
            if (/^(?:x|left|right)$/.test(name)) {
              o2[name] = val + dx;
              continue;
            }
            if (/^(?:y|top|bottom)$/.test(name)) {
              o2[name] = val + dy;
              continue;
            }
          }
          o2[name] = val;
        }
        return o2;
      },
      css: {
        top: dy ? 'auto' : 0,
        left: dx ? 'auto' : 0,
        right: dx ? 0 : 'auto',
        bottom: dy ? 0 : 'auto'
      }
    };
  }

  function translatePos(pos, dx, dy) {  // also in step.js
    return {
      top: typeof pos.top === 'number' ? pos.top + dy : pos.top,
      left: typeof pos.left === 'number' ? pos.left + dx : pos.left,
      right: typeof pos.right === 'number' ? pos.right - dx : pos.right,
      bottom: typeof pos.bottom === 'number' ? pos.bottom - dy : pos.bottom
    };
  }

  function pointOutsideRect(r, along, theta, d) {  // also in step.js
    var Px = r.left + (r.right - r.left) * (along != null ? along[0] : .5);
    var Py = r.top + (r.bottom - r.top) * (along != null ? along[1] : .5);

    // determine the two candidate edges (x = Cx and y = Cy)
    var sinTheta = Math.sin(theta);
    var cosTheta = Math.cos(theta);
    var Cx = cosTheta < 0 ? r.left - d : r.right + d;
    var Cy = sinTheta > 0 ? r.top - d : r.bottom + d;

    // find where ray from P crosses: (Qx, Cy) and (Cx, Qy)
    var tanTheta = sinTheta / cosTheta;
    var Qx = Px + (Py - Cy) / tanTheta;
    var Qy = Py + (Px - Cx) * tanTheta;

    // choose the point closer to P
    return dist2(Px, Py, Qx, Cy) < dist2(Px, Py, Cx, Qy) ? {x: Qx, y: Cy} : {x: Cx, y: Qy};
  }

  function dist2(x1, y1, x2, y2) {
    var dx = x1 - x2;
    var dy = y1 - y2;
    return dx * dx + dy * dy;
  }

  function layout() {
    this.clientHeight; // forces layout
  }

  function remove() {
    $(this).remove();
  }
}();
