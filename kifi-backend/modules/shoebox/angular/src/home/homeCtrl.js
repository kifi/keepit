'use strict';

angular.module('kifi')

.controller('HomeCtrl', [
  '$window', '$rootScope', '$scope', 'profileService',
  function($window, $rootScope, $scope, profileService) {

    $window.document.title = 'Kifi • Your stream';

    $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;

    $scope.$on('$destroy', $rootScope.$on('prefsChanged', function () {
      $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;
    }));

    $scope.hideDelightedSurvey = function () {
      $scope.showDelightedSurvey = false;
    };
  }
]);
