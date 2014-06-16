'use strict';

angular.module('kifi.addKeep', [])

.directive('kfAddKeep', [
  '$document', 'keyIndices',
  function ($document, keyIndices) {

    return {
      restrict: 'A',
      scope: {
        addKeepInput: '=',
        addKeepCheckedPrivate: '=',
        addKeepTogglePrivate: '&',
        keepUrl: '&'
      },
      templateUrl: 'keep/addKeep.tpl.html',
      link: function (scope, element) {

        var focusState = 0; // 0: input field, 1: private toggle, 2: action button
        var input = element.find('.kf-add-keep-input');
        var privateSwitch = element.find('.kf-add-keep-private-container');

        function processKey(e) {
          switch (e.which) {
            case keyIndices.KEY_ENTER:
              scope.keepUrl();
              break;
            case keyIndices.KEY_TAB:
              focusState = (focusState + 1) % 3;
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

        $document.on('keydown', processKey);
        privateSwitch.on('keydown', function (e) {
          scope.$apply(function () {
            if (e.which === keyIndices.KEY_SPACE) {
              e.stopPropagation();
              e.preventDefault();
              scope.addKeepTogglePrivate();
            }
          }); 
        });

        scope.$on('$destroy', function () {
          $document.off('keydown', processKey);
        });
      }
    };
  }
]);
