'use strict';

angular.module('kifi')

.directive('kfGoodies', [
  '$rootScope', '$window', '$location', '$analytics', 'installService', 'extensionLiaison', 'modalService',
  function ($rootScope, $window, $location, $analytics, installService, extensionLiaison, modalService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'recos/goodies.tpl.html',
      link: function (scope) {

        scope.installState = function () {
          return installService.installState;
        };

        scope.triggerInstall = function () {
          installService.triggerInstall(function () {
            modalService.open({
              template: 'common/modal/installExtensionErrorModal.tpl.html'
            });
          });
        };

        scope.triggerGuide = function (linkClicked) {
          extensionLiaison.triggerGuide();
          if (linkClicked) {
            $analytics.eventTrack('user_clicked_page', {
              'action': 'startGuide',
              'path': $location.path()
            });
          }
        };

        scope.importBookmarks = function () {
          var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

          if (!kifiVersion) {
            modalService.open({
              template: 'common/modal/installExtensionModal.tpl.html',
              scope: scope
            });
            return;
          }

          $rootScope.$emit('showGlobalModal', 'importBookmarks');
          $analytics.eventTrack('user_viewed_page', {
            'type': 'browserImport'
          });
        };

        scope.importBookmarkFile = function () {
          $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
          $analytics.eventTrack('user_viewed_page', {
            'type': '3rdPartyImport'
          });
        };

      }
    };
  }
]);
