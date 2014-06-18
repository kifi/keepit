// @require scripts/guide/step.js
// @require scripts/html/guide/step_3.js

guide.step3 = guide.step3 || function () {
  'use strict';
  var step, observer;
  var steps = [
    {
      lit: '.kifi-tile-card',
      pad: [20, 40],
      arrow: {from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: -70, gap: 10}},
      allow: {type: 'mouseover', target: '.kifi-tile-keep,.kifi-tile-kept'},
      pos: {bottom: 150, right: 70}
    },
    {
      lit: '.kifi-dock-compose',
      pad: [2, 188, 90, 70],
      arrow: {from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: -90, gap: 10}},
      allow: {type: 'click', target: '.kifi-dock-compose', proceed: true},
      pos: {bottom: 150, right: 190},
      substep: true
    },
    {
      lit: '.kifi-keeper',
      pad: [128, 100, 112, 20],
      arrow: {from: {angle: -90, gap: 10}, to: {angle: 0, gap: 5, along: [0, .45], sel: '.kifi-compose>.kifi-ti-list'}},
      allow: [
        {type: /^key/, target: '.kifi-compose input', unless: function (e) {return e.keyCode === 27 || e.keyCode >= 37 && e.keyCode <= 40}},  // esc, arrows
        {type: /^key/, target: '.kifi-compose-draft', unless: function (e) {return e.keyCode === 27}},  // esc
        {type: 'mousedown', target: '.kifi-ti-dropdown-item'}
      ],
      pos: {bottom: 250, right: 260},
      afterTransition: '.kifi-toast'
    },
    {
      lit: '.kifi-keeper',
      pad: [132, 104, 116, 24],
      // lit: '.kifi-toast',
      // pad: [-28, 100, 92, 70],
      arrow: {from: {angle: 0, gap: 16, along: [1, .55]}, to: {angle: -90, gap: 10, sel: '.kifi-compose-submit'}},
      allow: [
        {type: /^key/, target: '.kifi-compose input,.kifi-compose-draft,.kifi-compose-submit', unless: function (e) {return e.keyCode === 27}},  // esc
        {type: /^(?:mouse|click)/, target: '.kifi-compose input,.kifi-compose-draft,.kifi-compose-submit,.kifi-ti-token-x'}
      ],
      pos: {bottom: 280, right: 300}
    },
    {
      afterTransition: '.kifi-pane-box-cart',
      pos: {bottom: 300, right: 390},
      transition: 'opacity'
    },
    {
      pos: 'center'
    }
  ];
  return show;

  function show($guide, page, pageIdx) {
    if (!step) {
      step = guide.step(steps, {$guide: $guide, page: page, pageIdx: pageIdx, index: 3, done: .2, anchor: 'br', step: onStep, next: onClickNext, hide: onHide});
    }
  }

  function onStep(stepIdx) {
    switch (stepIdx) {
      case 0:
        observer = new MutationObserver(function (records) {
          if (elementAdded(records, 'kifi-keeper')) {
            observer.disconnect();
            observer = null;
            step.show(1);
          }
        });
        observer.observe(tile, {childList: true});
        break;
      case 2:
        observer = new MutationObserver(function (records) {
          if (elementAdded(records, 'kifi-ti-token')) {
            observer.disconnect();
            observer = null;
            step.show(3);
          }
        });
        setTimeout(function observeTokens() {
          var el = document.querySelector('.kifi-compose>.kifi-ti-list');
          if (el) {
            observer.observe(el, {childList: true});
          } else {
            setTimeout(observeTokens, 20);
          }
        }, 20);
        break;
      case 3:
        observer = new MutationObserver(function (records) {
          if (classAdded(records, 'kifi-active')) {
            observer.disconnect();
            observer = null;
            step.show(4);
          }
        });
        observer.observe(document.querySelector('.kifi-compose-submit'), {attributes: true, attributeFilter: ['class'], attributeOldValue: true});
        break;
    }
  }

  function onHide() {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
    step = null;
  }

  function onClickNext(e, stepIdx) {
    if (stepIdx === 4) {
      step.show(5);
    } else {
      step.nav('https://preview.kifi.com');
    }
  }

  function elementAdded(records, cssClass) {
    for (var i = 0; i < records.length; i++) {
      var nodes = records[i].addedNodes;
      for (var j = 0; j < nodes.length; j++) {
        var node = nodes[j];
        if (node.nodeType === 1 && node.classList.contains(cssClass)) {
          return node;
        }
      }
    }
  }

  function classAdded(records, cssClass) {
    for (var i = 0; i < records.length; i++) {
      var rec = records[i];
      if (rec.target.classList.contains(cssClass) && rec.oldValue.split(' ').indexOf(cssClass) < 0) {
        return rec.target;
      }
    }
  }
}();
