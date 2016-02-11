'use strict';

angular.module('kifi')

.directive('kfLeftHandNav', [
  '$analytics', '$rootElement', '$rootScope', '$document', '$q', '$state', '$window', 'profileService', 'userProfileActionService', 'orgProfileService',
    'modalService', 'net',
  function ($analytics, $rootElement,  $rootScope, $document, $q, $state, $window, profileService, userProfileActionService, orgProfileService,
              modalService, net) {

    return {
      restrict: 'A',
      templateUrl: 'leftHandNav/leftHandNav.tpl.html',
      replace: true,
      link: function (scope) {
        scope.libraries = [];
        scope.orgs = [];
        $rootScope.leftHandNavIsOpen = $window.matchMedia('(min-width: 480px)').matches;
        var appendPendingAndPotentialOrgs = function () {
          if (scope.me.pendingOrgs) {
            scope.me.pendingOrgs.forEach(function (o) {
              o.pending = true;
              o.libraries = [];
            });
            scope.orgs = scope.orgs.concat(scope.me.pendingOrgs);
          }

          if (scope.me.potentialOrgs) {
            scope.me.potentialOrgs.forEach(function (o) {
              o.potential = true;
              o.libraries = [];
            });
            scope.orgs = scope.orgs.concat(scope.me.potentialOrgs);
          }
        };

        var reloadData = function(me, hideReload) {
          var futureMe = (me && $q.when(me)) || profileService.fetchMe();
          scope.showUserAndOrgContent = hideReload;
          futureMe.then(function (me) {
            scope.me = me;
            net.getInitialLeftHandRailInfo(INITIAL_PAGE_SIZE + 1).then(function(res) {
              var lhr = res.data.lhr;
              scope.hasMoreUserLibaries = lhr.userWithLibs.libs.length === INITIAL_PAGE_SIZE + 1;
              lhr.userWithLibs.libs.splice(INITIAL_PAGE_SIZE);
              scope.libraries = lhr.userWithLibs.libs;
              scope.orgs = res.data.lhr.orgs.map(function(tuple) {
                var org = tuple.org;
                org.hasMoreLibraries = tuple.libs.length === INITIAL_PAGE_SIZE + 1;
                tuple.libs.splice(INITIAL_PAGE_SIZE);
                org.libraries = tuple.libs;
                return org;
              });
              scope.showCreateTeam = scope.orgs.length === 0;
              appendPendingAndPotentialOrgs();
              scope.showUserAndOrgContent = true;
            });
          });
        };

        // TODO: REMOVE THIS HACK
        document.body.style.overflow = 'hidden';

        var INITIAL_PAGE_SIZE = 6;
        var PAGE_SIZE = 15;
        var pageName = function() {
          return $state.$current.name;
        };
        scope.fetchLibraries = function (offset, limit) {
          scope.hasMoreUserLibaries = false;
          return userProfileActionService
              .getBasicLibraries(scope.me.id, offset, limit + 1)
              .then(function (data) {
                scope.loaded = true;
                return data;
              }).then(function(data) {
                scope.hasMoreUserLibaries = data.libs.length === limit + 1;
                data.libs.splice(limit);
                scope.libraries = (scope.libraries || []).concat(data.libs);
              });
        };

        scope.fetchOrgLibraries = function (org, offset, limit) {
          org.hasMoreLibraries = false;
          return orgProfileService.getOrgLibraries(org.id, offset, limit + 1)
            .then(function (data) {
              org.hasMoreLibraries = data.libraries.length === limit + 1;
              data.libraries.splice(limit);
              org.libraries = (org.libraries || []).concat(data.libraries);
            });
        };

        scope.fetchingUserLibraries = false;
        scope.viewMoreOwnLibraries = function () {
          scope.fetchingUserLibraries = true;
          scope.fetchLibraries(scope.libraries.length, PAGE_SIZE).then(function() {
            scope.fetchingUserLibraries = false;
          });
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedViewMoreMyLibraries'
          });
        };

        scope.viewMoreOrgLibraries = function (org) {
          org.fetchingLibraries = true;
          scope.fetchOrgLibraries(org, org.libraries.length, PAGE_SIZE).then(function() {
            org.fetchingLibraries = false;
          });
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedViewMoreTeamLibraries',
            'team' : org.id
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
              scope.viewMoreOrgLibraries(org);
              $state.reload();
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
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedCreateTeam'
          });

          if (!profileService.prefs.hide_company_name) {
            profileService.savePrefs({ hide_company_name: true });
          }

          $state.go('teams.new');
        };

        scope.openLearnMoreModal = function () {
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action': 'learnMoreTeams'
          });

          modalService.open({
            template: 'profile/learnMoreModal.tpl.html',
            modalData: {
              companyName: scope.companyName,
              triggerCreateTeam: function () {
                $analytics.eventTrack('user_clicked_page', {
                  'type' : pageName(),
                  'subType' : 'leftHandNav',
                  'action' : 'clickedCreateTeamLearnMore'
                });
                scope.createTeam();
              }
            }
          });
        };

        scope.onClickedMyProfile = function() {
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedGoToMyProfile'
          });
        };

        scope.onClickedMyLibrary = function(library) {
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedGoToMyProfile',
            'library': library.id
          });
        };


        scope.onClickedTeam = function(team) {
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedGoToTeam',
            'team' : team.id
          });
        };

        scope.onClickedTeamLibrary = function(team, library) {
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedGoToLibrary',
            'team' : team.id,
            'library' : library.id
          });
        };

        reloadData(profileService.me);
        [
          $rootScope.$on('refreshLeftHandNav', function() {
            reloadData();
          })
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });
      }
    };
  }
]);
