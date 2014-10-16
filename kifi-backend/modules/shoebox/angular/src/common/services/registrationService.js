'use strict';

angular.module('kifi')

.factory('registrationService', ['$http', '$q', 'routeService',
  function ($http, $q, routeService) {
    var allowedProviders = ['facebook'];
    var socialRegister = function (provider, oauth2TokenInfo) {
      if (allowedProviders.indexOf(provider) === -1) {
        return $q.reject('invalid_provider');
      } else {
        return $http.post(routeService.socialSignup(provider), oauth2TokenInfo).then(function (resp) {
          console.log('regServ#succ', resp);
          return resp.data || resp;
        });
      }
    };

    var socialFinalize = function (fields) {
      console.log(fields);
      return $http.post(routeService.socialFinalize, fields).then(function (resp) {
        console.log('socFin#succ', resp);
        return resp.data;
      });
    };

    var emailFinalize = function (fields) {
      console.log(fields);
      return $http.post(routeService.emailSignup, fields).then(function (resp) {
        console.log('emaFin#succ', resp);
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
