'use strict';

angular.module('kifi')

.directive('kfLibraryMenuHelp', ['$window',
  function ($window) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'layout/libraryMenu/libraryMenuHelp.tpl.html',
      link: function (scope , element /*, attrs*/) {
        function setLeftPosition() {
          element.css({'left': angular.element('.kf-lih-toggle-menu').offset().left - 13 + 'px'});
        }

        scope.$watch('libraryMenuHelp.visible', function (visible) {
          if (visible) {
            setLeftPosition();
          } else {
            // Let the server know that the user has seen this menu.
          }
        });

        scope.closeLMH = function () {
          scope.libraryMenuHelp.visible = false;
        };

        var debouncedSetLeftPosition = _.debounce(setLeftPosition, 50);
        $window.addEventListener('resize', debouncedSetLeftPosition);
        scope.$on('$destroy', function () {
          $window.removeEventListener(debouncedSetLeftPosition);
        });
      }
    };
  }
]);
