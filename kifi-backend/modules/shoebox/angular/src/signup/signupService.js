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
  '$scope', 'modalService', 'registrationService', '$window', 'installService',
  '$rootScope', '$location', 'routeService', '$state', 'util', '$analytics',
  function ($scope, modalService, registrationService, $window, installService,
    $rootScope, $location, routeService, $state, util, $analytics) {

    // Shared data across several modals

    $scope.userData = $scope.userData || {};

    function setModalScope($modalScope, onClose) {
      $modalScope.close = modalService.close;
      $modalScope.$on('$destroy', function () {
        if (typeof onClose === 'function') {
          onClose($modalScope);
        }
        $scope.registerFinalizeSubmitted = false;
        $scope.requestActive = false;
      });
    }

    $scope.fieldHasError = function (field) {
      var hasError = field.$invalid && $scope.registerFinalizeSubmitted;
      return hasError;
    };

    $scope.trackWebstore = function() {
      trackEvent('visitor_clicked_page', 'install', 'webstore');
    };

    $scope.trackGetChrome = function() {
      trackEvent('visitor_clicked_page', 'install', 'getChrome');
    };

    $scope.trackGetFirefox = function() {
      trackEvent('visitor_clicked_page', 'install', 'getFirefox');
    };

    function trackEvent(eventName, typeBase, action) {
      var currentState = $state.current.name;
      if (util.startsWith(currentState, 'library')) {
        typeBase += 'Library';
        $analytics.eventTrack(eventName, {type: typeBase, action: action});
      } else if (util.startsWith(currentState, 'userProfile')) {
        typeBase += 'UserProfile';
        $analytics.eventTrack(eventName, {type: typeBase, action: action});
      }
    }

    function emitTracking(eventType, typeBase, attributes) {
      var currentState = $state.current.name;
      if (util.startsWith(currentState, 'library')) {
        typeBase += 'Library';
        attributes = _.extend({type: typeBase}, attributes || {});
        $rootScope.$emit('trackLibraryEvent', eventType, attributes);
      } else if (util.startsWith(currentState, 'userProfile')) {
        typeBase += 'UserProfile';
        attributes = _.extend({type: typeBase}, attributes || {});
        $rootScope.$emit('trackUserProfileEvent', eventType, attributes);
      }
    }

    // First Register modal

    var registerModal = function () {
      emitTracking('view', 'signup');
      $scope.onLoginClick = function() {
        trackEvent('visitor_clicked_page', 'signup', 'login');
      };

      var params = $scope.userData ? {
        libraryId : $scope.userData.libraryId,
        intent : $scope.userData.intent
      } : {};
      $scope.facebookSignupPath = routeService.socialSignup('facebook', params);
      $scope.twitterSignupPath = routeService.socialSignup('twitter', params);

      $scope.emailSubmitted = false;

      setModalScope(modalService.open({
        template: 'signup/registerModal.tpl.html',
        scope: $scope
      }), function onClose() {
        if (!$scope.emailSubmitted) { // did not submit email, so closed modal
          trackEvent('visitor_clicked_page', 'signup', 'close');
        }
      });
    };

    $scope.submitEmail = function (form) {
      $scope.registerFinalizeSubmitted = true;
      $scope.emailSubmitted = true;
      if (!form.$valid) {
        return false;
      }
      emitTracking('click', 'signup', {action: 'clickedSignUpButton'});

      modalService.close();
      $scope.userData.method = 'email';
      registerFinalizeModal();
    };

    $scope.twAuth = function() {
      emitTracking('click', 'signup', {action: 'clickedAuthTwitter'});
    };

    $scope.fbAuth = function () {
      emitTracking('click', 'signup', {action: 'clickedAuthFacebook'});
    };

    // 2nd Register modal
    var registerFinalizeModal = function () {
      emitTracking('view', 'signup2');
      $scope.onToSClick = function() {
        trackEvent('visitor_clicked_page', 'signup2', 'termsOfService');
      };

      $scope.requestActive = false;
      $scope.registerFinalizeSubmitted = false;
      setModalScope(modalService.open({
        template: 'signup/registerFinalizeModal.tpl.html',
        scope: $scope
      }), function onClose() {
        if (!$scope.registerFinalizeSubmitted) { // did not submit registration & closed modal
          trackEvent('visitor_clicked_page', 'signup2', 'close');
        }
      });
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
          $location.url('/install');
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
          trackEvent('visitor_clicked_page', 'signup2', 'signup');
          $location.url('/install');
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

    registerModal();
  }
]);
