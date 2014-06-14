// @require styles/guide/step.css
// @require styles/guide/steps.css
// @require scripts/lib/jquery.js
// @require scripts/lib/q.min.js
// @require scripts/lib/underscore.js
// @require scripts/render.js
// @require scripts/guide/spotlight.js
// @require scripts/guide/curved_arrow.js
// @require scripts/html/guide/steps.js

var guide = guide || {};
guide.step = guide.step || function () {
  var $stage, $steps, spotlight, arrow, steps, opts, stepIdx, animTick;
  var eventsToScreen = 'mouseover mouseout mouseenter mouseleave mousedown mouseup click mousewheel wheel keydown keypress keyup'.split(' ');
  var MATCHES = 'mozMatchesSelector' in document.body ? 'mozMatchesSelector' : 'webkitMatchesSelector';
  return show;

  function show(steps_, opts_) {
    if (!$stage) {
      steps = steps_;
      opts = opts_;
      spotlight = new Spotlight(wholeWindow(), {opacity: 0, maxOpacity: .85});
      $stage = $(render('html/guide/step_' + opts.page, me)).appendTo('body');
      $steps = $(render('html/guide/steps', {showing: true})).appendTo('body');
      $steps.find('.kifi-guide-steps-x').click(hide);
      $(window).on('resize.guideStep', onWinResize);
      eventsToScreen.forEach(function (type) {
        window.addEventListener(type, screenEvent, true);
      });
      return showStep;
    }
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).css('transition-delay', '').removeClass('kifi-open');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      var ms = spotlight.animateTo(wholeWindow(), {opacity: 0, detach: true});
      if (arrow) {
        arrow.fadeAndDetach(ms / 2);
      }
      (opts.hide || api.noop)();

      $stage = $steps = spotlight = arrow = steps = opts = stepIdx = animTick = null;
      $(window).off('resize.guideStep');
      eventsToScreen.forEach(function (type) {
        window.removeEventListener(type, screenEvent, true);
      });
      return ms;
    } else {
      return 0;
    }
  }

  function showStep(idx, rect, ms) {
    if (idx > stepIdx || stepIdx == null) {
      log('[showStep] step:', stepIdx, '=>', idx);
      var step = steps[idx];
      var t0 = Date.now();
      rect = rect || (step.lit ? getRect(step.lit) : null);
      ms = rect ? animateSpotlightTo(rect, step.pad, ms) : (ms || 0);
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

      (opts.step || api.noop)(idx);
    }
  }

  function showNewStep(msToEarliestCompletion) {
    var step = steps[stepIdx];
    $stage
      .attr('kifi-step', stepIdx)
      .css(newStepPosCss(step.pos || {}))
      .data('pos', step.pos || $stage.data('pos'))
      .css({
        'transition-property': step.transition || '',
        'transition-duration': '',
        'transition-delay': Math.max(0, msToEarliestCompletion - 200) + 'ms'
      })
      .on('transitionend', function end(e) {
        if (e.target === this) {
          $(this).off('transitionend', end).css('transition-delay', '');
          var arr = step.arrow;
          if (arr) {
            var tail = $.extend({el: this.querySelector('.kifi-guide-p.kifi-step' + stepIdx).firstElementChild}, arr.from);
            var head = $.extend({el: $(arr.to.sel || step.lit)[0]}, arr.to);
            arrow = new CurvedArrow(tail, head, opts.anchor, stepIdx === 0 ? 600 : 400);
          }
        }
      })
      .each(function () {
        this.clientHeight;  // forces layout
      })
      .addClass('kifi-open');
  }

  // animates stage and arrow to new position and cross-fades to new action prompt
  function showSubstep(msToEarliestCompletion) {
    var step = steps[stepIdx];
    var arr = step.arrow;
    var pos = $stage.data('pos');
    var $pOld = $stage.find('.kifi-guide-p.kifi-step' + (stepIdx - 1)).css({display: 'block', position: 'absolute', width: '100%'});
    var $pNew = $stage.find('.kifi-guide-p.kifi-step' + stepIdx).css('opacity', 0);
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
        'transition-property': step.transition || '',
        'transition-duration': ms + 'ms',
        'transform': ['translate(', tfm.x, 'px,', tfm.y, 'px)'].join('')
      });
    }

    arrow.fadeAndDetach(100);
    arrow = null;

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
          var tail = $.extend({el: $pNew[0].firstElementChild}, arr.from);
          var head = $.extend({el: $(arr.to.sel || step.lit)[0]}, arr.to);
          arrow = new CurvedArrow(tail, head, opts.anchor, 400);
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

  function animateSpotlightTo(rect, pad, ms) {
    var padT = pad[0];
    var padR = pad.length > 1 ? pad[1] : padT;
    var padB = pad.length > 2 ? pad[2] : padT;
    var padL = pad.length > 3 ? pad[3] : padR;
    return spotlight.animateTo({
      x: rect.left - padL,
      y: rect.top - padT,
      w: rect.width + padL + padR,
      h: rect.height + padT + padB
    }, {opacity: 1, ms: ms});
  }

  function onWinResize(e) {
    if (spotlight.wd.w !== window.innerWidth ||
        spotlight.wd.h !== window.innerHeight) {
      var i = stepIdx, step, rect;
      do {
        step = steps[i--];
      } while (!step.lit);
      if (step && (rect = getRect(step.lit))) {
        animateSpotlightTo(rect, step.pad, 1);
      }
    }
  }

  function getRect(sel) {
    var el = $(sel)[0];
    return el && el.getBoundingClientRect();
  }

  function newStepPosCss(pos) {
    if (pos.left === 'auto' && pos.right === 'auto') {
      return $.extend({}, pos, {left: '50%', marginLeft: -$stage[0].offsetWidth / 2, right: ''});
    }
    return pos;
  }

  function screenEvent(e) {
    var step = stepIdx != null && steps[stepIdx];
    if (step && step.allow && (proceed = allowEvent(e, step.allow)) != null) {
      // do not interfere
      if (proceed) {
        showStep(stepIdx + 1);
      }
    } else if (/^(?:mousedown|mouseup|click)$/.test(e.type) && e.target[MATCHES]('a[href][class^=kifi-guide]')) {
      // do not interfere
      if (e.type === 'click' && e.target.classList.contains('kifi-guide-next')) {
        (opts.next || api.noop)(e, stepIdx);
      }
    } else if (e.type === 'keydown') {
      if (!e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey && e.keyCode !== 9) { // allow browser shortcuts, tab
        e.preventDefault();
        e.stopImmediatePropagation();
        if (e.keyCode === 27) { // esc
          hide();
        }
      } else if ((e.metaKey || e.ctrlKey) && e.shiftKey && ~[75,79,83].indexOf(e.keyCode)) {  // block kifi shortcuts
        e.preventDefault();
        e.stopImmediatePropagation();
      }
    } else {
      if (e.type !== 'mousedown') {
        e.preventDefault();
      }
      e.stopImmediatePropagation();
    }
  }

  // returns true (allow and proceed), false (allow but do not proceed), or undefined (do not allow)
  function allowEvent(e, crit) {
    if ('length' in crit) {
      for (var i = 0; i < crit.length; i++) {
        var proceed = allowEvent(e, crit[i]);
        if (proceed != null) {
          return proceed;
        }
      }
    } else if (!crit.type || crit.type.test ? crit.type.test(e.type) : crit.type === e.type) {
      if (e.target[MATCHES](crit.target) && (!crit.unless || !crit.unless(e))) {
        return !!crit.proceed;
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
