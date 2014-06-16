// @require styles/guide/step_4.css
// @require styles/guide/steps.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/guide/cut_screen.js
// @require scripts/guide/curved_arrow.js
// @require scripts/html/guide/step_4.js
// @require scripts/html/guide/steps.js

guide.step4 = guide.step4 || function () {
  'use strict';
  var $stage, cutScreen, $feats, arrows, $steps, timeout;
  var holes = [
    {sel: '.kf-sidebar-nav,.kf-sidebar-tag-list', pad: [-5, -20, 30, 0], maxHeight: 246},
    {sel: '.kf-query', pad: [6, 8], maxWidth: 320},
    {sel: '.kf-header-right>*', pad: [-6, 24]}
  ];
  var arcs = [
    {anchor: 'tl', from: {angle: 180, gap: 36, along: [0, .55], spacing: 7}, to: {angle: 100, gap: 20, along: [.95, 1], width: 0}},
    {anchor: 'tl', from: {angle: 150, gap: 20, along: [0, .35], spacing: 7}, to: {angle: 78, gap: 12, along: [.32, 1], width: 0}},
    {anchor: 'tr', from: {angle: 100, gap: 0, along: [.5, 0], spacing: 7}, to: {angle: 30, gap: 16, along: [.5, .7], width: 0}}
  ];
  return show;

  function show() {
    if (!$stage) {
      $stage = $(render('html/guide/step_4', me));
      cutScreen = new CutScreen([]);
      $stage.prepend(cutScreen.el).appendTo('body');
      $steps = $(render('html/guide/steps', {showing: true})).appendTo('body');
      $feats = $stage.find('.kifi-guide-feature');
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
      cutScreen.fadeAndDetach(340);
      arrows.forEach(function (arrow) {
        arrow.fadeAndDetach(340);
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
    var rect = cutScreen.cut(holes[i], 200);
    $feats.eq(i)
      .show()
      .each(layout)
      .one('transitionend', function () {
        var arc = arcs[i];
        var tail = $.extend({el: this}, arc.from);
        var head = $.extend({rect: toClientRect(rect)}, arc.to);
        arrows.push(new CurvedArrow(tail, head, arc.anchor, 400));
        timeout = setTimeout(arrows.length < holes.length ? cutHole : drumRoll, 1200);
      })
      .addClass('kifi-opaque');
  }

  function drumRoll() {
    $stage.find('.kifi-guide-drum-roll')
      .show()
      .each(layout)
      .addClass('kifi-opaque');
    $stage.find('.kifi-guide-next').click(farewell);
  }

  function farewell() {
    cutScreen.fill(200);
    arrows.forEach(function (arrow) {
      arrow.fadeAndDetach(200);
    });
    $feats.add('.kifi-guide-drum-roll')
      .on('transitionend', remove)
      .removeClass('kifi-opaque');
    $stage.find('.kifi-guide-farewell')
      .show()
      .each(layout)
      .addClass('kifi-opaque')
    .find('.kifi-guide-next')
      .click(hide);
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

  function layout() {
    this.clientHeight; // forces layout
  }

  function remove() {
    $(this).remove();
  }
}();
