'use strict';

angular.module('kifi')

// for elements on which the mouse wheel should have no effect
.directive('kfWheelStop', function () {
  return {
    restrict: 'A',
    link: function (scope, element) {
      element.on('wheel', function (event) {
        if (element.attr('kf-wheel-stop') !== 'false' && !event.originalEvent.kfAllow) {
          event.preventDefault();
        }
      });
    }
  };
})

// for elements contained by a kfWheelStop on which the mouse wheel should work
.directive('kfWheelAllow', function () {
  return {
    restrict: 'A',
    link: function (scope, element) {
      element.on('wheel', function (event) {
        var delta = event.originalEvent.deltaY;
        if (delta) {
          var scrollTop = this.scrollTop;
          if (delta > 0) {
            var scrollTopMax = this.scrollHeight - this.clientHeight;
            if (scrollTop + delta > scrollTopMax) {
              this.scrollTop = scrollTopMax;
            } else {
              event.originalEvent.kfAllow = true;
            }
          } else if (scrollTop + delta < 0) {
            if (scrollTop > 0) {
              this.scrollTop = 0;
            }
          } else {
            event.originalEvent.kfAllow = true;
          }
        }
      });
    }
  };
});
