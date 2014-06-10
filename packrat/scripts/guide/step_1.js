// @require styles/guide/step_1.css
// @require styles/guide/steps.css
// @require scripts/lib/jquery.js
// @require scripts/lib/q.min.js
// @require scripts/lib/underscore.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/guide/spotlight.js
// @require scripts/guide/svg_arrow.js
// @require scripts/html/guide/step_1.js
// @require scripts/html/guide/steps.js

var guide = guide || {};
guide.step1 = guide.step1 || function () {
  var $stage, $steps, spotlight, arrow, observer, stepIdx, animTick;
  var steps = [
    {
      lit: '.kifi-tile-card',
      pad: [20, 40],
      arrow: {from: {angle: 0, gap: 10}, to: {angle: -70, gap: 10}},
      ev: {type: 'mouseover', target: '.kifi-tile-keep'},
      pos: {bottom: 150, right: 160}
    },
    {
      lit: '.kifi-keep-card',
      pad: [10, 20, 60, 60],
      arrow: {from: {angle: 0, gap: 10}, to: {angle: -90, gap: 10}},
      ev: {type: 'click', target: '.kifi-keep-btn', proceed: true},
      substep: {arrow: 'move'}
    },
    {
      lit: null,
      afterTransition: '.kifi-kept-side',
      arrow: {from: {angle: 0, gap: 10}, to: {angle: -70, gap: 10, sel: '.kifi-kept-tag'}},
      ev: {type: 'click'}
    },
    {
      lit: '.kifi-tagbox',
      pad: [0, 10, 20],
      arrow: {from: {angle: -90, gap: 16}, to: {angle: 0, gap: 16, sel: '.kifi-tagbox-suggestion[data-name=Recipe]'}},
      ev: {type: 'click', target: '.kifi-tagbox-suggestion', allow: 'mouse'},
      substep: {arrow: 'new'},
      pos: {bottom: 230, right: 400}
    },
    {
      lit: '.kifi-tagbox',
      afterTransition: '.kifi-tagbox-tagged-wrapper',
      pad: [0, 40, 0, 10],
      ev: {},
      pos: {bottom: 370, right: 500}
    }
  ];
  var eventsToScreen = 'mouseover mouseout mouseenter mouseleave mousedown mouseup click mousewheel wheel keydown keypress keyup'.split(' ');
  var MATCHES = 'mozMatchesSelector' in document.body ? 'mozMatchesSelector' : 'webkitMatchesSelector';
  return show;

  function show() {
    if (!$stage) {
      observer = new MutationObserver(onTileChildChange);
      observer.observe(tile, {childList: true});

      spotlight = new Spotlight(wholeWindow(), {opacity: 0, maxOpacity: .85});
      $stage = $(render('html/guide/step_1', me)).appendTo('body');
      $steps = $(render('html/guide/steps', {showing: true})).appendTo('body');
      showStep(0);  // TODO: figure out which step to start on (in case already kept)
      $steps.find('.kifi-guide-steps-x').click(hide);
      $(window).on('resize.guideStep1', animateSpotlightTo.bind(null, null, null, null, 1));
      eventsToScreen.forEach(function (type) {
        window.addEventListener(type, screenEvent, true);
      });
    }
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).css('transition-delay', '').removeClass('kifi-open');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      var ms = spotlight.animateTo(wholeWindow(), {opacity: 0, detach: true});
      if (arrow) {
        arrow.fadeAndDetach(ms);
      }
      if (observer) {
        observer.disconnect();
      }

      $stage = $steps = spotlight = arrow = observer = stepIdx = null;
      $(window).off('resize.guideStep1');
      eventsToScreen.forEach(function (type) {
        window.removeEventListener(type, screenEvent, true);
      });
      return ms;
    } else {
      return 0;
    }
  }

  function showStep(idx, el, r, ms) {
    if (idx > stepIdx || stepIdx == null) {
      log('[showStep] step:', stepIdx, '=>', idx);
      var step = steps[idx];
      var t0 = Date.now();
      ms = step.lit ? animateSpotlightTo(step, el, r, ms) : (ms || 0);

      var promises = stepIdx != null && !step.substep ? [hideStep()] : [];
      if (step.afterTransition) {
        $(step.afterTransition).on('transitionend', function end(e) {
          if (e.target === this) {
            $(this).off('transitionend', end);
            afterTransitionDeferred.resolve();
          }
        });
        var afterTransitionDeferred = Q.defer();
        promises.push(afterTransitionDeferred.promise);
      }

      Q.all(promises).then(function () {
        ms -= Date.now() - t0;
        stepIdx = idx;
        if (step.substep) {
          showSubstep(ms);
        } else {
          showNewStep(ms);
        }
      }).fail(function (err) {
        console.error(err);
      });
    }
  }

  function showNewStep(msToEarliestCompletion) {
    var step = steps[stepIdx];
    $stage
      .attr('kifi-step', stepIdx)
      .css(step.pos || {})
      .data('pos', step.pos || $stage.data('pos'))
      .css({
        'transition-delay': Math.max(0, msToEarliestCompletion - 200) + 'ms',
        'transition-duration': ''
      })
      .on('transitionend', function end(e) {
        if (e.target === this) {
          $(this).off('transitionend', end).css('transition-delay', '');
          var arr = step.arrow;
          if (arr) {
            var tail = $.extend({el: this.querySelector('.kifi-p' + stepIdx).firstElementChild}, arr.from);
            var head = $.extend({el: $(arr.to.sel || step.lit)[0]}, arr.to);
            arrow = new SvgArrow(tail, head, stepIdx === 0 ? 600 : 400);
          }
        }
      })
      .layout()
      .addClass('kifi-open')
  }

  // animates stage and arrow to new position and cross-fades to new action prompt
  function showSubstep(msToEarliestCompletion) {
    var step = steps[stepIdx];
    var arr = step.arrow;
    var pos = $stage.data('pos');
    var $pOld = $stage.find('.kifi-p' + (stepIdx - 1)).css({display: 'block', position: 'absolute', width: '100%'});
    var $pNew = $stage.find('.kifi-p' + stepIdx).css('opacity', 0);
    var ms = Math.max(200, msToEarliestCompletion);

    $stage.attr('kifi-step', stepIdx);
    if (step.pos) {
      $stage.data('pos', step.pos);
      var tfm = {x: 0, y: 0};
      for (var k in pos) {
        tfm[k === 'left' || k === 'right' ? 'x' : 'y'] =
           (k === 'left' || k === 'top' ? 1 : -1) * (step.pos[k] - pos[k]);
      }
      $stage.css({
        'transition-duration': ms + 'ms',
        'transform': ['translate(', tfm.x, 'px,', tfm.y, 'px)'].join('')
      });
    }

    var tail = $.extend({el: $pNew[0].firstElementChild}, arr.from);
    var head = $.extend({el: $(arr.to.sel || step.lit)[0]}, arr.to);
    if (step.substep.arrow === 'move') {
      arrow.animateTo(tail, head, ms);
    } else {
      arrow.fadeAndDetach(100);
      arrow = null;
    }

    var ms_1 = 1 / ms;
    var t0 = window.performance.now();
    var tN = t0 + ms;
    var tick = animTick = function (t) {
      if (animTick === tick) {
        var alpha = t < tN ? t > t0 ? (t - t0) * ms_1 : 0 : 1;
        var alpha_1 = 1 - alpha;
        if (t < tN) {
          $pOld.css('opacity', alpha_1);
          $pNew.css('opacity', alpha);
          window.requestAnimationFrame(tick);
        } else {
          animTick = null;
          $pOld.add($pNew).removeAttr('style');
          if (!arrow) {
            arrow = new SvgArrow(tail, head, 400);
          }
        }
      }
    };
    window.requestAnimationFrame(tick);
  }

  function hideStep() {
    var deferred = Q.defer();
    $stage.on('transitionend', function end(e) {
      if (e.target === this) {
        $stage.off('transitionend', end)
          .removeClass('kifi-open kifi-done')
          .css({'transform': '', 'transition-duration': ''});
        deferred.resolve();
      }
    }).addClass('kifi-done');
    if (arrow) {
      arrow.fadeAndDetach($stage.css('transition-duration'));
      arrow = null;
    }
    return deferred.promise;
  }

  function animateSpotlightTo(step, el, r, ms) {
    if (!step) {
      var i = stepIdx;
      do {
        step = steps[i--];
      } while (!step.lit);
    }
    var lit = $(step.lit)[0];
    if (!r || lit !== el) {
      r = lit.getBoundingClientRect();
    }
    var padT = step.pad[0];
    var padR = step.pad.length > 1 ? step.pad[1] : padT;
    var padB = step.pad.length > 2 ? step.pad[2] : padT;
    var padL = step.pad.length > 3 ? step.pad[3] : padR;
    return spotlight.animateTo({
      x: r.left - padL,
      y: r.top - padT,
      w: r.width + padL + padR,
      h: r.height + padT + padB
    }, {opacity: 1, ms: ms});
  }

  function onTileChildChange(records) {
    var tagbox;
    if (elementAdded(records, 'kifi-keeper')) {
      showStep(1);
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
      showStep(3, tagbox, {left: r.right - w, top: r.bottom - h, width: w, height: h}, ms);

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
      showStep(4, tagbox, {left: r.left, top: r.top - h, width: r.width, height: r.height + h}, ms);
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

  function screenEvent(e) {
    var step = stepIdx != null && steps[stepIdx];
    if (step && (e.type === step.ev.type || step.ev.allow && e.type.lastIndexOf(step.ev.allow, 0) === 0) &&
        e.target[MATCHES](step.ev.target || step.arrow.to.sel)) {
      // do not interfere
      if (e.type === step.ev.type && step.ev.proceed) {
        showStep(stepIdx + 1);
      }
    } else if (!e.target.namespaceURI && e.target.className.lastIndexOf('kifi-guide', 0) === 0) {
      // do not interfere with guide
    } else if (e.type === 'keydown') {
      if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {
        hide();
      } else if ((e.metaKey || e.ctrlKey) && e.shiftKey && ~[75,79,83].indexOf(e.keyCode)) {  // kifi shortcuts
        e.stopImmediatePropagation();
      }
    } else {
      if (e.type !== 'mousedown') {
        e.preventDefault();
      }
      e.stopImmediatePropagation();
    }
  }

  function wholeWindow() {
    return {x: 0, y: 0, w: window.innerWidth, h: window.innerHeight};
  }

  function remove() {
    $(this).remove();
  }
}();
