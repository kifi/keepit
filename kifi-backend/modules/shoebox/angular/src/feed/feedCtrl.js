'use strict';

angular.module('kifi')

.controller('FeedCtrl', [
  '$scope', 'net', 'ml', function($scope, net, ml) {
    $scope.feed = [];

    ml.specs.getsFeed = new ml.Spec([
      new ml.Assert('Feed retrieved in 3 seconds or less', 3000),
      new ml.Expect('Feed returns a list', function(data) { return data.length; })
    ]);
    net.getFeed(10).then(function(response) {
      $scope.feed = response.data.keeps;
      ml.specs.getsFeed.respond([null, response.data.keeps]);
    });
  }
]);
