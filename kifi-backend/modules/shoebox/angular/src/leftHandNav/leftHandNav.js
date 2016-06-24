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
        var mql = $window.matchMedia('(min-width: 480px)');
        $rootScope.leftHandNavIsOpen = mql.matches;
        var isMobile = !mql.matches;
        mql.addListener(handleMatchMedia);
        scope.$on('$destroy', function () {
          mql.removeListener(handleMatchMedia);
        });

        function handleMatchMedia(mqlEvent) {
          if ($rootScope.leftHandNavIsOpen && !mqlEvent.matches) {
            scope.$apply(function() {
              $rootScope.leftHandNavIsOpen = false;
            });
          }
          isMobile = !mql.matches;
        }

        var maybeClose = function() {
            $rootScope.leftHandNavIsOpen = !isMobile;
        };


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

        var initialLoadFailed = function () {
          scope.showUserAndOrgContent = false;
          scope.initialFetchFailed = true;
        };

        scope.reloadData = function(me, hideReload) {
          var futureMe = (me && $q.when(me)) || profileService.fetchMe();
          scope.showUserAndOrgContent = hideReload;
          scope.initialFetchFailed = false;
          futureMe.then(function (me) {
            scope.me = me;
            var numSections = (me.orgs && me.orgs.length || 0) + 1;
            var INITIAL_PAGE_SIZE = 2 + Math.floor(20 / numSections);
            return net.getInitialLeftHandRailInfo(INITIAL_PAGE_SIZE + 1).then(function(res) {
              var lhr = res.data.lhr;
              scope.initialFetchFailed = false;
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
          })['catch'](initialLoadFailed);
        };

        // TODO: REMOVE THIS HACK
        document.body.style.overflow = 'hidden';

        var PAGE_SIZE = 15;
        var pageName = function() {
          return $state.$current.name;
        };
        scope.fetchLibraries = function (offset, limit) {
          scope.hasMoreUserLibaries = false;
          return userProfileActionService
              .getBasicLibraries(scope.me.id, offset, limit + 1)
              .then(function (data) {
                scope.hasMoreUserLibaries = data.libs.length === limit + 1;
                data.libs.splice(limit);
                scope.libraries = (scope.libraries || []).concat(data.libs);
              })['catch'](function () {
                scope.hasMoreUserLibaries = true;
              });
        };

        scope.fetchOrgLibraries = function (org, offset, limit) {
          org.hasMoreLibraries = false;
          return orgProfileService.getOrgBasicLibraries(org.id, offset, limit + 1)
            .then(function (data) {
              org.hasMoreLibraries = data.libraries.length === limit + 1;
              data.libraries.splice(limit);
              org.libraries = (org.libraries || []).concat(data.libraries);
            })['catch'](function () {
              org.hasMoreLibraries = true;
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
          if (profileService.shouldBeWindingDown()) {
            modalService.showWindingDownModal();
          } else {
            $state.go('userProfile.libraries.own', { handle: scope.me.username, openCreateLibrary: true }, {reload: true});
          }
        };

        scope.createOrgLibrary = function (org) {
          if (profileService.shouldBeWindingDown()) {
            modalService.showWindingDownModal();
          } else {
            $state.go('orgProfile.libraries', { handle: org.handle, openCreateLibrary: true }, {reload: true});
          }
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
        var futureMe = (profileService.me && $q.when(profileService.me)) || profileService.fetchMe();
        var companyNameP = (Object.keys(profileService.prefs) === 0 ? profileService.fetchPrefs() : $q.when(profileService.prefs)).then(function(prefs) {
          return prefs.company_name;
        });

        $q.all([
          futureMe,
          companyNameP
        ])
        .then(function (results) {
          var me = results[0];
          var companyName = results[1];
          scope.me = me;

          if (profileService.prefs.hide_company_name) {
            scope.companyName = null;
          } else {
            var potentialName = companyName;
            if (!potentialName) {
              var emails = potentialCompanyEmails(scope.me.emails);
              potentialName = emails && emails[0] && getEmailDomain(emails[0].address);
            }
            if (potentialName) {
              if (!orgNameExists(potentialName) && scope.orgs.length === 0) {
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
          emails = emails || [];
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
          maybeClose();
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
          maybeClose();

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
          maybeClose();
        };

        scope.onClickedMyLibrary = function(library) {
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedGoToMyProfile',
            'library': library.id
          });
          maybeClose();
        };


        scope.onClickedTeam = function(team) {
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedGoToTeam',
            'team' : team.id
          });
          maybeClose();
        };

        scope.onClickedTeamLibrary = function(team, library) {
          $analytics.eventTrack('user_clicked_page', {
            'type' : pageName(),
            'subType' : 'leftHandNav',
            'action' : 'clickedGoToLibrary',
            'team' : team.id,
            'library' : library.id
          });
          maybeClose();
        };

        var reload = function () { scope.reloadData(); };
        scope.reloadData(profileService.me);
        [
          $rootScope.$on('refreshLeftHandNav', reload),
          $rootScope.$on('libraryCreated', reload),
          $rootScope.$on('libraryModified', reload),
          $rootScope.$on('libraryJoined', reload),
          $rootScope.$on('libraryLeft', reload),
          $rootScope.$on('libraryDeleted', reload),
          $rootScope.$on('orgCreated', reload),
          $rootScope.$on('orgMemberInviteAccepted', reload),
          $rootScope.$on('orgMemberInviteDeclined', reload),
          $rootScope.$on('orgMemberRemoved', reload),
          $rootScope.$on('orgProfileUpdated', reload),
          $rootScope.$on('orgAvatarUploaded', reload),
          $rootScope.$on('orgOwnershipTransferred', reload),
          $rootScope.$on('profileSettingLhrLibrarySorting', reload)
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });
      }
    };
  }
]);
