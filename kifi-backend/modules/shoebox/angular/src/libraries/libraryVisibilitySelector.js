'use strict';

angular.module('kifi')

.directive('kfLibraryVisibilitySelector', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryVisibilitySelector.tpl.html',
      scope: {
        library: '=',
        space: '='
      },
      link: function (scope, element) {
        scope.spaceIsOrg = function () {
          return 'numMembers' in scope.space;
        };

        $timeout(function () {
          element.find('.library-visibility-checkbox-custom-input').addClass('kf-transitions');
        });
      }
    };
  }
]);
