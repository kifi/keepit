// @require scripts/render.js
// @require scripts/html/guide/steps.js

var guide = guide || {
  show: function (what) {
    'use strict';
    api.port.emit('has', 'guide', function (has) {
      if (has) {
        what = String(what);
        var step = what[0];
        api.require('scripts/guide/step_' + step + '.js', function () {
          var params = {showing: step > 0};
          params['p' + Math.max(1, step)] = true;
          var $steps = $(render('html/guide/steps', params))
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
          guide['step' + step](+what[2] || 0, $steps);
        });
      }
    });
  }
};
