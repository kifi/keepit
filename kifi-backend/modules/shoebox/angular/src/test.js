'use strict';

angular.module('kifi')

.controller('TestCtrl', [
  '$scope', '$FB', 'modalService', 'registrationService', '$window',
  function ($scope, $FB, modalService, registrationService, $window) {

    // Shared data across several modals
    function resetUserData() {
      $scope.userData = $scope.userData || {};
      $scope.userData.firstName = null;
      $scope.userData.lastName = null;
      $scope.userData.password = null;
    }

    resetUserData();

    function setModalScope($modalScope) {
      $modalScope.close = modalService.close;
      $modalScope.$on('$destroy', function () {
        $scope.registerFinalizeSubmitted = false;
        resetUserData();
      });
    }

    $scope.$error = {};

    // First Register modal

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

    window.setTimeout(function () {
      $scope.register();
    }, 10);

    $scope.register = function () {
      setModalScope(modalService.open({
        template: 'libraries/registerModal.tpl.html',
        scope: $scope
      }));
    };

    $scope.submitEmail = function () {
      // validate email???!?
      modalService.close();
      $scope.userData.method = 'email';
      $scope.registerFinalizeModal();
    };

    $scope.fbInit = function () {
      if (!$FB.failedToLoad) {
        $FB.init().then(function (resp) {
          console.log('done with fb', resp);
        });
      }
    };

    $scope.fbAuth = function () {
      if ($FB.failedToLoad) {
        $window.location.href = '/signup/facebook';
        return;
      }
      authFb().then(function (fbResp) {
        if (!fbResp) {
          //error
          return;
        } else if (fbResp.status === "connected") {
          // todo: remove?
          $FB.api('/me', {}, function(response) {
            $scope.userData.firstName = response.first_name;
            $scope.userData.lastName = response.last_name;
            $scope.userData.email = response.email;
          });

          registrationService.socialRegister('facebook', fbResp).then(function (resp) {
            if (resp.code && resp.code === 'user_logged_in') {
              console.log('test#user_logged_in', resp);
              // reload page?? we're done
            } else if (resp.code && resp.code === 'continue_signup') {
              console.log('test#continue_signup', resp);
              modalService.close();
              $scope.userData.method = 'social';
              $scope.registerFinalizeModal();
            } else {
              console.log('test#unknown_code??', resp);
            }
          });
        }
      });
    };

    // 2nd Register modal
    $scope.registerFinalizeModal = function () {
      setModalScope(modalService.open({
        template: 'libraries/registerFinalizeModal.tpl.html',
        scope: $scope
      }));
    };

    $scope.fieldHasError = function (field) {
      var hasError = field.$invalid && $scope.registerFinalizeSubmitted;
      return hasError;
    }

    $scope.registerFinalizeSubmit = function (form) {
      $scope.registerFinalizeSubmitted = true;
      if (!form.$valid) {
        return false;
      } else if ($scope.userData.method === 'social') {

      } else { // email signup

      }
    };

  }
]);
