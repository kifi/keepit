'use strict';

angular.module('kifi')

  .directive('kfKeepActivity', [
    '$filter', '$window', '$timeout', '$rootScope', '$stateParams', 'profileService', 'keepService', 'signupService', 'modalService',
    function ($filter, $window, $timeout, $rootScope, $stateParams, profileService, keepService, signupService, modalService) {
      var MARK_READ_TIMEOUT = 3000;

      function eventComparator(a, b) {
        if (a.kind === 'initial') {
          return -1;
        } else if (b.kind === 'initial') {
          return 1;
        }
        return a.timestamp - b.timestamp;
      }

      function resetCaret(contentEditable) {
        // resetting the caret on a contenteditable div is not so trivial, see link
        // http://stackoverflow.com/a/24117242/3381000
        var document = $window.document;
        var caret = 0; // insert caret after the 10th character say
        var range = document.createRange();
        var sel = $window.getSelection();
        contentEditable.textContent = '';
        range.setStart(contentEditable, caret);
        range.setEnd(contentEditable, caret);
        sel.removeAllRanges();
        sel.addRange(range);
      }

      function getCommentBox(element) {
        return element.find('.kf-keep-activity-input')[0];
      }

      return {
        restrict: 'A',
        scope: {
          keep: '=',
          maxInitialComments: '=',
          canEditKeep: '=',
          editKeepNote: '='
        },
        templateUrl: 'common/directives/keepActivity/keepActivity.tpl.html',
        link: function ($scope, element) {
          $scope.activity = [];
          $scope.visibleCount = 0;
          $scope.me = profileService.me;
          $scope.canAddComments = $scope.keep.permissions && $scope.keep.permissions.indexOf('add_message') !== -1;

          if (!$scope.keep.discussion) {
            $scope.keep.discussion = {
              messages: [],
              numMessages: 0
            };
          }

          $scope.activity = $scope.keep.activity.events.slice().sort(eventComparator); // don't mutate the original array, in case we need it later

          $scope.visibleCount = Math.min($scope.maxInitialComments || 4, $scope.activity.length);
          $scope.hasMoreToFetch = $scope.activity[0].kind !== 'initial';
          $scope.showViewPreviousComments = $scope.hasMoreToFetch || $scope.visibleCount < $scope.activity.length;

          // listeners

          $scope.keydown = function (e) {
            // if you are not holding the shift key while
            // hitting enter, then we take this as the desired comment.
            // else we continue normally
            var commentBox = getCommentBox(element);
            if (commentBox && !e.shiftKey && e.which === 13) {
              var tagRe = /(?:\[(#\D[\w\-​_ ]+[\w])\])|(#\D[\w\-_​]{2,})/g;
              var text = commentBox.textContent.replace(tagRe, '[$1$2]');
              var userPic = $filter('pic')(profileService.me);
              var userName = $filter('name')(profileService.me);
              var activityEvent = {
                timestamp: new Date().getTime(),
                kind: 'comment',
                author: {
                  id: profileService.me.id,
                  name: userName,
                  picture: userPic
                },
                header: [{
                  kind: 'author',
                  image: userPic,
                  text: profileService.me.firstName
                }, {
                  kind: 'text',
                  text: ' commented on this page'
                }],
                body: [{
                  kind: 'text',
                  text: text
                }]
              };
              $scope.activity.push(activityEvent);
              $scope.visibleCount++;
              var bufferHTML = commentBox.innerHTML;
              resetCaret(commentBox);

              keepService
              .addMessageToKeepDiscussion($scope.keep.pubId, text)
              .then(function (resp) {
                activityEvent.timestamp = resp.sentAt;
                activityEvent.id = resp.id;
              })['catch'](function () {
                $scope.error = 'Something went wrong. Try again?';
                $scope.visibleCount--;
                $scope.activity.pop();
                commentBox.innerHTML = bufferHTML;
              });

              e.stopPropagation();
              e.preventDefault();
            }

            $scope.error = '';
          };

          $scope.keyup = function () {
            var commentBox = getCommentBox(element);
            if (commentBox && commentBox.textContent === '') {
              commentBox.innerHTML = '';
            }
          };

          $scope.clickedInputBox = function(event) {
            if (profileService.shouldBeWindingDown()) {
              modalService.showWindingDownModal();
            } else {
              var commentBox = getCommentBox(element);
              if (!$rootScope.userLoggedIn && commentBox && event.which === 1) {
                signupService.register({ intent: 'joinKeep', keepId: $scope.keep.pubId, keepAuthToken: $stateParams.authToken || null });
              }
            }
          };

          // functions

          var readTimer;
          $scope.onInview = function (e, intoView) {
            if (intoView) {
              readTimer = $timeout(function () {
                readTimer = null;

                keepService
                .markDiscussionAsRead($scope.keep.pubId, $scope.activity.filter(function (e) {
                  return e.kind === 'comment';
                }))
                .then(function (resp) {
                  if (resp.unreadCounts[$scope.keep.pubId] > 0) {
                    fetchMessages();
                  }
                });
              }, MARK_READ_TIMEOUT);
            } else if (readTimer) {
              $timeout.cancel(readTimer);
            }
          };

          $scope.deleteComment = function(event, keepId, commentId) {
            var oldActivity = $scope.activity;
            keepService.deleteMessageFromKeepDiscussion(keepId, commentId)
            ['catch'](function () {
              $scope.activity = oldActivity;
              $scope.visibleCount++;
              $scope.error = 'Something went wrong. Please refresh and try again.';
            });
            $scope.activity = $scope.activity.filter(function (comment) {
              return comment.id !== commentId;
            });
            $scope.visibleCount--;
          };

          var MESSAGES_PER_PAGE = 4;
          var MESSAGES_PER_LOAD = MESSAGES_PER_PAGE * 2;

          function fetchMessages(lastTimestamp, batchSize) {
            return keepService
            .getActivityForKeepId($scope.keep.pubId, lastTimestamp, batchSize || MESSAGES_PER_LOAD + 1)
            .then(function (o) {
              var existingNewest = _.max($scope.activity, function (c) {
                return c.timestamp;
              });
              var batchOldest = _.min(o.activity.events, function (c) {
                return c.timestamp;
              });

              if (batchOldest.timestamp > existingNewest.timestamp) {
                fetchMessages(batchOldest.timestamp);
              }

              var updatedActivity = _.uniq($scope.activity.concat(o.activity.events), 'id').sort(eventComparator);
              if (o.activity.events.length === 0 || o.activity.events[o.activity.events.length - 1].kind === 'initial') {
                $scope.hasMoreToFetch = false; // We're at the beginning, can't possible paginate more.
              } else if (updatedActivity.length > $scope.visibleCount) {
                $scope.showViewPreviousComments = true;
              }

              $scope.activity = updatedActivity;
              return $scope.activity;
            });
          }

          $scope.onViewPreviousComments = function () {
            $scope.visibleCount = Math.min($scope.visibleCount + MESSAGES_PER_PAGE, $scope.activity.length); // don't go over the comments length
            $scope.hasMoreToFetch = $scope.activity[0].kind !== 'initial';
            $scope.showViewPreviousComments = $scope.hasMoreToFetch || $scope.visibleCount < $scope.activity.length;

            if ($scope.hasMoreToFetch) {
              var last = $scope.activity.slice(0,1).pop();
              if (last) {
                fetchMessages(last.timestamp);
              }
            }
          };
        }
      };
    }
  ]);
