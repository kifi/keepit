'use strict';

angular.module('kifi')

.controller('HomeCtrl', [
  '$rootScope', '$scope', 'profileService',
  function($rootScope, $scope, profileService) {

    $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;

    $scope.$on('$destroy', $rootScope.$on('prefsChanged', function () {
      $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;
    }));

    $scope.hideDelightedSurvey = function () {
      $scope.showDelightedSurvey = false;
    };
  }
]);
