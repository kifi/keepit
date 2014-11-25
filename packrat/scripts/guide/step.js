// @require styles/guide/step.css
// @require styles/guide/guide.css
// @require scripts/lib/jquery.js
// @require scripts/lib/q.min.js
// @require scripts/lib/underscore.js
// @require scripts/render.js
// @require scripts/guide/spotlight.js
// @require scripts/guide/curved_arrow.js

k.guide.step = k.guide.step || function () {
  var spotlight, $stage, $steps, $loading, timeout, arrow, steps, opts, stepIdx, animTick;
  var eventsToScreen = 'mouseover mouseout mouseenter mouseleave mousedown mouseup click mousewheel wheel keydown keypress keyup input'.split(' ');
  var MATCHES = 'mozMatchesSelector' in document.body ? 'mozMatchesSelector' : 'webkitMatchesSelector';
  return show;

  function show(steps_, opts_) {
    if (!$stage) {
      steps = steps_;
      opts = opts_;
      $loading = $('<div class="kifi-guide-loading kifi-root">')
        .append([0,0,0,0,0].map(function () {return '<span class="kifi-guide-spinner"></span>'}).join(''))
        .appendTo('body');
      spotlight = new Spotlight(wholeWindow(), {opacity: 0, maxOpacity: opts_.opacity});
      $stage = $(k.render('html/guide/step_' + opts.index, {me: k.me, page: opts_.page}));
      $steps = opts_.$guide.appendTo('body')
        .on('click', '.kifi-guide-x', hide);
      $steps.each(layout).data().updateProgress(opts_.done);
      eventsToScreen.forEach(function (type) {
        window.addEventListener(type, screenEvent, true);
      });
      if (document.readyState === 'complete') {
        timeout = setTimeout(show2, 500);
      } else {
        timeout = setTimeout(show2, 3500);
        window.addEventListener('load', onDocumentComplete, true);
      }
      return {
        show: showStep,
        nav: navTo,
        removeAll: removeAll
      };
    }
  }

  function onDocumentComplete() {
    clearTimeout(timeout);
    timeout = setTimeout(show2, 200);
  }

  function show2() {
    window.removeEventListener('load', onDocumentComplete, true);
    spotlight.attach($.fn.before.bind($steps));
    $stage.insertBefore($steps);
    $loading.remove();
    $loading = null;
    $(window).on('resize.guideStep', onWinResize);
    showStep($(steps[0].lit).css('display') !== 'none' ? 0 : 1);
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
      if ($loading) {
        $loading.remove();
      }
      (opts.hide || api.noop)();

      api.port.emit('end_guide', [opts.index, stepIdx || 0]);
      spotlight = $stage = $steps = $loading = timeout = arrow = steps = opts = stepIdx = animTick = null;
      $(window).off('resize.guideStep');
      eventsToScreen.forEach(function (type) {
        window.removeEventListener(type, screenEvent, true);
      });
      return ms;
    } else {
      return 0;
    }
  }

  function removeAll() {
    if ($stage) {
      spotlight.detach();
      $stage.remove();
      $steps.remove();
      if (arrow) {
        arrow.detach();
      }
      if (timeout) {
        clearTimeout(timeout);
        window.removeEventListener('load', onDocumentComplete, true);
      }
      if ($loading) {
        $loading.remove();
      }
      (opts.hide || api.noop)();

      $stage = $steps = spotlight = $loading = timeout = arrow = steps = opts = stepIdx = animTick = null;
      $(window).off('resize.guideStep');
      eventsToScreen.forEach(function (type) {
        window.removeEventListener(type, screenEvent, true);
      });
    }
  }

  function showStep(idx, ms, rectLit, rectTo) {
    if (idx > stepIdx || stepIdx == null) {
      log('[showStep] step:', stepIdx, '=>', idx);
      api.port.emit('track_guide', [opts.index, idx]);
      var step = steps[idx];
      var t0 = Date.now();
      rectLit = rectLit || (step.lit ? getRect(step.lit) : (step.pos === 'center' ? 'center' : null));
      ms = rectLit ? animateSpotlightTo(rectLit, step.pad, ms) : (ms || 0);
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

        var pos;
        var newArrow;
        var arr = step.arrow;
        if (arr) {
          var hEl = $(arr.to.sel || step.lit)[0];
          var anchor = createAnchor(hEl);
          var rHead = anchor.translate(rectTo || hEl.getBoundingClientRect());
          var headAngleRad = Math.PI / 180 * arr.to.angle;
          var H = pointOutsideRect(rHead, arr.to.along, headAngleRad + Math.PI, arr.to.gap);
          var T = {x: H.x - arr.dx, y: H.y - arr.dy};

          // compute T_ (T with stage positioned at appropriate window corner)
          var $stageClone = $stage.clone()
            .attr('kifi-step', idx)
            .css(anchor.css)
            .css({visibility: 'hidden', transform: 'none'})
            .appendTo('body');
          var tEl = $stageClone.find('.kifi-guide-p.kifi-step' + stepIdx)[0];
          var rTail = anchor.translate(tEl.getBoundingClientRect());
          var tailAngleRad = Math.PI / 180 * arr.from.angle;
          var T_ = pointOutsideRect(rTail, arr.from.along, tailAngleRad, arr.from.gap);
          $stageClone.remove();

          pos = translatePos(anchor.css, T.x - T_.x, T.y - T_.y);
          newArrow = new CurvedArrow(
            {x: T.x, y: T.y, angle: arr.from.angle, spacing: arr.from.spacing},
            {x: H.x, y: H.y, angle: arr.to.angle, draw: arr.to.draw},
            anchor.css);
        }

        (step.substep && arrow ? showSubstep : showNewStep)(pos || step.pos, ms, newArrow);
      });
    }
  }

  function showNewStep(pos, msToEarliestCompletion, newArrow) {
    arrow = newArrow;
    var step = steps[stepIdx];
    $stage
      .attr('kifi-step', stepIdx)
      .css(newStepPosCss(pos))
      .data('pos', pos || $stage.data('pos'))
      .css({
        'transition-property': step.transition || '',
        'transition-duration': '',
        'transition-delay': Math.max(0, msToEarliestCompletion - 200) + 'ms'
      })
      .each(layout)
      .on('transitionend', function end(e) {
        if (e.target === this) {
          $(this).off('transitionend', end).css('transition-delay', '');
          if (arrow) {
            arrow.reveal(stepIdx === 0 ? 600 : 400);
          }
          if (step.litFor) {
            setTimeout(animateSpotlightTo.bind(null, null, null, 600), step.litFor);
          }
        }
      })
      .addClass('kifi-open');
  }

  // animates stage and arrow to new position and cross-fades to new action prompt
  function showSubstep(pos, msToEarliestCompletion, newArrow) {
    var fadeDeferred = Q.defer();
    var promises = [fadeDeferred.promise];
    $stage.find('.kifi-guide-p.kifi-step' + (stepIdx - 1))
      .on('transitionend', function end() {
        $(this).off('transitionend', end);
        fadeDeferred.resolve();
      })
      .css({opacity: 0, transition: 'opacity .1s linear'});
    arrow.fadeAndDetach(100);
    arrow = newArrow;

    var ms = Math.max(200, msToEarliestCompletion);
    var step = steps[stepIdx];
    if (pos) {
      var posDeferred = Q.defer();
      promises.push(posDeferred.promise);
      var prevPos = $stage.data('pos');
      $stage.data('pos', pos);
      var tfm = {x: 0, y: 0};
      for (var prop in prevPos) {
        tfm[prop === 'left' || prop === 'right' ? 'x' : 'y'] =
           (prop === 'left' || prop === 'top' ? 1 : -1) * (pos[prop] - prevPos[prop]);
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
        arrow.reveal(400);
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
    if (rect == null || rect.left == null) {
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
    api.port.emit('await_deep_link', {
      locator: '#guide/' + (opts.index + 1),
      url: url
    });
    window.location.href = url;
  }

  function createAnchor(el) {  // also in step_4.js
    el = $(el).closest('.kifi-root')[0] || el;
    var cs = window.getComputedStyle(el);
    var dx = cs.right !== 'auto' ? -window.innerWidth : 0;
    var dy = cs.bottom !== 'auto' ? -window.innerHeight : 0;
    return {
      translate: function (o) {
        var o2 = {};
        for (var name in o) {
          var val = o[name];
          if (typeof val === 'number') {
            if (/^(?:x|left|right)$/.test(name)) {
              o2[name] = val + dx;
              continue;
            }
            if (/^(?:y|top|bottom)$/.test(name)) {
              o2[name] = val + dy;
              continue;
            }
          }
          o2[name] = val;
        }
        return o2;
      },
      css: {
        top: dy ? 'auto' : 0,
        left: dx ? 'auto' : 0,
        right: dx ? 0 : 'auto',
        bottom: dy ? 0 : 'auto'
      }
    };
  }

  function translatePos(pos, dx, dy) {  // also in step_4.js
    return {
      top: typeof pos.top === 'number' ? pos.top + dy : pos.top,
      left: typeof pos.left === 'number' ? pos.left + dx : pos.left,
      right: typeof pos.right === 'number' ? pos.right - dx : pos.right,
      bottom: typeof pos.bottom === 'number' ? pos.bottom - dy : pos.bottom
    };
  }

  function pointOutsideRect(r, along, theta, d) { // also in step_4.js
    var Px = r.left + (r.right - r.left) * (along != null ? along[0] : .5);
    var Py = r.top + (r.bottom - r.top) * (along != null ? along[1] : .5);

    // determine the two candidate edges (x = Cx and y = Cy)
    var sinTheta = Math.sin(theta);
    var cosTheta = Math.cos(theta);
    var Cx = cosTheta < 0 ? r.left - d : r.right + d;
    var Cy = sinTheta > 0 ? r.top - d : r.bottom + d;

    // find where ray from P crosses: (Qx, Cy) and (Cx, Qy)
    var tanTheta = sinTheta / cosTheta;
    var Qx = Px + (Py - Cy) / tanTheta;
    var Qy = Py + (Px - Cx) * tanTheta;

    // choose the point closer to P
    return dist2(Px, Py, Qx, Cy) < dist2(Px, Py, Cx, Qy) ? {x: Qx, y: Cy} : {x: Cx, y: Qy};
  }

  function dist2(x1, y1, x2, y2) {
    var dx = x1 - x2;
    var dy = y1 - y2;
    return dx * dx + dy * dy;
  }

  function getRect(selOrBox) {
    if (typeof selOrBox === 'string') {
      var el = $(selOrBox)[0];
      return el && el.getBoundingClientRect();
    } else {
      return {
        top: 'top' in selOrBox ? selOrBox.top : window.innerHeight - selOrBox.bottom - selOrBox.height,
        left: 'left' in selOrBox ? selOrBox.left : window.innerWidth - selOrBox.right - selOrBox.width,
        width: selOrBox.width,
        height: selOrBox.height
      };
    }
  }

  function newStepPosCss(pos) {
    return pos === 'center'
      ? {left: '50%', right: '', marginLeft: -$stage[0].offsetWidth / 2, top: '50%', bottom: '', marginTop: -$stage[0].offsetHeight / 2}
      : (pos || {});
  }

  function screenEvent(e) {
    var step = stepIdx != null && steps[stepIdx];
    if (step && step.allow && (proceed = allowEvent(e, step.allow)) != null || e.type === 'input') {
      // do not interfere
      e.guided = true;
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

  function setProp(o, name, val) {
    o[name] = val;
    return o;
  }
}();
