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
  var $stage, $steps, spotlight, arrow, tileObserver, keeperObserver, tagboxObserver, stepIdx, pIdx;
  var steps = [
    {lit: '.kifi-tile-card', pad: [20, 40], arrow: {angle: -70}},
    {lit: '.kifi-keep-card', pad: [10, 20, 60, 60], arrow: {angle: -90}},
    {lit: '.kifi-keep-card', pad: [10, 20, 60, 60], arrow: {sel: '.kifi-kept-tag,.kifi-keep-tag', angle: -45}},
    {lit: '.kifi-tagbox.kifi-in', pad: [0, 10, 20], arrow: {sel: '.kifi-tagbox-suggestion', angle: 0}},
    {lit: '.kifi-tagbox.kifi-in', pad: [0, 10, 20]}];
  var handlers = {
    kept: showStep.bind(null, 2),
    add_tag: showStep.bind(null, 4)
  };

  return show;

  function show() {
    if (!$stage) {
      tileObserver = new MutationObserver(onTileChildChange);
      tileObserver.observe(tile, {childList: true});
      api.port.on(handlers);

      spotlight = new Spotlight(wholeWindow(), {opacity: 0, maxOpacity: .85});
      $stage = $(render('html/guide/step_1', me)).appendTo('body');
      $steps = $(render('html/guide/steps', {showing: true})).appendTo('body');
      showStep(0);
      $steps.find('.kifi-guide-steps-x').click(hide);
      $(document).data('esc').add(hide);
      $(window).on('resize.guideStep1', showStep.bind(null, null, null, null, 1));
    }
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).css('transition-delay', '').removeClass('kifi-open');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      var ms = spotlight.animateTo(wholeWindow(), {opacity: 0, detach: true});
      arrow.fadeAndDetach(ms);
      tileObserver.disconnect();
      if (keeperObserver) keeperObserver.disconnect();
      if (tagboxObserver) tagboxObserver.disconnect();
      api.port.off(handlers);

      $stage = $steps = spotlight = arrow = tileObserver = keeperObserver = tagboxObserver = stepIdx = pIdx = null;
      $(document).data('esc').remove(hide);
      $(window).off('resize.guideStep1');
      return ms;
    } else {
      return 0;
    }
  }

  function showStep(idx, el, r, ms) {
    var stepIdxNew = stepIdx == null || idx > stepIdx ? idx : stepIdx;
    var pIdxNew = idx != null ? idx : pIdx;
    var step = steps[stepIdxNew];
    var p = steps[pIdxNew];
    var lit = document.querySelector(p.lit);
    if (!r || lit !== el) {
      r = lit.getBoundingClientRect();
    }
    log('[showStep] step:', stepIdx, '=>', stepIdxNew, 'p:', pIdx, '=>', pIdxNew, r);
    var t0 = Date.now();
    var padT = p.pad[0];
    var padR = p.pad.length > 1 ? p.pad[1] : padT;
    var padB = p.pad.length > 2 ? p.pad[2] : padT;
    var padL = p.pad.length > 3 ? p.pad[3] : padR;
    ms = spotlight.animateTo({
      x: r.left - padL,
      y: r.top - padT,
      w: r.width + padL + padR,
      h: r.height + padT + padB
    }, {opacity: 1, ms: ms});

    var hidePromise = stepIdxNew !== stepIdx && stepIdx ? hideStep() : null;

    if (stepIdx == null || hidePromise) {
      Q.when(hidePromise, function () {
        showNewStep(stepIdxNew, ms - (Date.now() - t0));
      });
    } else if (pIdxNew !== pIdx) {
      switchP(pIdxNew, ms);
    }

    return ms;
  }

  function showNewStep(idx, ms) {
    stepIdx = pIdx = idx;
    $stage
      .attr({'kifi-step': stepIdx, 'kifi-p': pIdx})
      .css('transition-delay', Math.max(0, ms - 200) + 'ms')
      .on('transitionend', function end(e) {
        if (e.target === this) {
          $(this).off('transitionend', end);
          var p = steps[pIdx];
          var arr = p.arrow;
          if (arr) {
            var elTail = this.querySelector('.kifi-p' + pIdx);
            var elHead = document.querySelector(arr.sel || p.lit);
            arrow = new SvgArrow(elTail, elHead, 0, arr.angle, 600);
          }
        }
      })
      .layout().addClass('kifi-open');
  }

  function switchP(idx, ms) {
    stepIdx = Math.max(stepIdx, idx);
    pIdx = idx;
    $stage
      .attr({'kifi-step': stepIdx, 'kifi-p': pIdx})
      // .css('transition-delay', Math.max(0, ms - 200) + 'ms')
      // .layout().addClass('kifi-open');
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
      arrow.fadeAndDetach(0);
      arrow = null;
    }
    return deferred.promise;
  }

  function onTileChildChange(records) {
    log('[onTileChildChange]', stepIdx, records);
    var tagbox, keeper;
    if ((tagbox = elementAdded(records, 'kifi-tagbox'))) {
      spotOnTagbox(tagbox);
      tagboxObserver = new MutationObserver(onTagboxChange);
      tagboxObserver.observe(tagbox, {attributes: true, attributeFilter: ['class'], attributeOldValue: true});
    } else if ((keeper = elementAdded(records, 'kifi-keeper'))) {
      var onKeeperChangeDebounced = _.debounce(onKeeperChange.bind(keeper), 20, true);
      keeperObserver = new MutationObserver(onKeeperChangeDebounced);
      keeperObserver.observe(keeper, {attributes: true, attributeFilter: ['class']});
      // onKeeperChangeDebounced();
      showStep(stepIdx > 1 ? 2 : 1);
    }
    // TODO: disconnect and release keeperObserver if .kifi-keeper element removed
    // TODO: disconnect and release tagboxObserver if .kifi-tagbox element removed
  }

  function onTagboxChange(records) {
    log('[onTagboxChange]', records);
    if (stepIdx >= 3 && classAdded(records, 'kifi-tagged') || classRemoved(records, 'kifi-tagged')) {
      // TODO: inspect .kifi-tagbox-tagged-wrapper’s transition to compute new height and its transition duration
    } else if (classRemoved(records, 'kifi-in')) {
      if (stepIdx < 4) {
        showStep(2);
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

  function onKeeperChange(records) {
    log('[onKeeperChange]', records);
    if (this.classList.contains('kifi-hiding')) {
      if (stepIdx < 4) {
        showStep(0);
      }
    } else {
      // showStep();
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

  function wholeWindow() {
    return {x: 0, y: 0, w: window.innerWidth, h: window.innerHeight};
  }

  function remove() {
    $(this).remove();
  }
}();
