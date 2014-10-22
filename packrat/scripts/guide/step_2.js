// @require scripts/guide/step.js
// @require scripts/html/guide/step_2.js

guide.step2 = guide.step2 || function () {
  'use strict';
  var step;
  var steps = [
    {
      lit: '#kifi-res-list .r',
      pad: [20, -180, 70, 20],
      arrow: {dx: -200, dy: -100, from: {angle: 180, gap: 12, along: [0, .55]}, to: {angle: 120, gap: 4, along: [.5, 1]}},
      allow: [
        {type: 'click', target: '#kifi-res-list .r a[href]', proceed: true},  // note: Google sometimes alters the link's class name
        {type: /^mouse/, target: '#kifi-res-list .r a[href]'}
      ]
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

  function show($guide, page, allowEsc) {
    if (!step) {
      step = guide.step(steps, {
        $guide: $guide,
        page: page,
        index: 2,
        done: .5,
        anchor: 'tl',
        opacity: .65,
        hide: onHide,
        esc: allowEsc
      });
    }
  }

  function onHide() {
    step = null;
  }
}();
