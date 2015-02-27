'use strict';

angular.module('kifi')

.directive('kfLibraryFollowers', [
  '$location', '$window', '$rootScope', 'libraryService', 'routeService',
  function ($location, $window, $rootScope, libraryService, routeService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'libraries/libraryFollowers.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        //
        // Smart Scroll
        //
        scope.moreFollowers = true;
        scope.followerList = [];
        scope.followerScrollDistance = '100%';

        scope.isFollowerScrollDisabled = function () {
          return !(scope.moreFollowers);
        };
        scope.followerScrollNext = function () {
          pageFollowers();
        };

        var pageSize = 10;
        scope.offset = 0;
        var loading = false;

        function pageFollowers() {
          if (loading) { return; }
          if (scope.library.id) {
            loading = true;
            libraryService.getMoreMembers(scope.library.id, pageSize, scope.offset).then(function (resp) {
              var members = resp.members;
              loading = false;
              if (members.length === 0) {
                scope.moreFollowers = false;
              } else {
                scope.moreFollowers = true;
                scope.offset += 1;
                members = _.reject(members, function(m) { return m.lastInvitedAt; });
                members.forEach(function (member) {
                  member.profileUrl = routeService.getProfileUrl(member.username);
                });
                scope.followerList.push.apply(scope.followerList, members);
              }
            });
          }
        }

        function augmentLibrary() {
          // The passed-in library may have owner information on different properties;
          // normalize the properties (scope.library properties override scope.library.owner properties).
          scope.library.owner = scope.library.owner || {};
          scope.library.owner.profileUrl = scope.library.ownerProfileUrl || scope.library.owner.profileUrl;
        }

        scope.close = function () {
          kfModalCtrl.close();
        };

        //
        // On link.
        //
        if (scope.modalData) {
          scope.library = _.cloneDeep(scope.modalData.library);
          augmentLibrary();

          scope.modalTitle = scope.library.name;
          scope.currentPageOrigin = scope.modalData.currentPageOrigin;
        }
      }
    };
  }
]);
