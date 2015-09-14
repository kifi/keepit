'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', 'orgProfileService', 'net', '$location', 'ml',
  function($scope, orgProfileService, net, $location, ml) {
    $scope.orgName = '';
    $scope.createOrg = function() {
      ml.specs.createOrg = new ml.Spec([
        new ml.Expect('Org was assigned a handle', function(handle) { return (handle !== undefined); }),
        new ml.Assert('Created org was returned in 3 seconds or less', 3000)
      ]);

      net.createOrg({name: $scope.orgName}).then(function(response) {
        var handle = response.data.organization.handle;
        $location.url('/' + handle);
        ml.specs.createOrg.respond(handle);
      });
    };
  }
]);
