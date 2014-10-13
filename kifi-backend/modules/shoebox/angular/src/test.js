'use strict';

angular.module('kifi')

.controller('TestCtrl', [
  '$scope', '$FB', 'modalService',
  function ($scope, $FB, modalService) {
    var authFb = function () {
      return $FB.getLoginStatus().then(function (loginStatus) {
        if (loginStatus.status === 'connected') {
          return loginStatus;
        } else {
          return $FB.login({scope:'user_friends,email'}).then(function (loginResult) {
            if (loginResult.status === 'unknown' || !loginResult.authResponse) {
              return {'error': 'user_denied_login'};
            } else {
              return loginResult;
            }
          }, function (a) {
            return {'error': a.error || 'login_error'};
          });
        }
      }, function (a) {
        return {'error': a.error || 'status_error'};
      });
    };

    $scope.modal = function () {
      modalService.open({
        template: 'libraries/registerModal.tpl.html',
        scope: $scope
      });
    };

    $scope.close = modalService.close;

    window.setTimeout(function () {
      $scope.modal();
    }, 10);

    $scope.fb = function () {
      authFb().then(function (end) {
        console.log('got the end!', end);
      });
    };
  }
]);
