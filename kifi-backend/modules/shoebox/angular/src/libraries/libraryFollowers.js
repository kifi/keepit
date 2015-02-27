'use strict';

angular.module('kifi')

.directive('kfLibraryFollowers', [
  'libraryService',
  function (libraryService) {
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
                scope.followerList.push.apply(scope.followerList, members);
              }
            });
          }
        }

        scope.close = function () {
          kfModalCtrl.close();
        };

        //
        // On link.
        //
        if (scope.modalData) {
          scope.library = scope.modalData.library;
          scope.modalTitle = scope.library.name;
          scope.currentPageOrigin = scope.modalData.currentPageOrigin;
        }
      }
    };
  }
]);
