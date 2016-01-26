'use strict';

angular.module('kifi')

.directive('kfEditKeepTitle', [
    'keepActionService', 'messageTicker',
  function (keepActionService, messageTicker) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'keep/editKeepTitle.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        var keep = scope.modalData && scope.modalData.keep;
        scope.keepTitle = keep && keep.title;

        scope.resetAndHide = function () {
          kfModalCtrl.close();
        };

        scope.onKeyDown = function ($event) {
          if ($event.which === 13) {
            scope.saveTitle();
          }
        };

        scope.saveTitle = function () {
          keepActionService.editKeepTitle(keep.pubId, scope.keepTitle).then(function(newTitle) {
              messageTicker({
              text: 'Title Updated',
              type: 'green'
            });
            keep.title = newTitle;
          });
          kfModalCtrl.close();
        };
      }
    };
  }
]);
