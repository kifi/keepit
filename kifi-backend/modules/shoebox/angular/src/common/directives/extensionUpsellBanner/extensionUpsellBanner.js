'use strict';

angular.module('kifi')

.directive('kfExtensionUpsellBanner', [
  '$analytics', '$rootScope', '$state', '$timeout', '$window', 'installService', 'modalService', 'profileService',
  function ($analytics, $rootScope, $state, $timeout, $window, installService, modalService, profileService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
      },
      templateUrl: 'common/directives/extensionUpsellBanner/extensionUpsellBanner.tpl.html',
      link: function (scope) {
        scope.showBanner = false;
        if (installService.canInstall) {
          profileService.fetchPrefs().then(function (prefs) {
            scope.showBanner = !prefs.hide_extension_upsell && !installService.installedVersion;
          });
        }

        scope.triggerInstall = function () {
          $analytics.eventTrack('user_clicked_page', {
            'type': $state.$current.name,
            'action': 'clickedInstall'
          });
          installService.triggerInstall(function () {
            modalService.open({
              template: 'common/modal/installExtensionErrorModal.tpl.html'
            });
          });
        };

        scope.dismiss = function () {
          $analytics.eventTrack('user_clicked_page', {
            'type': $state.$current.name,
            'action': 'clickedClosed'
          });
          scope.showBanner = false;
          profileService.savePrefs({hide_extension_upsell: true});
        };

        var onExtensionInstall = function () {
          scope.showBanner = false;
        };

        [
          $rootScope.$on('kifiExt', onExtensionInstall)
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });
      }
    };
  }
]);
