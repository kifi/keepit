'use strict';

angular.module('kifi')


.directive('kfCardStyleSelector', [
    '$rootScope', 'profileService',
  function ($rootScope, profileService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'common/directives/cardStyleSelector/cardStyleSelector.tpl.html',
      link: function (scope) {
        scope.admin = profileService.isAdmin();
        scope.galleryView = !profileService.prefs.use_minimal_keep_card;
        $rootScope.$on('prefsChanged', function() {
          scope.galleryView = !profileService.prefs.use_minimal_keep_card;
        });

        scope.setGalleryView = function() {
          scope.galleryView = true;
          profileService.savePrefs({use_minimal_keep_card: false});
          $rootScope.$emit('cardStyleChanged', {use_minimal_keep_card: false});
        };

        scope.setCompactView = function() {
          scope.galleryView = false;
          profileService.savePrefs({use_minimal_keep_card: true});
          $rootScope.$emit('cardStyleChanged', {use_minimal_keep_card: true});
        };

      }
    };
  }
]);
