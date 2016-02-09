'use strict';

angular.module('kifi')

.directive('kfLeftHandNav', [
  '$rootElement', '$rootScope', '$document', '$q', 'profileService', 'userProfileActionService', 'orgProfileService', '$state', 'modalService', '$analytics',
  function ($rootElement, $rootScope, $document, $q, profileService, userProfileActionService, orgProfileService, $state, modalService, $analytics) {
    return {
      restrict: 'A',
      templateUrl: 'leftHandNav/leftHandNav.tpl.html',
      replace: true,
      link: function (scope) {
        scope.me = profileService.me;
        scope.libraries = [];
        scope.orgs = scope.me.orgs;

        if (scope.me.pendingOrgs) {
          scope.me.pendingOrgs.forEach(function (o) {
            o.pending = true;
          });
          scope.orgs = scope.orgs.concat(scope.me.pendingOrgs);
        }

        if (scope.me.potentialOrgs) {
          scope.me.potentialOrgs.forEach(function (o) {
            o.potential = true;
          });
          scope.orgs = scope.orgs.concat(scope.me.potentialOrgs);
        }

        // TODO: REMOVE THIS HACK
        document.body.style.overflow = 'hidden';

        var INITIAL_PAGE_SIZE = 6;
        var PAGE_SIZE = 15;
        var extraLibraries = [];
        scope.fetchLibraries = function (pageNumber, pageSize) {
          var filter = 'own';
          scope.hasMoreUserLibaries = false;
          return userProfileActionService
              .getLibraries(scope.me.username, filter, pageNumber, pageSize)
              .then(function (data) {
                scope.loaded = true;
                return data[filter];
              }).then(function(libs) {
                scope.hasMoreUserLibaries = libs.length === pageSize;
                libs.splice(pageSize);
                if (pageNumber === 0) {
                  extraLibraries = libs.splice(INITIAL_PAGE_SIZE);
                  scope.libraries = scope.libraries.concat(libs);
                } else {
                  scope.libraries = scope.libraries.concat(extraLibraries).concat(libs);
                  extraLibraries = [];
                }
              });
        };

        var promises = [];
        promises.push(scope.fetchLibraries(0, PAGE_SIZE));

        scope.fetchOrgLibraries = function (org, offset, limit) {
          org.hasMoreLibraries = false;
          return orgProfileService.getOrgLibraries(org.id, offset, limit + 1)
            .then(function (data) {
              org.hasMoreLibraries = data.libraries.length === limit + 1;
              data.libraries.splice(limit);
              org.libraries = (org.libraries || []).concat(data.libraries);
            });
        };

        scope.orgs.forEach(function (org) {
           promises.push(scope.fetchOrgLibraries(org, 0, INITIAL_PAGE_SIZE));
        });

        scope.showUserAndOrgContent = false;
        $q.all(promises).then(function() {
          scope.showUserAndOrgContent = true;
        });

        scope.fetchingUserLibraries = false;
        scope.viewMoreOwnLibraries = function () {
          scope.fetchingUserLibraries = true;
          scope.fetchLibraries(Math.ceil(scope.libraries.length / PAGE_SIZE), PAGE_SIZE).then(function() {
            scope.fetchingUserLibraries = false;
          });
        };

        scope.viewMoreOrgLibraries = function (org) {
          org.fetchingLibraries = true;
          scope.fetchOrgLibraries(org, org.libraries.length, PAGE_SIZE).then(function() {
            org.fetchingLibraries = false;
          });
        };

        scope.createOwnLibrary = function () {
          $state.go('userProfile.libraries.own', { handle: scope.me.username, openCreateLibrary: true }, {reload: true});
        };

        scope.createOrgLibrary = function (org) {
          $state.go('orgProfile.libraries', { handle: org.handle, openCreateLibrary: true }, {reload: true});
        };


        scope.joinOrg = function(org) {
          orgProfileService
            .acceptOrgMemberInvite(org.id)
            .then(function() {
              org.pending = false;
            });
        };

        scope.declineOrg = function(org) {
          orgProfileService.declineOrgMemberInvite(org.id);
          _.remove(scope.orgs, org);
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
          _.remove(scope.orgs, org);
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

        // Code for showing potential company name and create team dialogs
        var companyNameP;
        if (Object.keys(profileService.prefs).length === 0) {
          companyNameP = profileService.fetchPrefs().then(function (prefs) {
            return prefs.company_name;
          });
        } else {
          companyNameP = $q.when(profileService.prefs.company_name);
        }
        companyNameP.then(function (companyName) {
          if (profileService.prefs.hide_company_name) {
            scope.companyName = null;
          } else {
            var potentialName = companyName;
            if (!potentialName) {
              var emails = potentialCompanyEmails(scope.me.emails);
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
      }
    };
  }
]);
