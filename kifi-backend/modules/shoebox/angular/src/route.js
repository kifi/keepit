'use strict';

angular.module('kifi')

.config(['$httpProvider', '$locationProvider', '$stateProvider', '$urlRouterProvider', 'StripeCheckoutProvider', 'ORG_PERMISSION',
  function($httpProvider, $locationProvider, $stateProvider, $urlRouterProvider, StripeCheckoutProvider, ORG_PERMISSION) {
    $locationProvider
      .html5Mode(true)
      .hashPrefix('!');

    $httpProvider.defaults.withCredentials = true;

    // URL redirects.
    var trailingSlashRe = /^([^?#]+)\/([?#].*|$)/;
    $urlRouterProvider
      .rule(function ($injector, $location) {
        var match = $location.url().match(trailingSlashRe);
        if (match) {
          return match[1] + match[2]; // remove trailing slash
        }
      })
      .when('/:handle/libraries', '/:handle')
      .otherwise('/');  // last resort

    // Set up the states.
    $stateProvider
      .state('home', {
        url: '/?openImportModal&error',
        controller: 'HomeCtrl',
        templateUrl: 'home/home.tpl.html',
        'abstract': true
      })
      .state('home.feed', {
        url: '?filter&handle',
        controller: 'FeedCtrl',
        templateUrl: 'feed/feed.tpl.html',
        reloadOnSearch: false
      })
      .state('getStarted', {
          url: '/getstarted',
          controller: 'FtueCtrl',
          templateUrl: 'ftue/ftue.tpl.html',
          'abstract': true
      })
      .state('getStarted.followLibraries', {
          url: '',
          controller: 'FtueFollowLibrariesCtrl',
          templateUrl: 'ftue/ftueFollowLibraries.tpl.html'
      })
      .state('invite', {
        url: '/invite',
        templateUrl: 'invite/invite.tpl.html'
      })
      .state('manageTags', {
        url: '/tags/manage',
        templateUrl: 'tagManage/tagManage.tpl.html',
        controller: 'ManageTagCtrl'
      })
      .state('settings', {
        url: '/settings',
        templateUrl: 'profile/profile.tpl.html',
        controller: 'ProfileCtrl'
      })
      .state('search', {
        url: '/find?q&f',
        templateUrl: 'search/search.tpl.html',
        controller: 'SearchCtrl',
        resolve: {
          library: angular.noop
        },
        reloadOnSearch: false  // controller handles search query changes itself
      })
      .state('intersectionPage', {
        url: '/int?uri&user&library&email',
        templateUrl: 'keeps/intersectionPage.tpl.html',
        controller: 'IntersectionPageCtrl'
      })
      .state('userOrOrg', {
        // Any params you want passed to redirected states need to be listed here
        url: '/:handle?authToken&openCreateLibrary&signUpWithSlack&slackTeamId&userId&keepId&libraryId&error',
        controller: [
          '$state', '$stateParams', 'orgProfileService',
          function ($state, $stateParams, orgProfileService) {
            orgProfileService
              .userOrOrg($stateParams.handle, $stateParams.authToken)
              .then(function (userOrOrgData) {
                var type = userOrOrgData.type;
                if (type === 'user') {
                  $state.go('userProfile.libraries.own', $stateParams, { location: false });
                } else if (type === 'org') {
                  $state.go('orgProfile.libraries', $stateParams, { location: false });
                }
              });
          }
        ]
      })
      .state('orgProfile', {
        url: '/:handle?authToken&openCreateLibrary&signUpWithSlack&slackTeamId&userId&keepId&libraryId&error',
        params: {
          organization: null
        },
        templateUrl: 'orgProfile/orgProfile.tpl.html',
        controller: 'OrgProfileCtrl',
        resolve: {
          profile: [
            '$state', '$stateParams','orgProfileService',
            function ($state, $stateParams, orgProfileService) {
              // return the Promise to make its value available to the controller
              return orgProfileService
                .userOrOrg($stateParams.handle, $stateParams.authToken)
                .then(function (userOrOrgData) {
                  var type = userOrOrgData.type;

                  if (type === 'org') { // sanity check
                    if (userOrOrgData.result && userOrOrgData.result.error) {
                      throw new Error(userOrOrgData.result.error);
                    } else {
                      // success
                      return userOrOrgData.result;
                    }
                  } else {
                    throw new Error('orgProfile state was given invalid type ' + type);
                  }
                });
            }
          ]
        },
        'abstract': true
      })
      .state('orgProfile.slack', {
        url: '',
        controller: 'OrgProfileSlackCtrl',
        templateUrl: 'orgProfile/orgProfileSlack.tpl.html',
        activetab: null,
        'abstract': true
      })
      .state('orgProfile.slack.basic', {
        url: '',
        activetab: null
      })
      .state('orgProfile.slack.welcome', {
        url: '',
        params: {
          userId: null
        },
        controller: 'OrgProfileSlackWelcomeCtrl',
        templateUrl: 'orgProfile/orgProfileSlackWelcome.tpl.html',
        activetab: null
      })
      .state('orgProfile.slack.keep', {
        url: '',
        params: {
          keepId: null,
          authToken: ''
        },
        controller: 'OrgProfileSlackKeepCtrl',
        templateUrl: 'orgProfile/orgProfileSlackKeep.tpl.html',
        activetab: null
      })
      .state('orgProfile.slack.library', {
        url: '',
        controller: 'OrgProfileSlackLibraryCtrl',
        templateUrl: 'orgProfile/orgProfileSlackLibrary.tpl.html',
        activetab: null
      })
      .state('orgProfile.members', {
        url: '/members?openInviteModal&addMany',
        controller: 'OrgProfileMemberManageCtrl',
        templateUrl: 'orgProfile/orgProfileMemberManage.tpl.html',
        activetab: 'members'
      })
      .state('orgProfile.libraries', {
        url: '?openInviteModal&addMany',
        params: {
          showSlackDialog: false, // shows the "integrate general with Slack" dialog
          forceSlackDialog: false // show the "integrate all your Slack channels with your Kifi team" dialog
        },
        controller: 'OrgProfileLibrariesCtrl',
        templateUrl: 'orgProfile/orgProfileLibraries.tpl.html',
        activetab: 'libraries'
      })
      .state('orgProfile.settings', {
        url: '/settings?openDomains',
        controller: 'OrgProfileSettingsCtrl',
        templateUrl: 'orgProfile/orgProfileSettings.tpl.html',
        activetab: 'settings',
        resolve: {
          billingState: [
            'billingService', 'profile',
            function (billingService, profile) {
              if (profile.viewer.permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) !== -1) {
                return billingService.getBillingState(profile.organization.id);
              } else {
                return {};
              }
            }
          ]
        },
        'abstract': true
      })
      .state('orgProfile.settings.team', {
        url: '',
        controller: 'TeamSettingsCtrl',
        templateUrl: 'teamSettings/teamSettings.tpl.html',
        activetab: 'settings',
        activenav: 'team-settings'
      })
      .state('orgProfile.settings.export', {
        url: '/export',
        controller: 'ExportKeepsCtrl',
        templateUrl: 'teamSettings/exportKeeps.tpl.html',
        activetab: 'settings',
        activenav: 'export-keeps'
      })
      .state('orgProfile.settings.contacts', {
        url: '/contacts',
        controller: 'BillingContactsCtrl',
        templateUrl: 'teamSettings/billingContacts.tpl.html',
        activetab: 'settings',
        activenav: 'billing-contacts'
      })
      .state('orgProfile.settings.plan', {
        url: '/plan?upgrade',
        controller: 'PaymentPlanCtrl',
        templateUrl: 'teamSettings/paymentPlan.tpl.html',
        activetab: 'settings',
        activenav: 'payment-plan',
        resolve: {
          stripe: StripeCheckoutProvider.load,
          paymentPlans: [
            'billingService', 'profile',
            function (billingService, profile) {
              return billingService
              .getBillingPlans(profile.organization.id);
            }
          ]
        }
      })
      .state('orgProfile.settings.activity', {
        url: '/activity',
        controller: 'ActivityLogCtrl',
        templateUrl: 'teamSettings/activityLog.tpl.html',
        activetab: 'settings',
        activenav: 'activity-log'
      })
      .state('orgProfile.settings.credits', {
        url: '/credits',
        controller: 'EarnCreditsCtrl',
        templateUrl: 'teamSettings/earnCredits.tpl.html',
        activetab: 'settings',
        activenav: 'earn-credits'
      })
      .state('orgProfile.settings.integrations', {
        url: '/integrations',
        controller: 'IntegrationsCtrl',
        templateUrl: 'teamSettings/integrations.tpl.html',
        activetab: 'settings',
        activenav: 'integrations'
      })
      .state('orgProfile.settings.integrationsSlackConfirm', {
        url: '/integrations/slack-confirm?:slackTeamId',
        controller: 'SlackConfirmCtrl',
        templateUrl: 'teamSettings/slackConfirm.tpl.html',
        activetab: 'settings',
        activenav: 'integrations'
      })
      .state('teams', {
        url: '/teams',
        'abstract': true,
        template: '<ui-view/>'
      })
      .state('teams.new', {
        url: '/new',
        controller: 'OrgProfileCreateCtrl',
        templateUrl: 'orgProfile/orgProfileCreate.tpl.html',
        params: {
          showSlackPromo: false
        }
      })
      .state('userProfile', {
        url: '/:handle?authToken&openCreateLibrary',
        templateUrl: 'userProfile/userProfile.tpl.html',
        controller: 'UserProfileCtrl',
        resolve: {
          profile: [
            '$state', '$stateParams', 'orgProfileService',
            function ($state, $stateParams, orgProfileService) {
              // return the Promise to make its value available to the controller
              return orgProfileService
                .userOrOrg($stateParams.handle, $stateParams.authToken)
                .then(function (userOrOrgData) {
                  var type = userOrOrgData.type;

                  if (type === 'user') { // sanity check
                    return userOrOrgData.result;
                  } else {
                    throw new Error('userProfile state was given invalid type ' + type);
                  }
                });
            }
          ]
        },
        'abstract': true
      })
      .state('userProfile.libraries', {
        url: '',
        templateUrl: 'userProfile/userProfileLibraries.tpl.html',
        controller: 'UserProfileLibrariesCtrl',
        'abstract': true
      })
      .state('userProfile.libraries.own', {
        url: '',
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html'
      })
      .state('userProfile.libraries.following', {
        url: '/libraries/following',
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html'
      })
      .state('userProfile.libraries.invited', {
        url: '/libraries/invited',
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html'
      })
      .state('userProfile.connections', {
        url: '/connections',
        templateUrl: 'userProfile/userProfileConnections.tpl.html',
        controller: 'UserProfileConnectionsCtrl'
      })
      .state('userProfile.followers', {
        url: '/followers',
        templateUrl: 'userProfile/userProfileFollowers.tpl.html',
        controller: 'UserProfileFollowersCtrl'
      })
      .state('keepPage', {
        url: '/k/:title/:pubId?authToken',
        templateUrl: 'keep/keepPage.tpl.html',
        controller: 'KeepPageCtrl'
      })
      .state('slackIntegrationTeamChooser', {
        url : '/integrations/slack/teams?slackTeamId&slackState',
        templateUrl: 'integrations/slack/teamChooser.tpl.html',
        controller: 'SlackIntegrationTeamChooserCtrl'
      })
      // ↓↓↓↓↓ Important: This needs to be last! ↓↓↓↓↓
      .state('library', {
        url: '/:handle/:librarySlug?authToken&signUpWithSlack',
        templateUrl: 'libraries/library.tpl.html',
        controller: 'LibraryCtrl',
        params: {
          showSlackDialog: false
        },
        resolve: {
          libraryService: 'libraryService',
          library: ['libraryService', '$stateParams', function (libraryService, $stateParams) {
            return libraryService.getLibraryByHandleAndSlug($stateParams.handle, $stateParams.librarySlug, $stateParams.authToken);
          }],
          libraryImageLoaded: ['$q', '$timeout', 'env', 'library', function ($q, $timeout, env, library) {
            if (library.image) {
              var deferred = $q.defer();
              var promise = loadImage($q, env.picBase + '/' + library.image.path).then(function () {
                deferred.resolve(true);
              }, function () {
                deferred.resolve(false);
              });
              $timeout(function () {
                deferred.resolve({promise: promise});
              }, 12);  // low number b/c it delays many library page loads
              return deferred.promise;
            }
            return false;
          }]
        },
        'abstract': true
      })
      .state('library.keeps', {
        url: '',
        templateUrl: 'libraries/libraryKeeps.tpl.html',
        errorView: 'kf-contact-admin'
      })
      .state('library.search', {
        url: '/find?q&f',
        templateUrl: 'search/matchingKeeps.tpl.html',
        controller: 'SearchCtrl',
        reloadOnSearch: false  // controller handles search query changes itself
      });
      // ↑↑↑↑↑ Important: This needs to be last! ↑↑↑↑↑

    function loadImage($q, url) {
      var deferred = $q.defer();
      var img = new Image();
      img.onload = function () {
        deferred.resolve(img);
      };
      img.onerror = function (e) {
        deferred.reject(e);
      };
      img.src = url;
      return deferred.promise;
    }
  }
]);
