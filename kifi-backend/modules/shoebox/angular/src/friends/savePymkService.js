'use strict';

angular.module('kifi')

.factory('savePymkService', [function () {
  var savedPersonYouMayKnow = null;

  return {
    savePersonYouMayKnow: function (person) {
      savedPersonYouMayKnow = person;
    },

    getSavedPersonYouMayKnow: function () {
      return savedPersonYouMayKnow;
    }
  };
}]);
