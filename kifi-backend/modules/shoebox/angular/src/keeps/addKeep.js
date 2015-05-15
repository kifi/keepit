'use strict';

angular.module('kifi')

.directive('kfAddKeep', [
  '$document', '$rootScope', '$location', '$state', '$timeout',
  'KEY', 'keepActionService', 'libraryService', 'modalService', 'util',
  function ($document, $rootScope, $location, $state, $timeout,
            KEY, keepActionService, libraryService, modalService, util) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'keep/addKeep.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        var focusState = 0; // 0: input field, 1: private toggle, 2: action button
        var input = element.find('.kf-add-keep-input');

        scope.state = {};
        var reset = function () {
          scope.state.checkedPrivate = false;
          scope.state.invalidUrl = false;
          scope.state.input = '';
          scope.keptPublic = false;
          scope.keptPrivate = false;
        };
        reset();

        function processKey(e) {
          scope.$apply(function () {
            switch (e.which) {
              case KEY.ENTER:
                scope.keepToLibrary();
                break;
              case KEY.TAB:
                focusState = (focusState + 1) % 3;
                if (focusState === 0) {
                  e.preventDefault();
                  e.stopPropagation();
                  safeFocus();
                }
                break;
            }
          });
        }

        scope.resetFocusState = function () {
          focusState = 0;
        };

        // Seems like calling input.focus() inside a $digest may cause an error
        // if input has an ng-focus attribute ??
        function safeFocus() {
          setTimeout(function () {
            input.focus();
          });
        }

        scope.$on('$destroy', function () {
          $document.off('keydown', processKey);
        });

        scope.selectedLibrary =
          scope.modalData.selectedLibId && _.find(libraryService.getOwnInfos(), {id: scope.modalData.selectedLibId}) ||
          libraryService.getSysMainInfo();

        scope.onLibrarySelected = function (selectedLibrary) {
          scope.selectedLibrary = selectedLibrary;
        };

        scope.keepToLibrary = function () {
          var url = (scope.state.input) || '';
          if (url && util.validateUrl(url)) {
            keepActionService.keepToLibrary([{ url: url }], scope.selectedLibrary.id).then(function (result) {
              if (result.failures && result.failures.length) {
                scope.resetAndHide();
                modalService.open({
                  template: 'common/modal/genericErrorModal.tpl.html'
                });
              } else {
                libraryService.fetchLibraryInfos(true);
                if (scope.selectedLibrary.visibility === 'secret') {
                  scope.keptPrivate = true;
                } else {
                  scope.keptPublic = true;
                }
                scope.inAnimation = true;
                $timeout(function () {
                  scope.inAnimation = false;
                }, 200);

                $timeout(scope.resetAndHide, 1700);

                // If we are on the library page where the keep is being added, add the keep to the top of the list of keeps.
                if ((result.alreadyKept.length === 0) &&
                    ($state.href($state.current.name) === scope.selectedLibrary.url)) {
                  return keepActionService.fetchFullKeepInfo(result.keeps[0]).then(function (keep) {
                    $rootScope.$emit('keepAdded', [keep], scope.selectedLibrary);
                  });
                }
              }
            })['catch'](modalService.openGenericErrorModal);
          } else {
            scope.state.invalidUrl = true;
          }
        };

        scope.resetAndHide = function () {
          reset();
          kfModalCtrl.close();
          $document.off('keydown', processKey);
        };

        $document.on('keydown', processKey);
        safeFocus();
      }
    };
  }
]);
