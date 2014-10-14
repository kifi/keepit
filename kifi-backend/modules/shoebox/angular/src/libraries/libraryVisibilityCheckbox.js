'use strict';

angular.module('kifi')

.directive('kfLibraryVisibilityCheckbox', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryVisibilityCheckbox.tpl.html',
      scope: {
        // This directive contains a checkbox that will change the visibility property
        // on the library object.
        library: '='
      }
    };
  }
]);
