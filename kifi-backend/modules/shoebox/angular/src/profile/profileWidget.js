'use strict';

angular.module('kifi')

.directive('kfProfileWidget', [
  '$state', '$analytics', 'profileService', 'modalService',
  function ($state, $analytics, profileService, modalService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'profile/profileWidget.tpl.html',
      link: function (scope) {
        var me = profileService.me;

        scope.me = me;
        scope.organizations = me.orgs;

        if (me.pendingOrgs) {
          me.pendingOrgs.forEach(function (o) {
            o.pending = true;
          });
          scope.organizations = scope.organizations.concat(me.pendingOrgs);
        }

        scope.shouldShowCreateTeam = function () {
          return scope.me.experiments.indexOf('admin') !== -1;
        };

        scope.registerEvent = function (action) {
          $analytics.eventTrack('user_clicked_page', {
            'action': 'clickedProfile' + action,
            'type': 'yourKeeps'
          });
        };

        scope.bioClick = function () {
          if (typeof(me.biography) === 'undefined') {
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

        scope.createTeam = function () {
          $state.go('organizations.new');
        };
      }
    };
  }
]);
