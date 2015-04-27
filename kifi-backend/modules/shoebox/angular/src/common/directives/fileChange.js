'use strict';

angular.module('kifi')

// propagates selected files from <input type="file"> to the Angular scope
// backstory at github.com/angular/angular.js/issues/1375

.directive('kfFileChange', function () {
  return {
    restrict: 'A',
    scope: {
      kfFileChange: '&'
    },
    link: function (scope, element) {
      element.on('change', function () {
        var input = this;
        scope.$apply(function () {
          scope.kfFileChange()(input.files);
          input.files = null;
        });
      });
    }
  };
});
