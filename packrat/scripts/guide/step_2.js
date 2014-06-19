// @require scripts/guide/step.js
// @require scripts/html/guide/step_2.js

guide.step2 = guide.step2 || function () {
  'use strict';
  var step;
  var steps = [
    {
      lit: '#kifi-res-list .r',
      pad: [20, -180, 70, 20],
      arrow: {from: {angle: 180, gap: 12, along: [0, .55]}, to: {angle: 120, gap: 4, along: [.5, 1], sel: '#kifi-res-list .kifi-res-title'}},
      allow: [
        {type: 'click', target: '.kifi-res-title', proceed: true},
        {type: /^mouse/, target: '.kifi-res-title'}
      ],
      pos: {top: 140, left: 520},
      fromRight: true
    }
  ];
  return {
    show: show,
    remove: function () {
      if (step) {
        step.removeAll();
      }
    }
  };

  function show($guide, page, pageIdx) {
    if (!step) {
      step = guide.step(steps, {
        $guide: $guide,
        page: page,
        pageIdx: pageIdx,
        index: 2,
        done: .5,
        anchor: 'tl',
        opacity: .65,
        hide: onHide
      });
    }
  }

  function onHide() {
    step = null;
  }
}();
