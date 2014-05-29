// @require styles/guide/step_1.css
// @require styles/guide/steps.css
// @require scripts/lib/jquery.js
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
    {lit: '.kifi-keep-card', pad: [10, 44], arrow: {}, req: [0]},
    {lit: '.kifi-keep-card', pad: [10, 44], arrow: {sel: '.kifi-kept-tag,.kifi-keep-tag', rot: 45}, req: [0]},
    {lit: '.kifi-tagbox.kifi-in', pad: [0, 10, 20], arrow: {sel: '.kifi-tagbox-suggestion', rot: 90}, req: [0, 2]}];
  var handlers = {
    kept: animateIntoPlace.bind(null, 2),
    add_tag: animateIntoPlace.bind(null, 4)
  };

  return show;

  function show() {
    if (!$stage) {
      tileObserver = new MutationObserver(onTileChildChange);
      tileObserver.observe(tile, {childList: true});
      api.port.on(handlers);

      spotlight = new Spotlight(wholeWindow(), {opacity: 0});
      $stage = $(render('html/guide/step_1', me));
      $p = $stage.find('.kifi-guide-p');
      $steps = $(render('html/guide/steps', {showing: true}));
      var ms = animateIntoPlace(0);
      $stage.css('transition-delay', Math.max(0, ms - 200) + 'ms');
      $steps.appendTo('body');
      $stage.appendTo('body').layout().addClass('kifi-open');
      $steps.find('.kifi-guide-steps-x').click(hide);
      $(document).data('esc').add(hide);
      $(window).on('resize.guideStep1', animateIntoPlace.bind(null, null, null, null, 1));
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

  function animateIntoPlace(idx, el, r, ms) {
    if (pIdx !== idx || idx == null) {
      if (idx != null) {
        if (stepIdx == null || stepIdx < idx) {
          stepIdx = idx;
        }
        pIdx = idx;
      }
      var step = steps[stepIdx], p = steps[pIdx];
      var lit = document.querySelector(p.lit);
      if (lit) {
        $stage.attr({'kifi-step': stepIdx, 'kifi-p': pIdx});
        if (!r || lit !== el) {
          r = lit.getBoundingClientRect();
        }
        log('[animateIntoPlace]', stepIdx, pIdx, r);
        ms = spotlight.animateTo({
            x: r.left - p.pad[1],
            y: r.top - p.pad[0],
            w: r.width + p.pad[1] * 2,
            h: r.height + p.pad[0] + p.pad[p.pad.length > 2 ? 2 : 0]
          }, {opacity: 1, ms: ms});

        return ms;
      } else {
        log('[animateIntoPlace]', stepIdx, pIdx, 'hiding');
        return hide();
      }
    }
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
      animateIntoPlace(1);
    }
    // TODO: disconnect and release keeperObserver if .kifi-keeper element removed
    // TODO: disconnect and release tagboxObserver if .kifi-tagbox element removed
  }

  function onTagboxChange(records) {
    log('[onTagboxChange]', records);
    if (this.classList.contains('kifi-in')) {
      var cs = window.getComputedStyle(this);
      var w = parseFloat(cs.width) + parseFloat(cs.borderLeftWidth) + parseFloat(cs.borderRightWidth);
      var h = parseFloat(cs.height) + parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth);
      var dur = cs.transitionDuration.split(',')[0];
      var ms = (~dur.indexOf('ms') ? 1 : 1000) * parseFloat(dur);  // TODO: check whether a transition is in effect before using ms
      var r = this.getBoundingClientRect();
      log('[onTagboxChange]', records, w, 'x', h, 'right:', r.right, 'bottom:', r.bottom, 'ms:', ms);
      animateIntoPlace(3, this, {left: r.right - w, top: r.bottom - h, width: w, height: h}, ms);
    } else {
      log('[onTagboxChange]', records);
      animateIntoPlace(2);
    }
  }

  function onKeeperChange(records) {
    log('[onKeeperChange]', records);
    if (this.classList.contains('kifi-hiding')) {
      animateIntoPlace(0);
    } else {
      // animateIntoPlace();
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
