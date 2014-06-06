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
  var $stage, $steps, spotlight, arrow, tileObserver, tagboxObserver, stepIdx;
  var steps = [
    {lit: 'kifi-tile-card', pad: [20, 40], arrow: {angle: -70}, ev: {type: 'mouseover', target: 'kifi-tile-keep'}},
    {lit: 'kifi-keep-card', pad: [10, 20, 60, 60], arrow: {angle: -90}, ev: {type: 'click', target: 'kifi-keep-btn'}, substep: true},
    {lit: null, afterTransition: '.kifi-kept-side', arrow: {angle: -60, to: 'kifi-kept-tag'}, ev: {type: 'click', target: 'kifi-kept-tag'}},
    {lit: 'kifi-tagbox', pad: [0, 10, 20], arrow: {angle: 0, to: 'kifi-tagbox-suggestion'}},
    {lit: 'kifi-tagbox', pad: [0, 10, 20]}];
  var eventsToScreen = 'mouseover mouseout mouseenter mouseleave mousedown mouseup click mousewheel wheel dragstart drag dragstop keydown keypress keyup'.split(' ');
  return show;

  function show() {
    if (!$stage) {
      tileObserver = new MutationObserver(onTileChildChange);
      tileObserver.observe(tile, {childList: true});

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
      arrow.fadeAndDetach(ms);
      tileObserver.disconnect();
      if (tagboxObserver) tagboxObserver.disconnect();

      $stage = $steps = spotlight = arrow = tileObserver = tagboxObserver = stepIdx = null;
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
      });
    }
  }

  function showNewStep(msToEarliestCompletion) {
    $stage
      .attr({'kifi-step': stepIdx, 'kifi-p': stepIdx})
      .css('transition-delay', Math.max(0, msToEarliestCompletion - 200) + 'ms')
      .on('transitionend', function end(e) {
        if (e.target === this) {
          $(this).off('transitionend', end);
          var step = steps[stepIdx];
          var arr = step.arrow;
          if (arr) {
            var elTail = this.querySelector('.kifi-p' + stepIdx);
            var elHead = document.getElementsByClassName(arr.to || step.lit)[0];
            arrow = new SvgArrow(elTail, elHead, 0, arr.angle, stepIdx === 0 ? 600 : 400);
          }
        }
      })
      .layout().addClass('kifi-open');
  }

  function showSubstep(msToEarliestCompletion) {
    $stage.attr('kifi-p', stepIdx);
    var step = steps[stepIdx];
    var arr = step.arrow;
    var elTail = $stage[0].querySelector('.kifi-p' + stepIdx);
    var elHead = document.getElementsByClassName(arr.to || step.lit)[0];
    arrow.animateTo(elTail, elHead, 0, arr.angle, Math.max(200, msToEarliestCompletion));
  }

  function hideStep() {
    var deferred = Q.defer();
    $stage.on('transitionend', function end(e) {
      if (e.target === this) {
        $stage.off('transitionend', end).removeClass('kifi-open kifi-done');
        deferred.resolve();
      }
    }).addClass('kifi-done');
    if (arrow) {
      arrow.fadeAndDetach(0); // TODO
      arrow = null;
    }
    return deferred.promise;
  }

  function animateSpotlightTo(step, el, r, ms) {
    step = step || steps[stepIdx];
    var lit = document.getElementsByClassName(step.lit)[0];
    if (!r || lit && lit !== el) {
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
    log('[onTileChildChange]', stepIdx, records);
    var tagbox, keeper;
    if ((tagbox = elementAdded(records, 'kifi-tagbox'))) {
      spotOnTagbox(tagbox);
      tagboxObserver = new MutationObserver(onTagboxChange);
      tagboxObserver.observe(tagbox, {attributes: true, attributeFilter: ['class'], attributeOldValue: true});
    } else if ((keeper = elementAdded(records, 'kifi-keeper'))) {
      showStep(1);
    }
    // TODO: disconnect and release tagboxObserver if .kifi-tagbox element removed
  }

  function onTagboxChange(records) {
    log('[onTagboxChange]', records);
    if (stepIdx >= 3 && classAdded(records, 'kifi-tagged') || classRemoved(records, 'kifi-tagged')) {
      // TODO: inspect .kifi-tagbox-tagged-wrapper’s transition to compute new height and its transition duration
    } else if (classRemoved(records, 'kifi-in')) {
      if (stepIdx < 4) {
        // showStep(2);
      } else {
        spotlight.animateTo({x: window.innerWidth - 32, y: window.innerHeight - 32, w: 0, h: 0}, {});
      }
    }
  }

  function spotOnTagbox(tagbox) {
    var cs = window.getComputedStyle(tagbox);
    var w = parseFloat(cs.width) + parseFloat(cs.borderLeftWidth) + parseFloat(cs.borderRightWidth);
    var h = parseFloat(cs.height) + parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth);
    var dur = cs.transitionDuration.split(',')[0];
    var ms = (~dur.indexOf('ms') ? 1 : 1000) * parseFloat(dur);  // TODO: check whether a transition is in effect before using ms
    var r = tagbox.getBoundingClientRect();
    log('[spotOnTagbox]', w, 'x', h, 'right:', r.right, 'bottom:', r.bottom, 'ms:', ms);
    showStep(Math.max(3, stepIdx), tagbox, {left: r.right - w, top: r.bottom - h, width: w, height: h}, ms);
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
        return true;
      }
    }
  }

  function classRemoved(records, cssClass) {
    for (var i = 0; i < records.length; i++) {
      var rec = records[i];
      if (!rec.target.classList.contains(cssClass) && ~rec.oldValue.split(' ').indexOf(cssClass)) {
        return true;
      }
    }
  }

  function screenEvent(e) {
    var step = stepIdx != null && steps[stepIdx];
    if (step && e.type === step.ev.type && e.target.classList.contains(step.ev.target)) {
      if (stepIdx === 1) {
        showStep(2);
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
