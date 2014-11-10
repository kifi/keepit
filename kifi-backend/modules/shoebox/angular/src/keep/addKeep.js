'use strict';

angular.module('kifi')

.directive('kfAddKeep', [
  '$document', '$rootScope', '$location', 'keyIndices', 'keepDecoratorService', 'keepActionService', 'libraryService', 'modalService', 'tagService', 'util',
  function ($document, $rootScope, $location, keyIndices, keepDecoratorService, keepActionService, libraryService, modalService, tagService, util) {
    return {
      restrict: 'A',
      scope: {},
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
        };
        reset();

        function processKey(e) {
          scope.$apply(function () {
            switch (e.which) {
              case keyIndices.KEY_ENTER:
                scope.keepToLibrary();
                break;
              case keyIndices.KEY_TAB:
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

        scope.selectedLibrary = _.find(libraryService.librarySummaries, { 'kind': 'system_main' });
        scope.onLibrarySelected = function (selectedLibrary) {
          scope.selectedLibrary = selectedLibrary;
        };

        scope.keepToLibrary = function () {
          var url = (scope.state.input) || '';
          if (url && util.validateUrl(url)) {
            return keepActionService.keepToLibrary([{ url: url }], scope.selectedLibrary.id).then(function (result) {
              if (result.failures && result.failures.length) {
                scope.resetAndHide();
                modalService.open({
                  template: 'common/modal/genericErrorModal.tpl.html'
                });
              } else if (result.alreadyKept.length > 0) {
                scope.resetAndHide();
                $location.path('/keep/' + result.alreadyKept[0].id);
              } else {
                return keepActionService.fetchFullKeepInfo(result.keeps[0]).then(function (fullKeep) {
                  var keep = new keepDecoratorService.Keep(fullKeep);
                  keep.buildKeep(keep);
                  keep.makeKept();

                  libraryService.fetchLibrarySummaries(true);
                  libraryService.addToLibraryCount(scope.selectedLibrary.id, 1);
                  tagService.addToKeepCount(1);

                  scope.$emit('keepAdded', libraryService.getSlugById(scope.selectedLibrary.id), [keep]);
                  scope.resetAndHide();
                });
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
