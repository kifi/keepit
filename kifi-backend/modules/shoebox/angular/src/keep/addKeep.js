'use strict';

angular.module('kifi')

.directive('kfAddKeep', [
  '$document', '$rootScope', '$location', 'keyIndices', 'keepDecoratorService', 'keepActionService', 'libraryService', 'tagService', 'util',
  function ($document, $rootScope, $location, keyIndices, keepDecoratorService, keepActionService, libraryService, tagService, util) {

    return {
      restrict: 'A',
      scope: {
        shown: '='
      },
      require: '^kfModal',
      templateUrl: 'keep/addKeep.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        var focusState = 0; // 0: input field, 1: private toggle, 2: action button
        var input = element.find('.kf-add-keep-input');
        var privateSwitch = element.find('.kf-add-keep-private-container');

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
                if (scope.librariesEnabled) {
                  scope.keepToLibrary();
                } else {
                  scope.keepUrl();
                }
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

        privateSwitch.on('keydown', function (e) {
          scope.$apply(function () {
            if (e.which === keyIndices.KEY_SPACE) {
              e.stopPropagation();
              e.preventDefault();
              scope.togglePrivate();
            }
          });
        });

        scope.$on('$destroy', function () {
          $document.off('keydown', processKey);
        });

        scope.togglePrivate = function () {
          scope.state.checkedPrivate = !scope.state.checkedPrivate;
        };

        scope.keepUrl = function () {
          var url = (scope.state.input) || '';
          if (url && util.validateUrl(url)) {
            $location.path('/');

            return keepActionService.keepUrl([url], scope.state.checkedPrivate).then(function (result) {
              if (result.failures && result.failures.length) {
                $rootScope.$emit('showGlobalModal', 'genericError');
              } else if (result.alreadyKept && result.alreadyKept.length) {
                $location.path('/keep/' + result.alreadyKept[0].id);
              } else {
                return keepActionService.fetchFullKeepInfo(result.keeps[0]).then(function (fullKeep) {
                  var keep = new keepDecoratorService.Keep(fullKeep);
                  keep.buildKeep(keep);
                  keep.makeKept();
                  tagService.addToKeepCount(1);

                  scope.$emit('keepAdded', '', keep);
                  scope.resetAndHide();
                });
              }
            }); 
          } else {
            scope.state.invalidUrl = true;
          }
        };

        scope.keepToLibrary = function () {
          var url = (scope.state.input) || '';
          if (url && util.validateUrl(url)) {
            return keepActionService.keepToLibrary([url], scope.data.selectedLibraryId).then(function (result) {
              if (result.failures && result.failures.length) {
                $rootScope.$emit('showGlobalModal', 'genericError');
              } else if (result.alreadyKept.length > 0) {
                $location.path('/keep/' + result.alreadyKept[0].id);
              } else {
                return keepActionService.fetchFullKeepInfo(result.keeps[0]).then(function (fullKeep) {
                  var keep = new keepDecoratorService.Keep(fullKeep);
                  keep.buildKeep(keep);
                  keep.makeKept();

                  libraryService.addToLibraryCount(scope.data.selectedLibraryId, 1);
                  tagService.addToKeepCount(1);

                  scope.$emit('keepAdded', libraryService.getSlugById(scope.data.selectedLibraryId), keep);
                  scope.resetAndHide();
                });
              }
            });
          } else {
            scope.state.invalidUrl = true;
          }
        };


        scope.librariesEnabled = libraryService.isAllowed();
        if (scope.librariesEnabled) {
          scope.libraries = _.filter(libraryService.librarySummaries, function(lib) {
            return lib.access !== 'read_only';
          });
          scope.data = scope.data || {};
          scope.data.selectedLibraryId = _.find(scope.libraries, function(lib) {
            return lib.name === 'Main Library';
          }).id;          
        }

        scope.$watch('shown', function (shown) {
          if (shown) {
            $document.on('keydown', processKey);
            safeFocus();
          } else {
            $document.off('keydown', processKey);
          }
        });

        scope.resetAndHide = function () {
          reset();
          kfModalCtrl.hideModal();
        };
      }
    };
  }
]);
