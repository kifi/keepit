// @require scripts/guide/step.js
// @require scripts/html/guide/step_1.js

guide.step1 = guide.step1 || function () {
  'use strict';
  var step, observer;
  var steps = [
    {
      lit: '.kifi-tile-card',
      pad: [20, 40],
      arrow: {from: {angle: 0, gap: 10, along: [1, .55]}, to: {angle: -70, gap: 10}},
      allow: {type: 'mouseover', target: '.kifi-tile-keep'},
      pos: {bottom: 150, right: 160}
    },
    {
      lit: '.kifi-keep-card',
      pad: [10, 20, 60, 60],
      arrow: {from: {angle: 0, gap: 10}, to: {angle: -90, gap: 10}},
      allow: {type: 'click', target: '.kifi-keep-btn', proceed: true},
      substep: {arrow: 'move'}
    },
    {
      afterTransition: '.kifi-kept-side',
      arrow: {from: {angle: 0, gap: 10}, to: {angle: -70, gap: 10, sel: '.kifi-kept-tag'}},
      allow: {type: 'click', target: '.kifi-kept-tag'}
    },
    {
      lit: '.kifi-tagbox',
      pad: [0, 10, 20],
      arrow: {from: {angle: -90, gap: 16}, to: {angle: 0, gap: 16, sel: '.kifi-tagbox-suggestion[data-name="{{tag}}"]'}},
      allow: [
        {type: 'click', target: '.kifi-tagbox-suggestion'},
        {type: /^mouse/, target: '.kifi-tagbox-suggestion'}
      ],
      substep: {arrow: 'new'},
      pos: {bottom: 230, right: 400}
    },
    {
      lit: '.kifi-tagbox',
      afterTransition: '.kifi-tagbox-tagged-wrapper',
      pad: [0, 40, 0, 10],
      pos: {bottom: 370, right: 500},
      transition: 'opacity'
    },
    {
      pad: [0],
      pos: {top: '20%', left: 'auto', right: 'auto'}
    }
  ];
  var steps_3_arrow_to_sel = steps[3].arrow.to.sel;
  return show;

  function show(siteIdx) {
    if (!step) {
      // TODO: handle already kept case well (different steps)
      step = guide.step(steps, {site: siteIdx, page: 1, anchor: 'br', step: onStep, next: onClickNext, hide: onHide});
      steps[3].arrow.to.sel = steps_3_arrow_to_sel.replace('{{tag}}', step.site.tag);
      step.show(0);
    }
  }

  function onStep(stepIdx) {
    switch (stepIdx) {
      case 0:
        observer = new MutationObserver(onTileChildChange);
        observer.observe(tile, {childList: true});
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
      step.show(5, {left: window.innerWidth - 31, top: window.innerHeight - 31, width: 0, height: 0});
    } else {
      step.nav(e.target.href);
    }
  }

  function onTileChildChange(records) {
    var tagbox;
    if (elementAdded(records, 'kifi-keeper')) {
      step.show(1);
    } else if ((tagbox = elementAdded(records, 'kifi-tagbox'))) {
      var recipeTag = tagbox.querySelector(steps[3].arrow.to.sel);
      if (recipeTag) {
        var fifthTag = recipeTag.parentNode.children[5];
        if (recipeTag !== fifthTag) {
          recipeTag.parentNode.insertBefore(recipeTag, fifthTag);
        }
      }
      var r = tagbox.getBoundingClientRect();
      var cs = window.getComputedStyle(tagbox);
      var w = getDeclaredWidth(cs);
      var h = getDeclaredHeight(cs);
      var ms = getTransitionDurationMs(cs);
      step.show(3, {left: r.right - w, top: r.bottom - h, width: w, height: h}, ms);

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
      step.show(4, {left: r.left, top: r.top - h, width: r.width, height: r.height + h}, ms);
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

  function getDeclaredWidth(cs) {
    return parseFloat(cs.borderLeftWidth) + parseFloat(cs.paddingLeft) + parseFloat(cs.width) + parseFloat(cs.paddingRight) + parseFloat(cs.borderRightWidth);
  }

  function getDeclaredHeight(cs) {
    return parseFloat(cs.borderTopWidth) + parseFloat(cs.paddingTop) + parseFloat(cs.height) + parseFloat(cs.paddingBottom) + parseFloat(cs.borderBottomWidth);
  }

  function getTransitionDurationMs(cs) {
    var dur = cs.transitionDuration.split(',')[0];
    return (~dur.indexOf('ms') ? 1 : 1000) * parseFloat(dur);
  }
}();
