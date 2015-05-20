'use strict';

angular.module('kifi')

.directive('kfLibraryPassPhrase', [
  function () {
    return {
      restrict: 'A',
      templateUrl: 'libraries/libraryPassPhrase.tpl.html',
      link: function () { }
    };
  }
]);
