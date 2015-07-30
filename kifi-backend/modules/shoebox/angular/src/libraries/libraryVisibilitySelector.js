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
