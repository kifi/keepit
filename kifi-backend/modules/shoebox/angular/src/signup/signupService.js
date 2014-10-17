'use strict';

angular.module('kifi')

.factory('signupService', [
  '$rootScope', '$compile', '$window',
  function ($rootScope, $compile, $window) {


    var api = {
      register: function (userData, handlers) {

        var template = angular.element('<div ng-controller="SignupCtrl"></div>');
        var scope = $rootScope.$new();

        userData = userData || {};
        handlers = handlers || {};

        scope.userData = userData;

        scope.onError = function (reason) {
          (handlers.onError || function (reason) {
            scope.$destroy();
            $window.location.href = (reason && reason.redirect) || 'https://www.kifi.com/signup';
          })(reason);
        };


        scope.onSuccess = function () {
          (handlers.onSuccess || function () {
            scope.$destroy();
            $window.location.reload();
          })();
        };

        scope.$on('$destroy', function () {
          console.log('destroyed');
        });

        $compile(template)(scope); // This immediately launches the modal;
      }
    };

    return api;
  }
])

.controller('SignupCtrl', [
  '$scope', '$FB', 'modalService', 'registrationService', '$window', 'installService', '$q', '$log',
  function ($scope, $FB, modalService, registrationService, $window, installService, $q, $log) {

    // Shared data across several modals
    function resetUserData() {
      $scope.userData = $scope.userData || {};
      $scope.userData.firstName = null;
      $scope.userData.lastName = null;
      $scope.userData.password = null;
    }

    resetUserData();

    function setModalScope($modalScope, onClose) {
      $modalScope.close = modalService.close;
      $modalScope.$on('$destroy', function () {
        $scope.registerFinalizeSubmitted = false;
        if (typeof onClose === 'function') {
          onClose($modalScope);
        }
      });
    }

    // First Register modal

    var facebookLogin = function () {
      return $FB.getLoginStatus().then(function (loginStatus) {
        if (loginStatus.status === 'connected') {
          return loginStatus;
        } else {
          return $FB.login({'scope':'public_profile,user_friends,email', 'return_scopes': true}).then(function (loginResult) {
            if (loginResult.status === 'unknown' || !loginResult.authResponse) {
              return {'error': 'user_denied_login'};
            } else {
              return loginResult;
            }
          }, function (a) {
            return {'error': a.error || 'login_error'};
          });
        }
      });
    };

    var registerModal = function () {
      setModalScope(modalService.open({
        template: 'signup/registerModal.tpl.html',
        scope: $scope
      }));
    };

    $scope.submitEmail = function (form) {
      $scope.registerFinalizeSubmitted = true;
      if (!form.$valid) {
        return false;
      }
      modalService.close();
      $scope.userData.method = 'email';
      registerFinalizeModal();
    };

    $scope.fbInit = function () {
      if (!$FB.failedToLoad) {
        $FB.init();
      }
    };

    $scope.fbAuth = function () {
      if ($FB.failedToLoad) {
        $scope.onError({'code': 'fb_blocked', redirect: 'https://www.kifi.com/signup/facebook'});
        return;
      }
      facebookLogin().then(function (fbResp) {
        if (!fbResp) {
          $scope.onError({'code': 'fb_blocked', redirect: 'https://www.kifi.com/signup/facebook'});
          return;
        } else if (fbResp.status === 'connected') {
          // todo: remove?
          var fbMeP = $FB.api('/me', {});
          var regP = registrationService.socialRegister('facebook', fbResp.authResponse);
          $q.all([fbMeP, regP]).then(function (responses) {
            var fbMe = responses[0];
            var resp = responses[1];
            $scope.userData.firstName = fbMe.first_name;
            $scope.userData.lastName = fbMe.last_name;
            $scope.userData.email = fbMe.email;
            if (resp.code && resp.code === 'user_logged_in') {
              // todo: follow or other action
              $scope.onSuccess();
            } else if (resp.code && resp.code === 'continue_signup') {
              $log.log('test#continue_signup', resp);
              modalService.close();
              $scope.userData.method = 'social';
              registerFinalizeModal();
            } else {
              $log.log('test#unknown_code??', resp);
            }
          })['catch'](function (err) {
            // we failed. bail.
            $scope.onError({'code': 'remote_social_fail', 'error': err});
          });
        }
      })['catch'](function() {
        $scope.onError({'code': 'fb_blocked', redirect: 'https://www.kifi.com/signup/facebook'});
        return;
      });
    };

    // 2nd Register modal
    var registerFinalizeModal = function () {
      $scope.registerFinalizeSubmitted = false;
      setModalScope(modalService.open({
        template: 'signup/registerFinalizeModal.tpl.html',
        scope: $scope
      }));
    };

    $scope.fieldHasError = function (field) {
      var hasError = field.$invalid && $scope.registerFinalizeSubmitted;
      return hasError;
    };

    $scope.registerFinalizeSubmit = function (form) {
      $scope.registerFinalizeSubmitted = true;
      var fields;
      if (!form.$valid) {
        return false;
      } else if ($scope.userData.method === 'social') {
        fields = {
          email: $scope.userData.email,
          password: $scope.userData.password,
          firstName: $scope.userData.firstName,
          lastName: $scope.userData.lastName,
          libraryPublicId: $scope.libraryId || ($scope.library && $scope.library.id)
        };

        registrationService.socialFinalize(fields).then(function (data) {
          $log.log('succz', data);
          modalService.close();
          thanksForRegisteringModal();
        })['catch'](function (err) {
          $log.log('errz2', err);
        });
      } else { // email signup
        fields = {
          email: $scope.userData.email,
          password: $scope.userData.password,
          firstName: $scope.userData.firstName,
          lastName: $scope.userData.lastName,
          libraryPublicId: $scope.libraryId || ($scope.library && $scope.library.id)
        };

        registrationService.emailFinalize(fields).then(function (data) {
          $log.log('succz', data);
          modalService.close();
          thanksForRegisteringModal();
        })['catch'](function (err) {
          $log.log('errz3', err);
        });
      }
    };

    // 3rd confirm modal
    var thanksForRegisteringModal = function () {
      if (true/*!installService.detectIfIsInstalled()*/) {
        if (installService.canInstall) {
          if (installService.isValidChrome) {
            $scope.platformName = 'Chrome';
          } else if (installService.isValidFirefox) {
            $scope.platformName = 'Firefox';
          }
          $scope.installExtension = installService.triggerInstall;
          $scope.thanksVersion = 'installExt';
        } else {
          $scope.thanksVersion = 'notSupported';
        }
        setModalScope(modalService.open({
          template: 'signup/thanksForRegisteringModal.tpl.html',
          scope: $scope
        }), function onClose() {
          $scope.onSuccess();
        });
      } else {
        $scope.onSuccess();
      }
    };

    registerModal();
  }
]);
