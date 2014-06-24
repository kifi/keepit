// @require scripts/guide/step.js
// @require scripts/html/guide/step_1.js

guide.step1 = guide.step1 || function () {
  'use strict';
  var step, observer, tagId;
  var steps = [
    {
      lit: '.kifi-tile-card',
      pad: [20, 40],
      arrow: {dx: 121, dy: 87, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: -70, gap: 10}},
      allow: {type: 'mouseover', target: '.kifi-tile-keep'}
    },
    {
      substep: true,
      lit: '.kifi-keep-card',
      pad: [10, 20, 60, 60],
      arrow: {dx: 130, dy: 96, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: -80, gap: 10}},
      allow: {type: 'click', target: '.kifi-keep-btn', proceed: true}
    },
    {
      afterTransition: '.kifi-kept-side',
      lit: '.kifi-kept-tag',
      pad: [20, 20, 0, 120],
      arrow: {dx: 136, dy: 78, from: {angle: 0, gap: 10}, to: {angle: -75, gap: 10, along: [.5, 0], sel: '.kifi-kept-tag'}},
      allow: {type: 'click', target: '.kifi-kept-tag'}
    },
    {
      substep: true,
      lit: '.kifi-tagbox',
      pad: [0, 10, 20],
      arrow: {dx: 100, dy: 0, from: {angle: 0, gap: 12, along: [1, .55]}, to: {angle: 0, gap: 16, sel: '.kifi-tagbox-input-box'}},
      allow: [
        {type: /^key/, target: '.kifi-tagbox-input', unless: function (e) {return e.keyCode === 27}},  // esc
        {type: /^(?:mouse|click$)/, target: '.kifi-tagbox-suggestion'}
      ]
    },
    {
      afterTransition: '.kifi-tagbox-tagged-wrapper',
      lit: '.kifi-tagbox',
      pad: [0, 40, 0, 10],
      pos: {bottom: 280, right: 480},  // TODO: position relative to spotlight
      transition: 'opacity'
    },
    {
      pos: 'center',
      transition: 'opacity'
    }
  ];
  var origSteps3ArrowToSel = steps[3].arrow.to.sel;
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
      // TODO: handle already kept case well (different steps?)
      steps[3].arrow.to.sel = origSteps3ArrowToSel.replace('{{tag}}', page.tag);
      step = guide.step(steps, {
        $guide: $guide,
        page: page,
        pageIdx: pageIdx,
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
        observer = new MutationObserver(onTileChildChange);
        observer.observe(tile, {childList: true});
        break;
      case 4:
        var el = document.querySelector('.kifi-tagbox-tag');
        tagId = el && el.dataset.id;
        break;
      case 5:
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
    if (stepIdx === 4) {
      e.closeKeeper = true;
      step.show(5);
    } else {
      step.nav(e.target.href, tagId);
    }
  }

  function onTileChildChange(records) {
    var tagbox;
    if (elementAdded(records, 'kifi-keeper')) {
      step.show(1);
    } else if ((tagbox = elementAdded(records, 'kifi-tagbox'))) {
      var r1 = tagbox.getBoundingClientRect();
      var cs = window.getComputedStyle(tagbox);
      var bp = getBorderPlusPadding(cs);
      var w = bp.left + parseFloat(cs.width) + bp.right;
      var h = bp.top + parseFloat(cs.height) + bp.bottom;
      var r2 = {left: r1.right - w, top: r1.bottom - h, width: w, height: h};
      var elTo = tagbox.querySelector(steps[3].arrow.to.sel);
      var elToTop = r2.top + bp.top + elTo.offsetTop;
      var elToLeft = r2.left + bp.left + elTo.offsetLeft;
      step.show(3, getTransitionDurationMs(cs), r2, {
        top: elToTop,
        left: elToLeft,
        right: elToLeft + elTo.offsetWidth,
        bottom: elToTop + elTo.offsetHeight
      });

      observer.disconnect();
      observer = new MutationObserver(onTagboxClassChange);
      observer.observe(tagbox, {attributes: true, attributeFilter: ['class'], attributeOldValue: true});
    }
  }

  function onTagboxClassChange(records) {
    var tagbox;
    if ((tagbox = classAdded(records, 'kifi-tagged'))) {
      var el = tagbox.querySelector(steps[4].afterTransition);
      var cs = window.getComputedStyle(el);
      var h = 180;//getDeclaredHeight(cs);
      var ms = getTransitionDurationMs(cs);
      var r = tagbox.getBoundingClientRect();
      step.show(4, ms, {left: r.left, top: r.top - h, width: r.width, height: r.height + h});
      observer.disconnect();
      observer = null;
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

  function getBorderPlusPadding(cs) {
    return {
      top: parseFloat(cs.borderTopWidth) + parseFloat(cs.paddingTop),
      left: parseFloat(cs.borderLeftWidth) + parseFloat(cs.paddingLeft),
      right: parseFloat(cs.borderRightWidth) + parseFloat(cs.paddingRight),
      bottom: parseFloat(cs.borderBottomWidth) + parseFloat(cs.paddingBottom)
    };
  }

  function getTransitionDurationMs(cs) {
    var dur = cs.transitionDuration.split(',')[0];
    return (~dur.indexOf('ms') ? 1 : 1000) * parseFloat(dur);
  }
}();
