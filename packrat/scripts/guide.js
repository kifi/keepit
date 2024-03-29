// @require scripts/render.js
// @require scripts/html/guide/steps.js

k.guide = k.guide || {
  show: function (o) {
    'use strict';
    log('[guide.show]', o);
    if (!o.page) {
      var ds = document.documentElement.dataset;
      if (ds.kifiExt) {
        ds.guide = '';
        window.postMessage('get_guide', location.origin);
      } else {
        log('[guide.show] not our site');
      }
    } else {
      api.require('scripts/guide/step_' + o.step + '.js', function () {
        var params = {showing: o.step > 0};
        params['p' + Math.max(1, o.step)] = true;
        var $steps = $(k.render('html/guide/steps', params))
          .data('updateProgress', function (frac) {
            frac = Math.min(1, frac);
            if (1 - frac < .001) {
              $bar.one('transitionend', function () {
                var $par = $bar.parent().removeClass('kifi-current');
                $bar.detach().css('border-left-width', 0);
                $par.next().addClass('kifi-current').append($bar);
              });
            }
            $bar.css('border-left-width', frac * 110);
          });
        var $bar = $steps.find('.kifi-gs-bar');
        var guideStep = k.guide['step' + o.step];
        guideStep.show($steps, o.page);
        api.onEnd.push(guideStep.remove);
      });
    }
  }
};
