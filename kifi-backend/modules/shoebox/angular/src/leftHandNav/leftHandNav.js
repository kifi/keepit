'use strict';

angular.module('kifi')

.directive('kfLeftHandNav', [
  '$rootElement', '$rootScope', '$document', '$q', 'profileService', 'userProfileActionService', 'orgProfileService', '$state',
  function ($rootElement, $rootScope, $document, $q, profileService, userProfileActionService, orgProfileService, $state) {
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
            o.declined = false;
          });
          scope.orgs = scope.orgs.concat(scope.me.pendingOrgs);
        }

        if (scope.me.potentialOrgs) {
          scope.me.potentialOrgs.forEach(function (o) {
            o.potential = true;
            o.declined = false;
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
              .getLibraries(scope.me.username, filter, pageNumber, pageSize + 1)
              .then(function (data) {
                scope.loaded = true;
                return data[filter];
              }).then(function(libs) {
                scope.hasMoreUserLibaries = libs.length === pageSize + 1;
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
          org.declined = true;
        };

        // scope.verifyEmail = function(email, org) {
        //   orgProfileService.trackEvent('user_clicked_page', org,
        //     {
        //       'type': 'homeFeed',
        //       'action': 'verifyOrgEmail'
        //     }
        //   );
        //   showVerificationAlert(email);
        //   profileService.sendMemberConfirmationEmail(org.id, email);
        // };

        // scope.hideOrgDomain = function(org) {
        //   org.declined = true;
        //   orgProfileService.trackEvent('user_clicked_page', org,
        //     {
        //       'type': 'homeFeed',
        //       'action': 'hideOrgDomain'
        //     }
        //   );
        //   profileService.hideOrgDomain(org);
        // };
      }
    };
  }
]);
