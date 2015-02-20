'use strict';

angular.module('kifi')

.factory('registrationService', ['$http', '$q', 'routeService',
  function ($http, $q, routeService) {
    var allowedProviders = ['facebook'];
    var socialRegister = function (provider, oauth2TokenInfo) {
      if (allowedProviders.indexOf(provider) === -1) {
        return $q.reject('invalid_provider');
      } else {
        return $http.post(routeService.socialSignupWithToken(provider), oauth2TokenInfo).then(function (resp) {
          return resp.data;
        });
      }
    };

    var socialFinalize = function (fields) {
      return $http.post(routeService.socialFinalize, fields).then(function (resp) {
        return resp.data;
      });
    };

    var emailFinalize = function (fields) {
      return $http.post(routeService.emailSignup, fields).then(function (resp) {
        return resp.data;
      });
    };

    return {
      socialRegister: socialRegister,
      socialFinalize: socialFinalize,
      emailFinalize: emailFinalize
    };
  }
]);
