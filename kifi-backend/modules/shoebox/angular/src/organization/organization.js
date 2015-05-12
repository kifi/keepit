'use strict';

angular.module('kifi')

.controller('OrganizationCtrl', [
  '$scope', '$analytics', '$location', '$rootScope', '$state', '$window', 'profile',
  'inviteService', 'originTrackingService', 'profileService', 'installService', 'modalService', 'initParams', 'userProfileActionService',
  function ($scope, $analytics, $location, $rootScope, $state, $window, profile,
            inviteService, originTrackingService, profileService, installService, modalService, initParams, userProfileActionService) {

    $scope.profile = _.cloneDeep(profile);
    $scope.libraryType = 'own';
    $scope.me = profileService.me;

    var username = $location.host().split('.')[0];
    var fetchPageSize = 12;
    var fetchPageNumber;
    var hasMoreLibraries;
    var loading;
    var newLibraryIds;

    $scope.libraries = null;

    function resetAndFetchLibraries() {
      fetchPageNumber = 0;
      hasMoreLibraries = true;
      loading = false;
      newLibraryIds = [];
      $scope.libraries = null;
      $scope.fetchLibraries();
    }

    function augmentLibrary(owner, following, lib) {
      owner = lib.owner || owner;
      lib.path = '/' + owner.username + '/' + lib.slug;
      lib.owner = owner;
      if (lib.following == null && following != null) {
        lib.following = following;
      }
      return lib;
    }

    $scope.fetchLibraries = function () {
      if (loading) {
        return;
      }
      loading = true;

      var filter = $scope.libraryType;
      userProfileActionService.getLibraries(username, filter, fetchPageNumber, fetchPageSize).then(function (data) {
        if ($scope.libraryType === filter) {
          hasMoreLibraries = data[filter].length === fetchPageSize;

          var isMyProfile = $scope.profile.id === $scope.me.id;
          var owner = filter === 'own' ? _.extend({username: username}, $scope.profile) : null;
          var following = isMyProfile ? (filter === 'following' ? true : (filter === 'invited' ? false : null)) : null;

          var filteredLibs = data[filter];
          if (filter === 'own' && isMyProfile && newLibraryIds.length) {
            _.remove(filteredLibs, function (lib) {
              return _.contains(newLibraryIds, lib.id);
            });
          }

          $scope.libraries = ($scope.libraries || []).concat(filteredLibs.map(augmentLibrary.bind(null, owner, following)));

          fetchPageNumber++;
          loading = false;
        }
      });
    };

    $scope.hasMoreLibraries = function () {
      return hasMoreLibraries;
    };

    resetAndFetchLibraries();

  }


]);
