'use strict';

angular.module('kifi')

.factory('userPersonaActionService', [
  '$http', '$q', 'routeService', 'Clutch',
  function ($http, $q, routeService, Clutch) {
    var clutchParams = {
      cacheDuration: 10000
    };

    function getData(res) {
      return res.data;
    }

    var userPersonaService = new Clutch(function () {
      return $http.get(routeService.getPersonas()).then(getData);
    }, clutchParams);

    var api = {
      getPersonas: function () {
        return userPersonaService.get();
      },

      selectPersonas: function (personaNames) {
        var body = { personas : personaNames };
        return $http.post(routeService.getPersonas(), body);
      },

      addPersona: function(personaId) {
        return $http.post(routeService.addPersona(personaId));
      },

      removePersona: function(personaId) {
        return $http.delete(routeService.removePersona(personaId)); // jshint ignore:line
      }
    };

    return api;
  }
]);
