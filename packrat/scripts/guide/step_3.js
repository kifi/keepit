// @require scripts/guide/step.js
// @require scripts/html/guide/step_3.js

k.guide.step3 = k.guide.step3 || function () {
  'use strict';
  var step, observer;
  var steps = [
    {
      lit: '.kifi-tile-card',
      pad: [20, 40],
      arrow: {dx: 130, dy: 87, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: -70, gap: 10}},
      allow: {type: 'mouseover', target: '.kifi-tile-card'},
    },
    {
      lit: {bottom: 8, right: 164, width: 49, height: 49},
      pad: [2, 188, 90, 70],
      afterTransition: '.kifi-dock-compose',
      arrow: {dx: 130, dy: 96, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: -80, gap: 10, sel: '.kifi-dock-compose'}},
      allow: {type: 'click', target: '.kifi-dock-compose', proceed: true},
      substep: true
    },
    {
      lit: {bottom: 64, right: 10, width: 302, height: 339},
      pad: [10, 0, 20],
      afterTransition: '.kifi-toast',
      arrow: {dx: 130, dy: 0, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: 0, gap: 5, sel: '.kifi-compose>.kifi-ti-list'}},
      allow: [
        {type: /^key/, target: '.kifi-compose input', unless: isEscOrEnterOnTip},
        {type: /^key/, target: '.kifi-compose-draft', unless: isEsc},
        {type: 'mousedown', target: '.kifi-ti-dropdown-item-token'}
      ]
    },
    {
      lit: {bottom: 64, right: 10, width: 302, height: 173},
      pad: [30, 0, 20],
      afterTransition: '.kifi-ti-dropdown',
      arrow: {dx: 360, dy: 70, from: {angle: -50, gap: 12, along: [.8, 1]}, to: {angle: 0, gap: 10, sel: '.kifi-compose-submit'}},
      allow: [
        {type: /^key/, target: '.kifi-compose input', unless: isEscOrEnterOnTip},
        {type: /^key/, target: '.kifi-compose-draft,.kifi-compose-submit', unless: isEsc},
        {type: /^(?:mouse|click)/, target: '.kifi-compose input,.kifi-ti-dropdown-item-token,.kifi-compose-draft,.kifi-compose-submit,.kifi-ti-token-x'}
      ],
      substep: true
    },
    {
      afterTransition: '.kifi-pane',
      litFor: 1000,
      pos: {bottom: 200, right: 400},
      transition: 'opacity'
    },
    {
      pos: 'center',
      transition: 'opacity'
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

  function show($guide, page) {
    if (!step) {
      k.tile.style.display = '';
      step = k.guide.step(steps, {
        $guide: $guide,
        page: page,
        index: 3,
        done: .2,
        anchor: 'br',
        opacity: .8,
        step: onStep,
        next: onClickNext,
        hide: onHide
      });
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
        observer.observe(k.tile, {childList: true});
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
          if (attrRemoved(records, 'href')) {
            observer.disconnect();
            observer = null;
            step.show(4);
          }
        });
        observer.observe(document.querySelector('.kifi-compose-submit'), {attributes: true, attributeFilter: ['href']});
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
      step.nav(e.target.href);
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

  function attrRemoved(records, name) {
    for (var i = 0; i < records.length; i++) {
      var rec = records[i];
      if (!rec.target.hasAttribute(name)) {
        return rec.target;
      }
    }
  }

  function isEsc(e) {
    return e.keyCode === 27;
  }

  function isEscOrEnterOnTip(e) {
    return e.keyCode === 27 || e.keyCode === 13 && $('.kifi-ti-dropdown-tip').hasClass('kifi-ti-dropdown-item-selected');
  }
}();
