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
              modalService.open({
                template: 'recos/userChecklistKeepModal.tpl.html',
                scope: scope
              });

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
            subtitle: 'Browse libraries from your connections',
            action: function () {
              modalService.open({
                template: 'recos/userChecklistFollowModal.tpl.html',
                scope: scope
              });
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
            subtitle: 'The easiest way to add favorites to Kifi',
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

        function hasChildWithSelector(parent, selector) {
          return !!parent.querySelector(selector);
        }

        function scrollToUnderHeader($element, $header) {
          var PADDING = 10;
          var documentTop = $window.document.documentElement.getBoundingClientRect().top;
          var elementTop = $element.get(0).getBoundingClientRect().top;
          var headerHeight = $header.get(0).getBoundingClientRect().height;
          var absoluteElementTop = elementTop - documentTop;
          var scrollToY = absoluteElementTop - headerHeight - PADDING;
          $window.scrollTo(0, scrollToY);
        }

        // Highlight a button and move its card to the top of the card list
        function triggerHighlightElement(toHighlightSelector, containerSelector) {
          var HIGHLIGHT_TIMEOUT = 15000;
          var containers = angular.element(containerSelector);
          var cards = containers.filter(function () {
            return hasChildWithSelector(this, toHighlightSelector);
          });
          // Try to choose the second element so the user definitely sees
          // that something changed. If only one is available, use that one.
          var card = cards.length > 1 ? cards.eq(1) : cards.eq(0);

          if (card.length === 0) {
            return;
          }

          var button = card.find(toHighlightSelector);
          button.addClass('kf-uc-highlight-levitate');

          // Move it to the top
          card.parent().prepend(card);
          // Scroll to show the whole card
          scrollToUnderHeader(card, angular.element('.kf-lih'));

          function removeHighlight() {
            button.removeClass('kf-uc-highlight-levitate');
          }

          button.one('click', removeHighlight);
          setTimeout(function () {
            button.off('click', removeHighlight);
            removeHighlight();
          }, HIGHLIGHT_TIMEOUT);
        }

        scope.keepCardExists = function () {
          return angular.element('.kf-keep-card').length > 0;
        };

        scope.triggerHighlightKeepButton = triggerHighlightElement.bind(null, '.kf-keep-keep-btn','.kf-keep');

        scope.followCardExists = function () {
          return angular.element('.kf-rcl-card').length > 0;
        };

        scope.triggerHighlightFollowButton = triggerHighlightElement.bind(null, '.kf-rcl-follow-btn', '.kf-keep');

        function allItemsComplete(items) {
          for (var i = 0; i < items.length; i++) {
            if (!items[i].complete) {
              return false;
            }
          }
          return true;
        }

        function getEnabledChecklistItems() {
          return profileService.prefs && profileService.prefs.checklist;
        }

        function createChecklistData(enabledChecklistItems) {
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
        }

        // Wait for the profileService to fetch the checklist
        scope.$watchCollection(getEnabledChecklistItems, createChecklistData);
      }
    };
  }
]);
