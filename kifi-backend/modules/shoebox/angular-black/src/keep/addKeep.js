'use strict';

angular.module('kifi.addKeep', [])

.directive('kfAddKeep', [
  '$document', '$rootScope', 'keyIndices', 'keepService',
  function ($document, $rootScope, keyIndices, keepService) {

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
          switch (e.which) {
            case keyIndices.KEY_ENTER:
              scope.$apply(function () {
                scope.keepUrl();
              });
              break;
            case keyIndices.KEY_TAB:
              scope.$apply(function () {
                focusState = (focusState + 1) % 3;
              });
              if (focusState === 0) {
                e.preventDefault();
                e.stopPropagation();
                input.focus();
              }
              break;
          }
        }

        input.on('focus', function () {
          scope.$apply(function () {
            focusState = 0;
          });
        });

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
          if (url && keepService.validateUrl(url)) {
            return keepService.keepUrl([url], scope.state.checkedPrivate).then(function (result) {
              reset();
              kfModalCtrl.hideModal();
              if (result.failures && result.failures.length) {
                $rootScope.$emit('showGlobalModal','genericError');
              } else if (result.alreadyKept && result.alreadyKept.length) {
                console.log('already kept!');
              } else {
                keepService.fetchFullKeepInfo(result.keeps[0]);
              }
            });
          } else {
            scope.state.invalidUrl = true;
          }
        };

        scope.$watch('shown', function (shown) {
          if (shown) {
            $document.on('keydown', processKey);
            input.focus();
          } else {
            $document.off('keydown', processKey);
          }
        });
      }
    };
  }
]);
