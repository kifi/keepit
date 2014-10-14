'use strict';

angular.module('kifi')

.factory('registrationService', ['$http', '$q', 'routeService',
  function ($http, $q, routeService) {
    var allowedProviders = ['facebook'];
    var socialRegister = function (provider, oauth2TokenInfo) {
      if (allowedProviders.indexOf(provider) === -1) {
        return $q.reject('invalid_provider');
      } else {
        return $http.post(routeService.socialRegister(provider), oauth2TokenInfo).then(function (resp) {
          console.log('regServ#succ', resp);
          return resp.data || resp;
        })['catch'](function (err) {
          console.log('regServ#error', err);
          return err.data || err;
        });
      }
    };

    return {
      socialRegister: socialRegister
    };
  }
]);
