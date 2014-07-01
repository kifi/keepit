// @require styles/guide/step_4.css
// @require styles/guide/guide.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/guide/cut_screen.js
// @require scripts/guide/curved_arrow.js
// @require scripts/html/guide/step_4.js

guide.step4 = guide.step4 || function () {
  'use strict';
  var $stage, cutScreen, $feats, arrows, $steps, timeout;
  var holes = [
    {sel: '.kf-sidebar-nav,.kf-sidebar-tag-list', pad: [-5, -5, 30, 0], maxHeight: 246},
    {sel: '.kf-query', pad: [6, 8], maxWidth: 320},
    {sel: '.kf-header-right>*', pad: [-6, 24]}
  ];
  var arcs = [
    {dx: -63, dy: -40, from: {angle: 180, gap: 36, along: [0, .55], spacing: 7}, to: {angle: 100, gap: 20, along: [.95, 1]}},
    {dx: -30, dy: -70, from: {angle: 150, gap: 20, along: [0, .35], spacing: 7}, to: {angle: 78, gap: 12, along: [.32, 1]}},
    {dx: 26, dy: -70, from: {angle: 100, gap: 0, along: [.5, 0], spacing: 7}, to: {angle: 30, gap: 16, along: [.5, .7]}}
  ];
  return {show: show, remove: removeAll};

  function show($guide, __, ___, allowEsc) {
    if (!$stage) {
      var show2Bound = show2.bind(null, $guide, allowEsc);
      var $html = $('html');
      if ($html.hasClass('kf-sidebar-active')) {
        show2Bound();
      } else {
        window.postMessage('show_left_column', location.origin);
        $html.on('transitionend.guideStep4', '.kf-sidebar', function (e) {
          if (e.target === this) {
            show2Bound();
          }
        });
        timeout = setTimeout(show2Bound, 900);
      }
    }
  }

  function show2($guide, allowEsc) {
    $('html').off('transitionend.guideStep4');
    $stage = $(render('html/guide/step_4', me)).appendTo('body');
    cutScreen = new CutScreen([], $stage[0], $stage[0].firstChild);
    $steps = $guide.appendTo('body')
      .one('click', '.kifi-guide-x', hide);
    $steps.layout().data().updateProgress(.2);
    $feats = $stage.find('.kifi-guide-feature');
    if (allowEsc) {
      $(document).data('esc').add(hide);
    }
    arrows = [];
    clearTimeout(timeout);
    timeout = setTimeout(cutHole, 600);
    api.port.emit('track_guide', [4, 0]);
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).addClass('kifi-gone');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      cutScreen.fadeAndDetach(340);
      arrows.forEach(function (arrow) {
        arrow.fadeAndDetach(340);
      });
      if (timeout) {
        clearTimeout(timeout);
      }
      api.port.emit('end_guide', [4, $stage.find('.kifi-guide-farewell').hasClass('kifi-opaque') ? 1 : 0]);
      $stage = cutScreen = $feats = arrows = $steps = timeout = null;
      $(document).data('esc').remove(hide);
    }
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
    var rHead = toClientRect(cutScreen.cut(holes[i], 200));
    var arc = arcs[i];

    var headAngleRad = Math.PI / 180 * arc.to.angle;
    var H = pointOutsideRect(rHead, arc.to.along, headAngleRad + Math.PI, arc.to.gap);
    var T = {x: H.x - arc.dx, y: H.y - arc.dy};

    // compute T_ (T with feature positioned at top-left window corner)
    var $feat = $feats.eq(i)
      .css({top: 0, left: 0, display: 'block'});
    var rTail = $feat[0].getBoundingClientRect();
    var tailAngleRad = Math.PI / 180 * arc.from.angle;
    var T_ = pointOutsideRect(rTail, arc.from.along, tailAngleRad, arc.from.gap);

    $feat
      .css({left: T.x - T_.x, top: T.y - T_.y})
      .each(layout)
      .one('transitionend', function () {
        var arrow = new CurvedArrow(
          {x: T.x, y: T.y, angle: arc.from.angle, spacing: arc.from.spacing},
          {x: H.x, y: H.y, angle: arc.to.angle, draw: false},
          {top: 0, left: 0});
        arrow.reveal(400);
        arrows.push(arrow);
        timeout = setTimeout(arrows.length < holes.length ? cutHole : drumRoll, 1200);
        $steps.data().updateProgress((i + 2) / (arcs.length + 2));
      })
      .addClass('kifi-opaque');
  }

  function drumRoll() {
    $stage.find('.kifi-guide-drum-roll')
      .show()
      .each(layout)
      .addClass('kifi-opaque')
    .find('.kifi-guide-4-next')
      .one('click', farewell);
  }

  function farewell() {
    cutScreen.fill(260);
    arrows.forEach(function (arrow) {
      arrow.fadeAndDetach(260);
    });
    var farewellShown;
    $feats.add('.kifi-guide-drum-roll')
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
