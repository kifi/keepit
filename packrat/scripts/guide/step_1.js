// @require styles/guide/step_1.css
// @require styles/guide/steps.css
// @require scripts/lib/jquery.js
// @require scripts/lib/q.min.js
// @require scripts/lib/underscore.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/guide/spotlight.js
// @require scripts/html/guide/step_1.js
// @require scripts/html/guide/steps.js

var guide = guide || {};
guide.step1 = guide.step1 || function () {
  var $stage, $p, $steps, spotlight, tileObserver, keeperObserver, tagboxObserver, stepIdx, pIdx;
  var steps = [
    {lit: '.kifi-tile-card', pad: [20, 40], arrow: {}},
    {lit: '.kifi-keep-card', pad: [10, 44], arrow: {}},
    {lit: '.kifi-keep-card', pad: [10, 44], arrow: {sel: '.kifi-kept-tag,.kifi-keep-tag', rot: 45}},
    {lit: '.kifi-tagbox.kifi-in', pad: [0, 10, 20], arrow: {sel: '.kifi-tagbox-suggestion', rot: 90}},
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

      spotlight = new Spotlight(wholeWindow(), {opacity: 0});
      $stage = $(render('html/guide/step_1', me)).appendTo('body');
      $p = $stage.find('.kifi-guide-p');
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
      tileObserver.disconnect();
      if (keeperObserver) keeperObserver.disconnect();
      if (tagboxObserver) tagboxObserver.disconnect();
      api.port.off(handlers);

      $stage = $p = $steps = spotlight = tileObserver = keeperObserver = tagboxObserver = stepIdx = pIdx = null;
      $(document).data('esc').remove(hide);
      $(window).off('resize.guideStep1');
      return ms;
    } else {
      return 0;
    }
  }

  function showStep(idx, el, r, ms) {
    if (pIdx !== idx) {
      var pIdxOrig = pIdx;
      var stepIdxOrig = stepIdx;

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
      ms = spotlight.animateTo({
          x: r.left - p.pad[1],
          y: r.top - p.pad[0],
          w: r.width + p.pad[1] * 2,
          h: r.height + p.pad[0] + p.pad[p.pad.length > 2 ? 2 : 0]
        }, {opacity: 1, ms: ms});

      var hidePromise = stepIdxNew !== stepIdxOrig && stepIdxOrig ? hideStep() : null;

      if (stepIdxOrig == null || hidePromise) {
        Q.when(hidePromise, function () {
          showNewStep(stepIdxNew, ms - (Date.now() - t0));
        });
      } else if (pIdxNew !== pIdxOrig) {
        switchP(pIdxNew, ms);
      }

      return ms;
      // } else {
      //   log('[showStep]', stepIdx, pIdx, 'hiding');
      //   return hide();
      // }
    }
  }

  function showNewStep(idx, ms) {
    stepIdx = pIdx = idx;
    $stage
      .attr({'kifi-step': stepIdx, 'kifi-p': pIdx})
      .css('transition-delay', Math.max(0, ms - 200) + 'ms')
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
    return deferred.promise;
  }

  function onTileChildChange(records) {
    log('[onTileChildChange]', stepIdx, records);
    var tagbox, keeper;
    if ((tagbox = added(records, 'kifi-tagbox'))) {
      var onTagboxChangeDebounced = _.debounce(onTagboxChange.bind(tagbox), 20, true);
      tagboxObserver = new MutationObserver(onTagboxChangeDebounced);
      tagboxObserver.observe(tagbox, {attributes: true});
      onTagboxChangeDebounced();
    } else if ((keeper = added(records, 'kifi-keeper'))) {
      var onKeeperChangeDebounced = _.debounce(onKeeperChange.bind(keeper), 20, true);
      keeperObserver = new MutationObserver(onKeeperChangeDebounced);
      keeperObserver.observe(keeper, {attributes: true});
      // onKeeperChangeDebounced();
      showStep(stepIdx > 1 ? 2 : 1);
    }
    // TODO: disconnect and release keeperObserver if .kifi-keeper element removed
    // TODO: disconnect and release tagboxObserver if .kifi-tagbox element removed
  }

  function onTagboxChange(records) {
    log('[onTagboxChange]', records);
    if (this.classList.contains('kifi-tagged')) {
      // TODO: inspect .kifi-tagbox-tagged-wrapper’s transition to compute new height and its transition duration
    } else if (this.classList.contains('kifi-in')) {
      var cs = window.getComputedStyle(this);
      var w = parseFloat(cs.width) + parseFloat(cs.borderLeftWidth) + parseFloat(cs.borderRightWidth);
      var h = parseFloat(cs.height) + parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth);
      var dur = cs.transitionDuration.split(',')[0];
      var ms = (~dur.indexOf('ms') ? 1 : 1000) * parseFloat(dur);  // TODO: check whether a transition is in effect before using ms
      var r = this.getBoundingClientRect();
      log('[onTagboxChange]', records, w, 'x', h, 'right:', r.right, 'bottom:', r.bottom, 'ms:', ms);
      showStep(Math.max(3, stepIdx), this, {left: r.right - w, top: r.bottom - h, width: w, height: h}, ms);
    } else {
      log('[onTagboxChange]', records);
      showStep(2);
    }
  }

  function onKeeperChange(records) {
    log('[onKeeperChange]', records);
    if (this.classList.contains('kifi-hiding')) {
      showStep(0);
    } else {
      // showStep();
    }
  }

  function added(records, cssClass) {
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

  function wholeWindow() {
    return {x: 0, y: 0, w: window.innerWidth, h: window.innerHeight};
  }

  function remove() {
    $(this).remove();
  }
}();
