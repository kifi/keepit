'use strict';

angular.module('kifi')

.directive('kfUserProfileUserList', [
  function () {
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
      templateUrl: 'userProfile/userProfileUserList.tpl.html'
    };
  }
]);