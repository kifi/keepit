// @require scripts/guide/step.js
// @require scripts/html/guide/step_2.js

guide.step2 = guide.step2 || function () {
  'use strict';
  var showStep;
  var steps = [
    {
      lit: '#kifi-res-list .r',
      pad: [20, -180, 70, 20],
      arrow: {from: {angle: 180, gap: 12, along: [0, .55]}, to: {angle: 120, gap: 4, along: [.9, 1], sel: '#kifi-res-list .kifi-res-title'}},
      allow: {type: 'click', target: '.kifi-res-title'},
      pos: {top: 120, left: 520}
    }
  ];
  return show;

  function show() {
    if (!showStep) {
      showStep = guide.step(steps, {page: 2, anchor: 'tl', hide: onHide});
      showStep(0);
      window.addEventListener('click', onClick, true);
    }
  }

  function onHide() {
    showStep = null;
    window.removeEventListener('click', onClick, true);
  }

  function onClick(e) {
    if (e.which === 1 && e.target.classList.contains('kifi-res-title')) {
      e.preventDefault();
      var url = e.target.href;
      api.port.emit('await_deep_link', {locator: '#guide/3', url: url});
      window.location = url;
    }
  }
}();
