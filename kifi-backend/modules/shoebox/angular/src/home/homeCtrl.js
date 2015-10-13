'use strict';

angular.module('kifi')

.controller('HomeCtrl', [
  '$rootScope', '$scope', '$stateParams', 'profileService',
  function($rootScope, $scope, $stateParams, profileService) {

    $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;

    $scope.$on('$destroy', $rootScope.$on('prefsChanged', function () {
      $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;
    }));

    $scope.hideDelightedSurvey = function () {
      $scope.showDelightedSurvey = false;
    };

    if ($stateParams.openImportModal === 'importBookmarks') {
      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    } else if ($stateParams.openImportModal === 'importBookmarkFile') {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    } else if ($stateParams.openImportModal) {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    }
  }
]);
