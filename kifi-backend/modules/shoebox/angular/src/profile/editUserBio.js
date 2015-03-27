'use strict';

angular.module('kifi')

.directive('kfEditBio', [
  'modalService', '$timeout', 'profileService',
  function (modalService, $timeout, profileService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'profile/editUserBio.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        var characterLimit = 160;
        var editBioInput = element.find('.kf-bio-input');
        editBioInput.focus().select();
        scope.bioText = scope.modalData && scope.modalData.biography ? scope.modalData.biography : '';
        scope.charactersLeft = characterLimit - scope.bioText.length;

        scope.checkOverLimit = function () {
          scope.charactersLeft = characterLimit - scope.bioText.length;
        };

        scope.saveBiography = function () {
          if (scope.charactersLeft < 0) {
            return;
          }
          var inputBio = scope.bioText;
          profileService.changeBiography(inputBio);
          kfModalCtrl.close();
          if (scope.modalData && _.isFunction(scope.modalData.onClose)) {
            scope.modalData.onClose(inputBio);
          }
        };

        scope.absoluteVal = function(num) {
          return Math.abs(num);
        }

        scope.close = function () {
          kfModalCtrl.close();
        };

      }
    };
  }
]);
