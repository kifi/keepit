
var guide = guide || {
  show: function (step) {
    api.port.emit('has', 'guide', function (has) {
      if (has) {
        api.require('scripts/guide/step_' + step + '.js', function () {
          guide['step' + step]();
        });
      }
    });
  }
};
