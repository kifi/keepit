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
  var $stage, $steps, spotlight, tileObserver, tagboxObserver, completed;
  var steps = [
    {lit: '.kifi-tile-card', pad: [20, 40]},
    {lit: '.kifi-keep-card', pad: [0, 44], req: [0]},
    {lit: '.kifi-keep-card', pad: [0, 44], req: [0], arrow: '.kifi-kept-tag,.kifi-keep-tag'},
    {lit: '.kifi-tagbox', pad: [0, 10, 20], req: [0, 2]}];
  var handlers = {
    kept: complete.bind(null, 2),
    add_tag: complete.bind(null, 4)
  };

  return show;

  function show() {
    if (!$stage) {
      completed = 0;
      tileObserver = new MutationObserver(onTileChildChange);
      tileObserver.observe(tile, {childList: true});
      api.port.on(handlers);

      spotlight = new Spotlight(wholeWindow(), {opacity: 0});
      $stage = $(render('html/guide/step_1', me));
      $steps = $(render('html/guide/steps', {showing: true}));
      var ms = updateSpotlightPosition();
      $stage.css('transition-delay', Math.max(0, ms - 200) + 'ms');
      $steps.appendTo('body');
      $stage.appendTo('body').layout().addClass('kifi-open');
      $steps.find('.kifi-guide-steps-x').click(hide);
      $(document).data('esc').add(hide);
      $(window).on('resize', updateSpotlightPosition);
    }
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).css('transition-delay', '').removeClass('kifi-open');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      var ms = spotlight.animateTo(wholeWindow(), {opacity: 0, detach: true});
      tileObserver.disconnect();
      if (tagboxObserver) tagboxObserver.disconnect();
      api.port.off(handlers);

      $stage = $steps = spotlight = tileObserver = tagboxObserver = null;
      $(document).data('esc').remove(hide);
      $(window).off('resize', updateSpotlightPosition);
      return ms;
    } else {
      return 0;
    }
  }

  function complete(n) {
    if (completed < n) {
      completed = n;
      updateSpotlightPosition();
    }
  }

  function updateSpotlightPosition(el, r) {
    var stepIdx = completed, pIdx = stepIdx;
    var step = steps[stepIdx], p = step;
    var lit = document.querySelector(step.lit);
    if (!lit && step.req) {
      for (var i = step.req.length; !lit && i--;) {
        pIdx = step.req[i];
        p = steps[pIdx];
        lit = document.querySelector(p.lit);
      }
    }
    if (lit) {
      $stage.attr({'kifi-step': stepIdx, 'kifi-p': pIdx});
      if (!r || lit !== el) {
        r = lit.getBoundingClientRect();
      }
      console.log('animating to:', r);
      return spotlight.animateTo({
          x: r.left - p.pad[1],
          y: r.top - p.pad[0],
          w: r.width + p.pad[1] * 2,
          h: r.height + p.pad[0] + p.pad[p.pad.length > 2 ? 2 : 0]
        }, {opacity: 1});
    } else {
      return hide();
    }
  }

  function onTileChildChange() {
    if (completed < 1) {
      if (tile.querySelector(steps[1].lit)) {
        completed = 1;
      }
    } else if (completed < 3) {
      var tagbox = tile.querySelector(steps[3].lit);
      if (tagbox) {
        completed = 3;
        var onChange = _.debounce(onTagboxChange.bind(tagbox), 5, true);
        tagboxObserver = new MutationObserver(onChange);
        tagboxObserver.observe(tagbox, {attributes: true});
        $(tagbox).on('transitionend', onChange);
        return;
      }
    }
    updateSpotlightPosition();
  }

  function onTagboxChange() {
    var r = this.getBoundingClientRect(), cs = window.getComputedStyle(this);
    var w = parseFloat(cs.width) + parseFloat(cs.borderLeftWidth) + parseFloat(cs.borderRightWidth);
    var h = parseFloat(cs.height) + parseFloat(cs.borderTopWidth) + parseFloat(cs.borderBottomWidth);
    updateSpotlightPosition(this, {left: r.right - w, top: r.bottom - h, width: w, height: h});
  }

  function wholeWindow() {
    return {x: 0, y: 0, w: window.innerWidth, h: window.innerHeight};
  }

  function remove() {
    $(this).remove();
  }
}();
