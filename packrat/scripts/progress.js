// @require styles/progress.css
// @require scripts/lib/jquery.js

k.progress = k.progress || (function () {

  return {
    emptyAndShow: emptyThenProgress,
    show: progress
  };

  function emptyThenProgress($progressParent, promise) {
    $progressParent = $progressParent.is('.kifi-progress-parent') ? $progressParent : $progressParent.find('.kifi-progress-parent');
    return progress($progressParent, promise).fin(function () {
      $progressParent.children().delay(300).fadeOut(300);
    });
  }

  // Takes a promise for a task's outcome. Returns a promise that relays
  // the outcome after visual indication of the outcome is complete.
  function progress(parent, promise) {
    var $el = $('<div class="kifi-progress"/>').appendTo(parent);
    var frac = 0, ms = 10, deferred = Q.defer();

    var timeout;
    function update() {
      var left = 0.9 - frac;
      frac += 0.06 * left;
      $el[0].style.width = Math.min(frac * 100, 100) + '%';
      if (left > 0.0001) {
        timeout = setTimeout(function () {
          update();
        }, ms);
      }
    }
    timeout = setTimeout(function () {
      update();
    }, ms);

    promise.done(function (val) {
      log('[progress:done]');
      clearTimeout(timeout);
      timeout = null;
      $el.on('transitionend', function (e) {
        if (e.originalEvent.propertyName === 'clip') {
          $el.off('transitionend');
          deferred.resolve(val);
        }
      }).addClass('kifi-done');
    }, function (reason) {
      log('[progress:fail]');
      clearTimeout(timeout);
      timeout = null;
      var finishFail = function () {
        $el.remove();
        deferred.reject(reason);
      };
      if ($el[0].offsetWidth) {
        $el.one('transitionend', finishFail).addClass('kifi-fail');
      } else {
        finishFail();
      }
    });
    return deferred.promise;
  }
}());
