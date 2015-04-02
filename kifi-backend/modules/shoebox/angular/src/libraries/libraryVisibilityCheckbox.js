'use strict';

angular.module('kifi')

.directive('kfLibraryVisibilityCheckbox', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryVisibilityCheckbox.tpl.html',
      scope: {
        library: '='
      },
      link: function (scope, element) {
        $timeout(function () {
          element.find('.library-visibility-custom-input').addClass('kf-transitions');
        });
      }
    };
  }
]);
