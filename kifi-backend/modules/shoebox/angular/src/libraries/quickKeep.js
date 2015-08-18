'use strict';

angular.module('kifi')

.directive('kfQuickKeep', [
  'util', 'keepActionService', 'installService', 'libraryService', 'modalService', '$rootScope',
  function (util, keepActionService, installService, libraryService, modalService, $rootScope) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '='
      },
      templateUrl: 'libraries/quickKeep.tpl.html',
      link: function (scope) {

        scope.quickKeep = {};

        scope.isOwnerOrCollaborator = function () {
          return scope.library && scope.library.membership && (scope.library.membership.access === 'owner' || scope.library.membership.access === 'read_write');
        };

        scope.doQuickKeep = function () {
          var url = (scope.quickKeep.url) || '';
          scope.quickKeep.quickCheck = true;
          libraryService.trackEvent('user_clicked_page', scope.library, { action: 'clickedQuickKeep' });
          if (url && util.validateUrl(url)) {
            keepActionService.keepToLibrary([{ url: url }], scope.library.id).then(function (result) {
              scope.quickKeep.quickCheck = false;
              scope.quickKeep = {};
              if (result.failures && result.failures.length) {
                modalService.openGenericErrorModal();
              } else {
                libraryService.fetchLibraryInfos(true);
                // If we are on the library page where the keep is being added, add the keep to the top of the list of keeps.
                if (result.alreadyKept.length === 0) {
                  return keepActionService.fetchFullKeepInfo(result.keeps[0]).then(function (keep) {
                    $rootScope.$emit('keepAdded', [keep], scope.library);
                  });
                }
              }
            })['catch'](modalService.openGenericErrorModal);
          } else {
            scope.quickKeep.invalidUrl = true;
          }
        };

        scope.checkQuickKeepUrl = function () {
          var url = (scope.quickKeep.url) || '';
          if (!scope.quickKeep.quickCheck) {
            return;
          }
          if (url && util.validateUrl(url) || url==='') {
            scope.quickKeep.invalidUrl = false;
          } else {
            scope.quickKeep.invalidUrl = true;
          }
        };

        scope.triggerExtensionInstall = installService.triggerInstall;

        scope.canInstallExtension = installService.canInstall;


      }
    };
  }
]);
