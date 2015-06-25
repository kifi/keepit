'use strict';

angular.module('kifi')

.directive('kfProfileWidget', [
  '$analytics', 'profileService', 'modalService',
  function ($analytics, profileService, modalService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'profile/profileWidget.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;

        scope.registerEvent = function(action) {
          $analytics.eventTrack('user_clicked_page', {
            'action': 'clickedProfile' + action,
            'type': 'yourKeeps'
          });
        };

        scope.bioClick = function() {
          if (typeof(scope.me.biography) === 'undefined') {
            this.registerEvent('AddBio');

            modalService.open({
              template: 'profile/editUserBiographyModal.tpl.html',
              modalData: {
                biography: '',
                onClose: function (newBiography) {
                  scope.me.biography = newBiography;
                }
              }
            });
          }
        };
      }
    };
  }
]);
