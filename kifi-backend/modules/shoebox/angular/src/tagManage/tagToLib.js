'use strict';

angular.module('kifi')

.directive('kfTagLibrary', ['$location', 'libraryService',
  function ($location, libraryService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'tagManage/tagToLibMsg.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        scope.hide = function () {
          kfModalCtrl.close();
        };

        scope.navigateToLibrary = function () {
          libraryService.getLibraryById(scope.modalData.library.id, true).then(function() { // invalidates cache
            $location.url(scope.modalData.library.url);
          });
          kfModalCtrl.close();
        };
        scope.action = (scope.modalData.action === 'copy') ? 'Copying' : 'Moving';
      }
    };
  }
]);
