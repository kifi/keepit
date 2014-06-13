// @require scripts/guide/step.js
// @require scripts/html/guide/step_2.js

guide.step2 = guide.step2 || function () {
  'use strict';
  var showStep;
  var steps = [
    {
      lit: '#kifi-res-list .r',
      pad: [20, -180, 70, 20],
      arrow: {from: {angle: 180, gap: 12}, to: {angle: 120, gap: 4, along: [.9, 1], sel: '#kifi-res-list .kifi-res-title'}},
      allow: {type: 'click', allow: '.kifi-res-title'},
      pos: {top: 120, left: 520}
    }
  ];
  return show;

  function show() {
    if (!showStep) {
      showStep = guide.step(steps, {page: 2, anchor: 'tl', hide: onHide});
      showStep(0);
    }
  }

  function onHide() {
    showStep = null;
  }
}();
