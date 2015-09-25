'use strict';

angular.module('kifi')

.directive('kfCreateKeepWidget', [
  '$rootScope', 'keepActionService', 'libraryService', 'modalService', 'util',
  function ($rootScope, keepActionService, libraryService, modalService, util) {
    // TODO: Move this to src/keeps and replace a lot of
    // addKeep.js/.tpl.html with this directive.
    return {
      restrict: 'A',
      templateUrl: 'feed/createKeepWidget.tpl.html',
      replace: true,
      scope: {},
      link: function (scope) {
        libraryService.fetchLibraryInfos().then(function () {
          scope.selectedLibrary = libraryService.getSysMainInfo();
        });

        scope.onLibrarySelected = function (selectedLibrary) {
          scope.selectedLibrary = selectedLibrary;
        };

        scope.reset = function () {
          scope.state = {
            input: '',
            invalidUrl: false,
            alreadyKept: false
          };
        };
        scope.reset();

        scope.keepToLibrary = function () {
          var url = (scope.state.input) || '';

          if (url && util.validateUrl(url)) {
            keepActionService
            .keepToLibrary([{ url: url }], scope.selectedLibrary.id)
            .then(function (result) {
              if (result.failures && result.failures.length) {
                scope.reset();
                modalService.open({
                  template: 'common/modal/genericErrorModal.tpl.html'
                });
              } else {
                libraryService.fetchLibraryInfos(true);

                // If we are on the library page where the keep is being added, add the keep to the top of the list of keeps.
                if (result.alreadyKept.length === 0) {
                  scope.reset(); // reset any previous errors.

                  return keepActionService
                  .fetchFullKeepInfo(result.keeps[0])
                  .then(function (keep) {
                    $rootScope.$emit('keepAdded', [keep], scope.selectedLibrary);
                  });
                } else {
                  scope.state.alreadyKept = true;
                }
              }
            })['catch'](modalService.openGenericErrorModal);
          } else {
            scope.state.invalidUrl = true;
          }
        };
      }
    };
  }
]);
