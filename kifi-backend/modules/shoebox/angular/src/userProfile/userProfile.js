'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', 'userProfileStubService',
  function ($scope, userProfileStubService) {
    function init() {
      console.log('I am on the USER PROFILE page!');
    }

    init();
  }
]);
