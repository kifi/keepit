
var guide = guide || {
  show: function (what) {
    api.port.emit('has', 'guide', function (has) {
      if (has) {
        what = String(what);
        var step = what[0];
        api.require('scripts/guide/step_' + step + '.js', function () {
          guide['step' + step](+what[2]);
        });
      }
    });
  }
};
