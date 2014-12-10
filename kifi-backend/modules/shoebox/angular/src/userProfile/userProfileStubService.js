'use strict';

angular.module('kifi')

.factory('userProfileStubService', ['$q',
  function ($q) {
    var getProfile = function (userName) {
      var profiles = {
        'lydialaurenson': {
          id: 'f2f153db-6952-4b32-8854-8c0e452e1c64',
          firstName: 'Lydia',
          lastName: 'Laurenson',
          pictureName: 'FSPNP.jpg',
          numLibraries: 7,
          numFriends: 77,
          numFollowers: 777,
          helpedRekeep: 7777
        }
      };

      return profiles[userName] ?
        $q.when(profiles[userName]) :
        $q.when(null);
    };

    var getLibraries = function (userId) {
      var libraries = {
        'f2f153db-6952-4b32-8854-8c0e452e1c64': {  // Lydia Laurenson
          libraries: [
            {  // Library #1
              id: 'l9Rre2MSpAPb',
              name: 'Halloween!',
              slug: 'halloween',
              numKeeps: 7,
              numFollowers: 77,
              followersToDisplay: [
                {  // Follower #1-1
                  firstName: 'Andrew',
                  lastName: 'Conner',
                  pictureName: 'W4wuZ.jpg',
                  userName: 'andrew'
                },
                {  // Follower #1-2
                  firstName: 'Mark',
                  lastName: 'Yoshitake',
                  pictureName: 'bwKWO.jpg',
                  userName: 'markyoshitake'
                }
              ]
            },
            {  // Library #2
              id: 'l14bTasWaiYK',
              name: 'Cross-Cultural Digital Media',
              slug: 'crosscultural-social-media',
              numKeeps: 8,
              numFollowers: 88,
              followersToDisplay: [
                {  // Follower #2-1
                  firstName: 'Mark',
                  lastName: 'Yoshitake',
                  pictureName: 'bwKWO.jpg',
                  userName: 'markyoshitake'
                },
                {  // Follower #2-2
                  firstName: 'Andrew',
                  lastName: 'Conner',
                  pictureName: 'W4wuZ.jpg',
                  userName: 'andrew'
                }
              ]
            }
          ]
        }
      };

      return libraries[userId] ?
        $q.when(libraries[userId]) :
        $q.when(null);
    };

    var getFollowingLibraries = function (/* userId */) {
      return null;
    };

    var getFriends = function (/* userId */) {
      return null;
    };

    var getFollowers = function (/* userId */) {
      return null;
    };

    var getHelped = function (/* userId */) {
      return null;
    };

    var api = {
      getProfile: getProfile,
      getLibraries: getLibraries,
      getFollowingLibraries: getFollowingLibraries,
      getFriends: getFriends,
      getFollowers: getFollowers,
      getHelped: getHelped
    };

    return api;
  }
]);