// @require scripts/guide/step.js
// @require scripts/html/guide/step_1.js

k.guide.step1 = k.guide.step1 || function () {
  'use strict';
  var step, observer;
  var steps = [
    {
      lit: '.kifi-tile-card',
      pad: [20, 40],
      arrow: {dx: 121, dy: 87, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: -70, gap: 10}},
      allow: {type: 'mouseover', target: '.kifi-tile-keep,.kifi-tile-kept'}
    },
    {
      substep: true,
      lit: {bottom: 8, right: 7, width: 155, height: 49},
      pad: [10, 20, 50, 20],
      afterTransition: '.kifi-keep-btn',
      arrow: {dx: 130, dy: 96, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: -80, gap: 10, sel: '.kifi-keep-btn'}},
      allow: {type: 'click', target: '.kifi-keep-btn'}
    },
    {
      pad: [0],
      afterTransition: '.kifi-keep-box',
      arrow: {dx: 150, dy: 0, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: 0, gap: 5, sel: '.kifi-keep-box-lib.kifi-system.kifi-discoverable'}},
      allow: [
        {type: /^key/, target: '.kifi-keep-box-lib-input', unless: function (e) {
          return e.keyCode === 27 ||  // allow all keys except Esc (anywhere) and Enter (on Create New Library)
            (e.keyCode === 13 || e.keyCode === 108) && $(e.target).closest('.kifi-keep-box-view').find('.kifi-keep-box-lib.kifi-create').is('.kifi-highlighted');
        }},
        {type: /^(?:mouse|click$)/, target: '.kifi-keep-box-lib:not(.kifi-create)'}
      ]
    },
    {
      litFor: 3000,
      pad: [0, 0, 20],
      afterTransition: '.kifi-keep-box-cart',
      pos: {bottom: 280, right: 480},  // TODO: position relative to spotlight
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
        index: 1,
        done: .3,
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
      case 1:
        observer = new MutationObserver(function (records) {
          var box = elementAdded(records, 'kifi-keep-box');
          if (box) {
            observer.disconnect();
            observer = null;
            var r = box.parentNode.getBoundingClientRect();
            step.show(2, getTransitionDurationMs(window.getComputedStyle(box)), {
              left: r.left + box.offsetLeft,
              top: r.top + box.offsetTop,
              width: box.offsetWidth,
              height: box.offsetHeight
            });
          }
        });
        observer.observe(k.tile.querySelector('.kifi-keeper'), {childList: true});
        break;
      case 2:
        observer = new MutationObserver(function (records) {
          var view = elementAdded(records, 'kifi-keep-box-view-keep');
          if (view) {
            observer.disconnect();
            observer = null;
            var cart = view.parentNode;
            var vp = cart.parentNode;
            var box = $(vp).closest('.kifi-keep-box')[0];
            var r = box.parentNode.getBoundingClientRect();
            var pxTaller = view.offsetHeight - vp.offsetHeight;
            step.show(3, getTransitionDurationMs(window.getComputedStyle(cart)), {
              left: r.left + box.offsetLeft,
              top: r.top + box.offsetTop - pxTaller,
              width: box.offsetWidth,
              height: box.offsetHeight + pxTaller
            });
          }
        });
        observer.observe(k.tile.querySelector('.kifi-keep-box-cart'), {childList: true});
        break;
      case 4:
        api.port.emit('prime_search', 'g');
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
    if (stepIdx === 3) {
      e.closeKeeper = true;
      step.show(4);
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

  function getTransitionDurationMs(cs) {
    var dur = cs.transitionDuration.split(',')[0];
    return (~dur.indexOf('ms') ? 1 : 1000) * parseFloat(dur);
  }
}();
