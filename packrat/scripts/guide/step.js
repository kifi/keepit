// @require styles/guide/step.css
// @require styles/guide/guide.css
// @require scripts/lib/jquery.js
// @require scripts/lib/q.min.js
// @require scripts/lib/underscore.js
// @require scripts/render.js
// @require scripts/guide/spotlight.js
// @require scripts/guide/curved_arrow.js

guide.step = guide.step || function () {
  var spotlight, $stage, $steps, timeout, arrow, steps, opts, stepIdx, animTick;
  var eventsToScreen = 'mouseover mouseout mouseenter mouseleave mousedown mouseup click mousewheel wheel keydown keypress keyup'.split(' ');
  var MATCHES = 'mozMatchesSelector' in document.body ? 'mozMatchesSelector' : 'webkitMatchesSelector';
  // var sites = [
  //   {noun: 'recipe', tag: 'Recipe', query: 'cake+recipe'},
  //   {noun: 'tote', tag: 'Shopping Wishlist', query: 'tote'},
  //   {noun: 'article', tag: 'Read Later', query: 'lifehack+truly+love'},
  //   {noun: 'video', tag: 'Inspiration', query: 'steve+jobs'}];
  return show;

  function show(steps_, opts_) {
    if (!$stage) {
      steps = steps_;
      opts = opts_;
      spotlight = new Spotlight(wholeWindow(), {opacity: 0, maxOpacity: .85});
      $stage = $(render('html/guide/step_' + opts.index, {me: me, page: opts_.page}));
      $steps = opts_.$guide.appendTo('body')
        .on('click', '.kifi-gs-x', hide);
      $steps.each(layout).data().updateProgress(opts_.done);
      if (document.readyState === 'complete') {
        timeout = setTimeout(show2, 2000);
      } else {
        timeout = setTimeout(show2, 6000);
        window.addEventListener('load', onDocumentComplete, true);
      }
      return {
        show: showStep,
        nav: navTo
      };
    }
  }

  function onDocumentComplete() {
    clearTimeout(timeout);
    timeout = setTimeout(show2, 500);
  }

  function show2() {
    window.removeEventListener('load', onDocumentComplete, true);
    spotlight.attach($.fn.before.bind($steps));
    $stage.insertBefore($steps);
    $(window).on('resize.guideStep', onWinResize);
    eventsToScreen.forEach(function (type) {
      window.addEventListener(type, screenEvent, true);
    });
    showStep(0);
  }

  function hide() {
    if ($stage) {
      var ms = spotlight.animateTo(wholeWindow(), {opacity: 0, detach: true});
      $stage.one('transitionend', remove).css('transition-delay', '').removeClass('kifi-open');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      if (arrow) {
        arrow.fadeAndDetach(ms / 2);
      }
      if (timeout) {
        clearTimeout(timeout);
        window.removeEventListener('load', onDocumentComplete, true);
      }
      (opts.hide || api.noop)();

      $stage = $steps = spotlight = timeout = arrow = steps = opts = stepIdx = animTick = null;
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
      rect = rect || (step.lit ? getRect(step.lit) : (step.pos === 'center' ? 'center' : null));
      ms = rect ? animateSpotlightTo(rect, step.pad, ms) : (ms || 0);
      var promises = stepIdx != null && !step.substep ? [hideStep()] : [];
      if (step.afterTransition) {
        $(document).on('transitionend', step.afterTransition, function end(e) {
          if (e.target === this) {
            $(document).off('transitionend', step.afterTransition, end);
            afterTransitionDeferred.resolve();
          }
        });
        var afterTransitionDeferred = Q.defer();
        promises.push(afterTransitionDeferred.promise);
      }

      Q.all(promises).done(function () {
        ms -= Date.now() - t0;
        stepIdx = idx;
        (opts.step || api.noop)(idx);
        $steps.data().updateProgress(opts.done + (1 - opts.done) * (idx + 1) / steps.length);
        if (step.substep) {
          showSubstep(ms);
        } else {
          showNewStep(ms);
        }
      });
    }
  }

  function showNewStep(msToEarliestCompletion) {
    var step = steps[stepIdx];
    $stage
      .attr('kifi-step', stepIdx)
      .css(newStepPosCss(step.pos))
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
            var tail = $.extend({el: this.querySelector('.kifi-guide-p.kifi-step' + stepIdx)}, arr.from);
            var head = $.extend({el: $(arr.to.sel || step.lit)[0]}, arr.to);
            arrow = new CurvedArrow(tail, head, opts.anchor, stepIdx === 0 ? 600 : 400);
          }
        }
      })
      .each(layout)
      .addClass('kifi-open');
  }

  // animates stage and arrow to new position and cross-fades to new action prompt
  function showSubstep(msToEarliestCompletion) {
    var fadeDeferred = Q.defer();
    var promises = [fadeDeferred.promise];
    $stage.find('.kifi-guide-p.kifi-step' + (stepIdx - 1))
      .on('transitionend', function end() {
        $(this).off('transitionend', end);
        fadeDeferred.resolve();
      })
      .css({opacity: 0, transition: 'opacity .1s linear'});
    arrow.fadeAndDetach(100);
    arrow = null;

    var ms = Math.max(200, msToEarliestCompletion);
    var step = steps[stepIdx];
    if (step.pos) {
      var posDeferred = Q.defer();
      promises.push(posDeferred.promise);
      var pos = $stage.data('pos');
      $stage.data('pos', step.pos);
      var tfm = {x: 0, y: 0};
      for (var k in pos) {
        tfm[k === 'left' || k === 'right' ? 'x' : 'y'] =
           (k === 'left' || k === 'top' ? 1 : -1) * (step.pos[k] - pos[k]);
      }
      $stage.on('transitionend', function end(e) {
        if (e.target === this) {
          $(this).off('transitionend', end);
          posDeferred.resolve();
        }
      })
      .css({
        'transition-property': step.transition || '',
        'transition-duration': ms + 'ms',
        'transform': ['translate(', tfm.x, 'px,', tfm.y, 'px)'].join('')
      });
    }

    Q.all(promises).done(function () {
      var $pNew = $stage.find('.kifi-guide-p.kifi-step' + stepIdx).addClass('kifi-off-left');
      $stage.attr('kifi-step', stepIdx);
      $pNew.each(layout)
      .one('transitionend', function () {
        var arr = step.arrow;
        var tail = $.extend({el: this}, arr.from);
        var head = $.extend({el: $(arr.to.sel || step.lit)[0]}, arr.to);
        arrow = new CurvedArrow(tail, head, opts.anchor, 400);
      })
      .removeClass('kifi-off-left');
    });
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
    if (rect === 'center') {
      return spotlight.animateTo(null, {brightness: 0, opacity: 1, ms: ms});
    } else {
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

  function navTo(url) {
    api.port.emit('await_deep_link', {locator: '#guide/' + (opts.index + 1) + '/' + opts.pageIdx, url: url});
    window.location = url;
  }

  function getRect(sel) {
    var el = $(sel)[0];
    return el && el.getBoundingClientRect();
  }

  function newStepPosCss(pos) {
    return pos === 'center'
      ? {left: '50%', right: '', marginLeft: -$stage[0].offsetWidth / 2, top: '50%', bottom: '', marginTop: -$stage[0].offsetHeight / 2}
      : (pos || {});
  }

  function screenEvent(e) {
    var step = stepIdx != null && steps[stepIdx];
    if (step && step.allow && (proceed = allowEvent(e, step.allow)) != null) {
      // do not interfere
      if (proceed) {
        if (stepIdx + 1 < steps.length) {
          showStep(stepIdx + 1);
        } else {
          navTo($(e.target).closest('[href]').prop('href'));
        }
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
      var sel = crit.target.split(',');
      for (var n = sel.length, i = 0; i < n; i++) {
        sel.push(sel[i] + ' *');
      }
      if (e.target[MATCHES](sel.join(',')) && (!crit.unless || !crit.unless(e))) {
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

  function layout() {
    this.clientHeight;
  }

  function setProp(o, k, v) {
    o[k] = v;
    return o;
  }
}();
