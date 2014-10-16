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
        })['catch'](function (err) {
          console.log('regServ#error', err);
          return err.data || err;
        });
      }
    };

    var emailFinalize = function (fields) {
      console.log(fields);
      return $http.post(routeService.emailSignup, fields).then(function (resp) {
        console.log('emaFin#succ', resp);
        return resp.data;
      })['catch'](function (err) {
        console.log('emaFin#error', err);
        return err.data || err;
      });
    };

    return {
      socialRegister: socialRegister,
      emailFinalize: emailFinalize
    };
  }
]);
