'use strict';

angular.module('kifi')

.directive('kfSeeMutualFriends', ['$rootScope', '$timeout', 'env', 'friendService', 'inviteService', 'userService',
  function ($rootScope, $timeout, env, friendService, inviteService, userService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/seeMutualFriends.tpl.html',
      require: '^kfModal',
      link: function (scope, element, attrs, kfModalCtrl) {
        scope.actionText = 'Add Friend';
        scope.clickable = true;

        function init() {
          updateScopeValues(scope.modalData.savedPymk);
          scope.inUserProfileBeta = userService.inUserProfileBeta();
        }

        // Helper function to update scope values based on passed-in person.
        function updateScopeValues (person) {
          scope.actionText = 'Add Friend';
          scope.clickable = true;

          scope.id = person.id;
          scope.name = person.fullName;
          scope.numMutualFriends = person.numMutualFriends;
          scope.pictureUrl = person.pictureUrl;
          scope.userProfileUrl = person.profileUrl;

          // Divide up list of mutual friends into list of pairs of mutual
          // friends for two-column display in modal.
          var mutualFriendsPairs = [];
          var mutualFriendPair = [];
          person.mutualFriends.forEach(function (mutualFriend, index) {
            mutualFriend.pictureUrl = friendService.getPictureUrlForUser(mutualFriend);
            mutualFriend.profileUrl = env.origin + '/' + mutualFriend.username;
            mutualFriendPair.push(mutualFriend);

            if (index % 2 !== 0) {
              mutualFriendsPairs.push(mutualFriendPair);
              mutualFriendPair = [];
            }

            // Flush last pair even if it has only one mutual friend.
            if ((index === person.mutualFriends.length - 1) && (mutualFriendPair.length > 0)) {
              mutualFriendsPairs.push(mutualFriendPair);
            }
          });

          scope.mutualFriendsPairs = mutualFriendsPairs;
        }

        scope.addFriend = function (id) {
          if (!scope.clickable) {
            return;
          }
          scope.clickable = false;

          inviteService.friendRequest(id).then(function () {
            scope.actionText = 'Sent!';
            inviteService.expireSocialSearch();
          }, function () {
            scope.actionText = 'Error. Retry?';
            scope.clickable = true;
            inviteService.expireSocialSearch();
          });
        };

        scope.close = function () {
          kfModalCtrl.close();
        };


        init();
      }
    };
  }
]);

