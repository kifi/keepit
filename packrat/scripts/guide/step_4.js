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
    {sel: '.kf-sidebar-nav,.kf-sidebar-tag-list', pad: [-5, -20, 30, 0], maxHeight: 246},
    {sel: '.kf-query', pad: [6, 8], maxWidth: 320},
    {sel: '.kf-header-right>*', pad: [-6, 24]}
  ];
  var arcs = [
    {anchor: 'tl', from: {angle: 180, gap: 36, along: [0, .55], spacing: 7}, to: {angle: 100, gap: 20, along: [.95, 1], draw: false}},
    {anchor: 'tl', from: {angle: 150, gap: 20, along: [0, .35], spacing: 7}, to: {angle: 78, gap: 12, along: [.32, 1], draw: false}},
    {anchor: 'tr', from: {angle: 100, gap: 0, along: [.5, 0], spacing: 7}, to: {angle: 30, gap: 16, along: [.5, .7], draw: false}}
  ];
  return show;

  function show($guide) {
    if (!$stage) {
      $stage = $(render('html/guide/step_4', me));
      cutScreen = new CutScreen([]);
      $stage.prepend(cutScreen.el).appendTo('body');
      $steps = $guide.appendTo('body')
        .on('click', '.kifi-gs-x', hide);
      $steps.layout().data().updateProgress(.2);
      $feats = $stage.find('.kifi-guide-feature');
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
        $steps.data().updateProgress((i + 2) / (arcs.length + 2));
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
          .find('.kifi-guide-next')
            .click(hide);
        }
      })
      .removeClass('kifi-opaque');
    $steps.data().updateProgress(1);
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
