'use strict';

angular.module('kifi')

.directive('kfProfileWidget', [
  '$state', '$analytics', 'profileService', 'modalService', 'orgProfileService',
  function ($state, $analytics, profileService, modalService, orgProfileService) {
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
            o.notDeclined = true;
          });
          scope.organizations = scope.organizations.concat(me.pendingOrgs);
        }

        scope.shouldShowCreateTeam = function () {
          return scope.me.experiments.indexOf('admin') !== -1 || scope.me.experiments.indexOf('create_team') !== -1;
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
          $state.go('teams.new');
        };

        scope.acceptInvite = function(org) {
          orgProfileService
            .acceptOrgMemberInvite(org.id)
            .then(function() {
              org.pending = false;
            });
        };

        scope.declineInvite = function(org) {
          orgProfileService.declineOrgMemberInvite(org.id);
          org.notDeclined = false;
        };
      }
    };
  }
]);
