// @require styles/guide/step_4.css
// @require styles/guide/steps.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/guide/cut_screen.js
// @require scripts/guide/curved_arrow.js
// @require scripts/html/guide/step_4.js
// @require scripts/html/guide/steps.js

var guide = guide || {};
guide.step4 = guide.step4 || function () {
  'use strict';
  var $stage, screen, $feats, arrows, $steps, timeout;
  var holes = [
    {sel: '.kf-sidebar-nav,.kf-sidebar-tag-list', pad: [-5, -20, 30, 0], maxHeight: 246},
    {sel: '.kf-query', pad: [10], maxWidth: 320},
    {sel: '.kf-header-right>*', pad: [-6, 24]}
  ];
  var arcs = [
    {anchor: 'tl', from: {angle: 180, gap: 36, along: [0, .55]}, to: {angle: 100, gap: 20, along: [.95, 1], width: 0}},
    {anchor: 'tl', from: {angle: 150, gap: 14, along: [0, .5]}, to: {angle: 50, gap: 10, along: [.35, 1], width: 0}},
    {anchor: 'tr', from: {angle: 90, gap: 10, along: [.5, 0]}, to: {angle: 40, gap: 20, along: [.5, 1], width: 0}}
  ];
  return show;

  function show() {
    if (!$stage) {
      $stage = $(render('html/guide/step_4', me));
      screen = new CutScreen([]);
      $stage.prepend(screen.el).appendTo('body');
      $steps = $(render('html/guide/steps', {showing: true})).appendTo('body');
      $feats = $stage.find('.kifi-guide-feature');
      $stage.find('.kifi-guide-next').click(next);
      $steps.find('.kifi-guide-steps-x').click(hide);
      $(document).data('esc').add(hide);
      arrows = [];
      timeout = setTimeout(cutHole, 600);
    }
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).addClass('kifi-gone');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      screen.detach();
      arrows.forEach(function (arrow) {
        arrow.fadeAndDetach(300); // TODO: measure transition duration?
      });
      if (timeout) {
        clearTimeout(timeout);
      }
      $stage = screen = $feats = arrows = $steps = timeout = null;
      $(document).data('esc').remove(hide);
    }
  }

  function cutHole() {
    var i = arrows.length;
    var rect = screen.cut(holes[i]);
    var $f = $feats.eq(i).show();
    var arc = arcs[i];
    var tail = $.extend({el: $f[0]}, arc.from);
    var head = $.extend({rect: toClientRect(rect)}, arc.to);
    arrows.push(new CurvedArrow(tail, head, arc.anchor, 400));
    if (arrows.length < holes.length) {
      timeout = setTimeout(cutHole, 3000);
    }
  }

  function next() {
    if (this.parentNode.classList.contains('kifi-guide-drum-roll')) {
      // show farewell
    } else {
      hide();
    }
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

  function remove() {
    $(this).remove();
  }
}();
