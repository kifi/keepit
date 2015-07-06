'use strict';

angular.module('kifi')

.directive('kfUserChecklist', [
  '$rootScope', '$window', '$location', '$analytics', 'installService', 'extensionLiaison', 'modalService', 'profileService',
  function ($rootScope, $window, $location, $analytics, installService, extensionLiaison, modalService, profileService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'recos/userChecklist.tpl.html',
      link: function (scope, element) {
        var checklistItems = [
          {
            name: 'invite_friends',
            title: 'Invite three friends to Kifi',
            subtitle: 'Blah subtitle',
            action: function () {
              $location.path('/invite');
            }
          },
          {
            name: 'keep_pages',
            title: 'Keep five pages',
            subtitle: 'Blah subtitle'
          },
          {
            name: 'follow_libs',
            title: 'Follow five libraries',
            subtitle: 'Blah subtitle',
            action: function () {
              $window.location.href = 'http://support.kifi.com/hc/en-us/articles/202657599-Following-Libraries';
            }
          },
          {
            name: 'install_ext',
            title: 'Get the Kifi browser add-on',
            subtitle: 'The most loved features are in the add-on',
            action: function () {
              $location.path('/install');
            }
          },
          {
            name: 'take_tour',
            title: 'Take the tour',
            subtitle: 'blah',
            action: function (linkClicked) {
              extensionLiaison.triggerGuide();
              if (linkClicked) {
                $analytics.eventTrack('user_clicked_page', {
                  'action': 'startGuide',
                  'path': $location.path()
                });
              }
            }
          },
          {
            name: 'import_bookmarks',
            title: 'Import your bookmarks',
            subtitle: 'blah blah',
            action: function () {
              var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

              if (!kifiVersion) {
                modalService.open({
                  template: 'common/modal/installExtensionModal.tpl.html',
                  scope: scope
                });
                return;
              }

              $rootScope.$emit('showGlobalModal', 'importBookmarks');
              $analytics.eventTrack('user_viewed_page', {
                'type': 'browserImport'
              });
            }
          },
          {
            name: 'import_thirdparty',
            title: 'Import third party libraries',
            subtitle: 'blah blah',
            action: function () {
                $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
                $analytics.eventTrack('user_viewed_page', {
                  'type': '3rdPartyImport'
                });
            }
          },
          {
            name: 'install_mobile',
            title: 'Get the mobile app',
            subtitle: 'blah blah'
          },
          {
            name: 'twitter_sync',
            title: 'Connect your Twitter account',
            subtitle: 'blah blah'
          }
        ];

        profileService.fetchPrefs().then(function (prefs) {
          var profileChecklist = prefs.checklist;

          // Don't show the checklist if there isn't
          // any checklist data in the profile
          if (!profileChecklist) {
            element.remove();
            return;
          }

          if (!(profileChecklist instanceof Array)) {
            profileChecklist = Object.keys(profileChecklist).map(function (p) {
              return { name: p, complete: profileChecklist[p] };
            });
          }

          var allComplete = true;
          for (var i = 0; i < profileChecklist.length; i++) {
            if (!profileChecklist[i].complete) {
              allComplete = false;
              break;
            }
          }

          // Don't show the checklist if all items are compelte
          if (allComplete) {
            element.remove();
            return;
          }

          scope.checklist = checklistItems.map(function (checklistItem) {
            var profileItem = profileChecklist.filter(function (p) {
              return p.name === checklistItem.name;
            }).pop();
            if (!profileItem) {
              // If the profile checklist doesn't contain an item,
              // remove it from the list of checklist items to display
              return null;
            }

            // Copy the complete value to the list of checklist items to display
            checklistItem.complete = profileItem.complete;

            return checklistItem;
          }).filter(Boolean);
        });
      }
    };
  }
]);
