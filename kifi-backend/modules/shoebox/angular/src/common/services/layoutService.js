'use strict';

angular.module('kifi.layoutService', [])

.factory('layoutService', [
  function () {
    var sidebarActive = false;

    var api = {
      sidebarActive: function () {
        return sidebarActive;
      },
      toggleSidebar: function () {
        sidebarActive = !sidebarActive;
      }
    };

    return api;
  }
]);
