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
            $window.location.href = (reason && reason.redirect) || 'https://www.kifi.com/signup';
          })(reason);
        };


        scope.onSuccess = function () {
          (handlers.onSuccess || function () {
            return true;
            //$window.location.reload();
          })();
        };

        $compile(template)(scope); // This immediately launches the modal
      }
    };

    return api;
  }
])

.controller('SignupCtrl', [
  '$scope', '$FB', '$twitter', 'modalService', 'registrationService', '$window', 'installService', '$q', '$log', '$rootScope', '$location',
  function ($scope, $FB, $twitter, modalService, registrationService, $window, installService, $q, $log, $rootScope, $location) {

    // Shared data across several modals

    $scope.userData = $scope.userData || {};
    $scope.showTwitter = _.has($location.search(), 'twitter'); // todo (aaron): remove for twitter launch

    var encodedCurrentPage = $window.encodeURIComponent($location.url().split('?')[0]); //remove query params when redirecting back to this page
    $scope.twitterSignupUrl = '/signup/twitter?redirect=' + encodedCurrentPage; // twitter signup url with redirect

    function setModalScope($modalScope, onClose) {
      $modalScope.close = modalService.close;
      $modalScope.$on('$destroy', function () {
        $scope.registerFinalizeSubmitted = false;
        $scope.requestActive = false;
        if (typeof onClose === 'function') {
          onClose($modalScope);
        }
      });
    }

    $scope.fieldHasError = function (field) {
      var hasError = field.$invalid && $scope.registerFinalizeSubmitted;
      return hasError;
    };

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
          });
        }
      });
    };

    var registerModal = function () {
      if ($scope.userData.libraryId) {
        $rootScope.$emit('trackLibraryEvent', 'view', { type: 'signupLibrary' });
      }

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

      if ($scope.userData.libraryId) {
        $rootScope.$emit('trackLibraryEvent', 'click', { type: 'signupLibrary', action: 'clickSignUpButton' });
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

    $scope.fbAuthFromLibrary = function () {
      if ($scope.userData.libraryId) {
        $rootScope.$emit('trackLibraryEvent', 'click', { type: 'signupLibrary', action: 'clickAuthFacebook' });
      }
      return $scope.fbAuth();
    };

    $scope.fbAuth = function () {
      if ($FB.failedToLoad) {
        $scope.onError({'code': 'fb_blocked', redirect: 'https://www.kifi.com/signup/facebook'});
        return;
      }
      $scope.requestActive = true;
      facebookLogin().then(function (fbResp) {
        if (!fbResp) {
          $scope.onError({'code': 'fb_blocked', redirect: 'https://www.kifi.com/signup/facebook'});
          return;
        } else if (fbResp.error) {
          $scope.requestActive = false;
          return;
        } else if (fbResp.status === 'connected') {
          var fbMeP = $FB.api('/me', {});
          var regP = registrationService.socialRegister('facebook', fbResp.authResponse);

          $q.all([fbMeP, regP]).then(function (responses) {
            $scope.requestActive = false;
            var fbMe = responses[0];
            var resp = responses[1];

            $scope.userData.firstName = fbMe.first_name;
            $scope.userData.lastName = fbMe.last_name;
            $scope.userData.email = fbMe.email;

            if (resp.code === 'user_logged_in') {
              // todo: follow or other action
              $rootScope.$emit('appStart');
              modalService.close();
              $scope.onSuccess();
            } else if (resp.code && resp.code === 'continue_signup') {
              // todo: handle case when emails match, so backend auto-logs in user - andrew
              modalService.close();
              $scope.userData.method = 'social';
              registerFinalizeModal();
            } else if (resp.code && resp.code === 'connect_option') {
              // todo, figure out what this could be, handle errors - andrew
              $scope.onError({'code': 'connect_option', redirect: resp.uri});
            }
          })['catch'](function (err) {
            // Combo request failed. Would love to get logs of this.
            $scope.requestActive = false;
            $scope.onError({'code': 'remote_social_fail', 'error': err});
          });
        }
      })['catch'](function() {
        // Facebook login failed. Usually an adblocker.
        $scope.requestActive = false;
        $scope.onError({'code': 'fb_blocked', redirect: 'https://www.kifi.com/signup/facebook'});
        return;
      });
    };

    // 2nd Register modal
    var registerFinalizeModal = function () {
      if ($scope.userData.libraryId) {
        $rootScope.$emit('trackLibraryEvent', 'view', { type: 'signup2Library' });
      }

      $scope.requestActive = false;
      $scope.registerFinalizeSubmitted = false;
      setModalScope(modalService.open({
        template: 'signup/registerFinalizeModal.tpl.html',
        scope: $scope
      }));
    };

    $scope.registerFinalizeSubmit = function (form) {
      $scope.registerFinalizeSubmitted = true;
      $scope.requestActive = true;
      var fields;
      if (!form.$valid) {
        $scope.requestActive = false;
        return false;
      } else if ($scope.userData.method === 'social') {
        fields = {
          email: $scope.userData.email,
          password: $scope.userData.password,
          firstName: $scope.userData.firstName,
          lastName: $scope.userData.lastName,
          libraryPublicId: $scope.userData.libraryId
        };

        registrationService.socialFinalize(fields).then(function () {
          // todo: do we need to handle the return resp?
          modalService.close();
          thanksForRegisteringModal();
        })['catch'](function () {
          // Would love to get logs of this.
          $scope.onError({'code': 'social_finalize_fail', redirect: 'https://www.kifi.com/signup'});
        });
      } else { // email signup
        fields = {
          email: $scope.userData.email,
          password: $scope.userData.password,
          firstName: $scope.userData.firstName,
          lastName: $scope.userData.lastName,
          libraryPublicId: $scope.userData.libraryId
        };

        registrationService.emailFinalize(fields).then(function () {
          // todo: do we need to handle the return resp?
          modalService.close();
          thanksForRegisteringModal();
        })['catch'](function (resp) {
          if (resp.data && resp.data.error === 'user_exists_failed_auth') {
            $scope.requestActive = false;
            $scope.emailTaken = true;
            return;
          } else {
            $scope.onError({'code': 'email_fail', redirect: 'https://www.kifi.com/signup'});
          }
        });
      }
    };

    // 3rd confirm modal
    var thanksForRegisteringModal = function () {
      $scope.requestActive = false;
      if (!installService.installedVersion) {
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

        if ($scope.userData.libraryId) {
          $rootScope.$emit('trackLibraryEvent', 'view', { type: 'installLibrary' });
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
      $rootScope.$emit('appStart');
    };

    registerModal();
  }
]);
