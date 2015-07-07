'use strict';

angular.module('kifi')

.directive('kfUserChecklist', [
  '$rootScope', '$window', '$location', '$analytics', 'installService', 'extensionLiaison', 'modalService', 'profileService', 'net', 'routeService',
  function ($rootScope, $window, $location, $analytics, installService, extensionLiaison, modalService, profileService, net, routeService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'recos/userChecklist.tpl.html',
      link: function (scope, element) {
        function completeOrIncomplete(complete) {
          return complete ? 'completed' : 'incomplete';
        }

        var allChecklistItems = [
          {
            name: 'invite_friends',
            title: 'Invite 3 colleagues or friends to Kifi',
            subtitle: 'Let them tap into your knowledge on Kifi',
            action: function () {
              $window.open(routeService.socialInvite, '_blank');
              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clickedInvite3Checklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          },
          {
            name: 'keep_pages',
            title: 'Keep 5 pages',
            subtitle: 'Keep from the site, browser add-on, or mobile',
            action: function () {
              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clickedKeep5Checklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          },
          {
            name: 'follow_libs',
            title: 'Follow 5 libraries',
            subtitle: 'Browse libraries in your recommendations',
            action: function () {
              $window.open(routeService.followingLibraries, '_blank');
              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clickedFollow5Checklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          },
          {
            name: 'install_ext',
            title: 'Get the Kifi browser add-on',
            subtitle: 'The most loved features are in the add-on',
            action: function () {
              installService.triggerInstall(function () {
                modalService.open({
                  template: 'common/modal/installExtensionErrorModal.tpl.html'
                });
              });

              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clickedGetAddOnChecklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          },
          {
            name: 'take_tour',
            title: 'Take the Kifi browser addon tour',
            subtitle: 'A quick walk-thru of our most popular features',
            action: function () {
              extensionLiaison.triggerGuide();
              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clickedTakeTourChecklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          },
          {
            name: 'import_bookmarks',
            title: 'Import bookmarks from your browser',
            subtitle: 'The easiest way to add your favorites to Kifi',
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
              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clickedBrowserImportChecklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          },
          {
            name: 'import_third_party',
            title: 'Bring in your links from 3rd parties',
            subtitle: 'Pocket, Delicious, Pinboard, Instapaper & more',
            action: function () {
              $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clicked3rdPartyImportChecklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          },
          {
            name: 'install_mobile',
            title: 'Get the iOS or Android mobile app',
            subtitle: 'Text a link to your phone for Kifi on the go',
            action: function () {
              modalService.open({
                template: 'common/modal/sendMobileAppSMS.tpl.html',
                scope: scope
              });

              $analytics.eventTrack('user_viewed_page', {
                'type': 'getMobileChecklist'
              });

              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clickedGetMobileChecklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          },
          {
            name: 'twitter_sync',
            title: 'Sign up for the Twitter Beta',
            subtitle: 'Twitter meets deep search',
            action: function () {
              $window.open(routeService.connectTwitter,'_blank');
              $analytics.eventTrack('user_clicked_page', {
                'type': 'yourKeeps',
                'action': 'clickedTwitterChecklist',
                'subaction': completeOrIncomplete(this.complete)
              });
            }
          }
        ];

        function allItemsComplete(items) {
          for (var i = 0; i < items.length; i++) {
            if (!items[i].complete) {
              return false;
            }
          }
          return true;
        }

        scope.sms = { phoneNumber: '' };

        scope.triggerSendSMS = function () {
          net.sendMobileAppSMS({ phoneNumber: scope.sms.phoneNumber })
            ['catch'](function () {
              modalService.open({
                template: 'common/modal/sendMobileAppSMSError.tpl.html'
              });
            });

          // Clear the model so the modal doesn't show the phone number
          // if the user opens it again later
          scope.sms.phoneNumber = '';
        };

        scope.$watchCollection(function () {
          return profileService.prefs && profileService.prefs.checklist;
        }, function (enabledChecklistItems) {
          if (!enabledChecklistItems) {
            return;
          }

          // Don't show the checklist if all items are compelete
          if (allItemsComplete(enabledChecklistItems)) {
            element.remove();
            return;
          }

          scope.checklist = enabledChecklistItems.map(function (enabledItem) {
            var checklistItem = allChecklistItems.filter(function (item) {
              return enabledItem.name === item.name;
            }).pop();
            if (!checklistItem) {
              return null;
            }

            // Copy the complete value to the list of checklist items to display
            checklistItem.complete = enabledItem.complete;

            return checklistItem;
          }).filter(Boolean);
        });
      }
    };
  }
]);
