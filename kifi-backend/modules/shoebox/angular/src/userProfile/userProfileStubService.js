'use strict';

angular.module('kifi')

.factory('userProfileStubService', ['$q',
  function ($q) {
    //
    // Internal functions.
    //
    function getMyLibraries(userId) {
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
        $q.when(libraries[userId].libraries) :
        $q.when(null);
    }

    function getFollowingLibraries(/* userId */) {
      return null;
    }

    function getInvitedLibraries(/* userId */) {
      return null;
    }

    function getFriends(/* userId */) {
      return null;
    }

    function getFollowers(/* userId */) {
      return null;
    }


    //
    // Functions exposed on the API.
    //
    var getProfile = function (username) {
      var profiles = {
        'lydialaurenson': {
          id: 'f2f153db-6952-4b32-8854-8c0e452e1c64',
          firstName: 'Lydia',
          lastName: 'Laurenson',
          pictureName: 'FSPNP.jpg',
          numKeeps: 77777,
          numLibraries: 7,
          numFriends: 77,
          numFollowers: 777,
          helpedRekeep: 7777,
          friendsWith: false
        }
      };

      return profiles[username] ?
        $q.when(profiles[username]) :
        $q.when(null);
    };

    var getLibraries = function (userId, libraryType) {
      switch (libraryType) {
        case 'my':
          return getMyLibraries(userId);
        case 'following':
          return getFollowingLibraries(userId);
        case 'invited':
          return getInvitedLibraries(userId);
        default:
          // Unknown type? Return empty array as libraries.
          return $q.when([]);
      }
    };

    var getUsers = function (userId, userType) {
      switch (userType) {
        case 'friends':
          return getFriends(userId);
        case 'followers':
          return getFollowers(userId);
        default:
          // Unknown type? Return empty array as users.
          return $q.when([]);
      }
    };

    var getHelped = function (/* userId */) {
      return null;
    };

    var api = {
      getProfile: getProfile,
      getLibraries: getLibraries,
      getPeople: getUsers,
      getHelped: getHelped
    };

    return api;
  }
]);