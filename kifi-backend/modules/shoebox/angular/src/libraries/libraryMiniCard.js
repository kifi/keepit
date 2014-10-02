'use strict';

angular.module('kifi')

.directive('kfLibraryMiniCard', ['$location', '$window', 'friendService', 'libraryService', 'modalService', 'profileService',
  function ($location, $window, friendService, libraryService, modalService, profileService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
      },
      templateUrl: 'libraries/libraryMiniCard.tpl.html',
      link: function (scope/*, element, attrs*/) {


      }
    };
  }
]);
