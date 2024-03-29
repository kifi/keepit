'use strict';

angular.module('kifi')

.directive('kfProfileWidget', [
  '$state', '$analytics', '$q', 'profileService', 'modalService', 'orgProfileService',
  function ($state, $analytics, $q, profileService, modalService, orgProfileService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'profile/profileWidget.tpl.html',
      link: function (scope) {
        var me = profileService.me;

        scope.me = me;
        scope.organizations = me.orgs || [];

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

        (Object.keys(profileService.prefs).length === 0 ? profileService.fetchPrefs() : $q.when(profileService.prefs)).then(function (prefs) {
          if (prefs.hide_company_name) {
            scope.companyName = null;
          } else {
            var potentialName = prefs.company_name;
            if (!potentialName) {
              var emails = potentialCompanyEmails(me.emails);
              potentialName = emails && emails[0] && getEmailDomain(emails[0].address);
            }
            if (potentialName) {
              if (!orgNameExists(potentialName) && scope.organizations.length === 0) {
                if (potentialName.length > 28) {
                  scope.companyName = potentialName.substr(0, 26) + '…';
                } else {
                  scope.companyName = potentialName;
                }
              } else {
                scope.companyName = null;
                profileService.savePrefs({ hide_company_name: true });
              }
            }
          }
        });

        function orgNameExists(companyName) {
          var orgNames = profileService.me.orgs.map(function(org) { return org.name.toLowerCase(); });
          return orgNames.indexOf(companyName.toLowerCase()) !== -1;
        }

        function potentialCompanyEmails(emails) {
          return emails.filter(function(email){
            return !email.isOwned && !email.isFreeMail;
          });
        }

        var domainSuffixRegex = /\..*$/;

        function getEmailDomain(address) {
          var domain = address.slice(address.lastIndexOf('@') + 1).replace(domainSuffixRegex, '');
          return domain[0].toUpperCase() + domain.slice(1, domain.length);
        }

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

          if (!profileService.prefs.hide_company_name) {
            profileService.savePrefs({ hide_company_name: true });
          }

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
          org.declined = true;
        };

        scope.sendMemberConfirmationEmail = function(email, org) {
          orgProfileService.trackEvent('user_clicked_page', org,
            {
              'type': 'homeFeed',
              'action': 'verifyOrgEmail'
            }
          );
          showVerificationAlert(email);
          profileService.sendMemberConfirmationEmail(org.id, email);
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
