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
            o.declined = false;
          });
          scope.organizations = scope.organizations.concat(me.pendingOrgs);
        }

        if (me.potentialOrgs) {
          me.potentialOrgs.forEach(function (o) {
            o.potential = true;
            o.declined = false;
          });
          scope.organizations = scope.organizations.concat(me.potentialOrgs);
        }

        if (!profileService.prefs.company_name) {
          profileService.fetchPrefs().then(function (prefs) {
            if (prefs.company_name && !orgNameExists(prefs.company_name)) {
              scope.companyName = prefs.company_name;
            }
          });
        } else {
          scope.companyName = (!orgNameExists(profileService.prefs.company_name) && profileService.prefs.company_name);
        }

        function orgNameExists(companyName) {
          var orgNames = profileService.me.orgs.map(
            function(org) {
              return org.name.toLowerCase();
            }
          );
          return orgNames.indexOf(companyName.toLowerCase()) !== -1;
        }

        scope.shouldShowCreateTeam = function () {
          return scope.me.experiments.indexOf('admin') !== -1 || (scope.me.experiments.indexOf('create_team') !== -1 && scope.me.orgs.length <= 1);
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
          $analytics.eventTrack('user_clicked_page', {
            'type' : 'homeFeed',
            'action' : 'clickedCreateTeamRighthandRail'
          });
          $state.go('teams.new');
        };

        scope.openLearnMoreModal = function () {
          $analytics.eventTrack('user_clicked_page', {
            'type': 'homeFeed',
            'action': 'learnMoreTeams'
          });

          modalService.open({
            template: 'profile/learnMoreModal.tpl.html',
            modalData: {
              companyName: scope.companyName,
              triggerCreateTeam: function () {
                $analytics.eventTrack('user_clicked_page', {
                  'type' : 'homeFeed',
                  'action' : 'clickedCreateTeamLearnMore'
                });
                scope.createTeam();
              }
            }
          });
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

        scope.sendVerificationEmail = function(email, org) {
          orgProfileService.trackEvent('user_clicked_page', org,
            {
              'type': 'homeFeed',
              'action': 'verifyOrgEmail'
            }
          );
          showVerificationAlert(email);
          profileService.resendVerificationEmail(email);
        };

        scope.hideOrgDomain = function(org) {
          org.declined = true;
          orgProfileService.trackEvent('user_clicked_page', org,
            {
              'type': 'homeFeed',
              'action': 'hideOrgDomain'
            }
          );
          profileService.hideOrgDomain(org);
        };

        function showVerificationAlert(email) {
          scope.emailForVerification = email;
          modalService.open({
            template: 'profile/emailResendVerificationModal.tpl.html',
            scope: scope
          });
        }

      }
    };
  }
]);
