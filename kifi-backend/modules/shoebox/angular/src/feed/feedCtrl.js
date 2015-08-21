'use strict';

angular.module('kifi')

.controller('FeedCtrl', [
  '$scope', 'net', 'ml', function($scope, net, ml) {
    $scope.feed = [];

    (function() {
      ml.specs.getsFeed = new ml.Spec([
        new ml.Assert('Feed retrieved in 3 seconds or less', 3000),
        new ml.Expect('Feed returns a list', function(data) { return data.length; })
      ]);
      $scope.feed = [];
      net.getFeed(10).then(function(response) {
        ml.specs.getsFeed.respond([undefined, response.data.keeps]);
        $scope.feed = response.data.keeps;
      });
    })();
  }
]);
