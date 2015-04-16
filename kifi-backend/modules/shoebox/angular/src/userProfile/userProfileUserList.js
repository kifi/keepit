'use strict';

angular.module('kifi')

.directive('kfUserProfileUserList', [
  'profileService',
  function (profileService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        users: '=kfUserProfileUserList',
        listTitle: '@',
        hasMore: '&',
        fetchMore: '&',
        emptyText: '@'
      },
      templateUrl: 'userProfile/userProfileUserList.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;
      }
    };
  }
]);
