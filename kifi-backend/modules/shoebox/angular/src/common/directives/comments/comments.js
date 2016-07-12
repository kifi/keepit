'use strict';

angular.module('kifi')

  .directive('kfKeepComments', [
    '$window', '$timeout', '$rootScope', '$stateParams', 'profileService', 'keepService',
    function ($window, $timeout, $rootScope, $stateParams, profileService, keepService) {
      var MARK_READ_TIMEOUT = 3000;

      function bySentAt(a, b) {
        return a.sentAt - b.sentAt;
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
        return element.find('.kf-keep-comments-input')[0];
      }

      return {
        restrict: 'A',
        scope: {
          keep: '=',
          maxInitialComments: '=',
          canEditKeep: '=',
          editKeepNote: '='
        },
        templateUrl: 'common/directives/comments/comments.tpl.html',
        link: function ($scope, element) {
          $scope.comments = [];
          $scope.visibleCount = 0;
          $scope.me = profileService.me;
          $scope.canAddComments = $scope.keep.permissions && $scope.keep.permissions.indexOf('add_message') !== -1;

          if (!$scope.keep.discussion) {
            $scope.keep.discussion = {
              messages: [],
              numMessages: 0
            };
          }

          $scope.comments = $scope.keep.discussion.messages.slice().sort(bySentAt); // don't mutate the original array, in case we need it later

          $scope.visibleCount = Math.min($scope.maxInitialComments || 3, $scope.comments.length);
          $scope.showViewPreviousComments = $scope.hasMoreToFetch = $scope.visibleCount < $scope.keep.discussion.numMessages;

          // listeners

          $scope.keydown = function (e) {
            // if you are not holding the shift key while
            // hitting enter, then we take this as the desired comment.
            // else we continue normally
            var commentBox = getCommentBox(element);
            if (commentBox && !e.shiftKey && e.which === 13) {
              var tagRe = /(?:\[(#\D[\w\-​_ ]+[\w])\])|(#\D[\w\-_​]{2,})/g;
              var text = commentBox.textContent.replace(tagRe, '[$1$2]');
              var msg = {
                sentAt: new Date().getTime(),
                sentBy: profileService.me,
                text: text
              };
              $scope.comments.push(msg);
              $scope.visibleCount++;
              var bufferHTML = commentBox.innerHTML;
              resetCaret(commentBox);

              keepService
              .addMessageToKeepDiscussion($scope.keep.pubId, msg.text)
              .then(function (resp) {
                msg.sentAt = resp.sentAt;
                msg.id = resp.id;
              })['catch'](function () {
                $scope.error = 'Something went wrong. Try again?';
                $scope.visibleCount--;
                $scope.comments.pop();
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

          $scope.clickedInputBox = function() {};

          // functions

          var readTimer;
          $scope.onInview = function (e, intoView) {
            if (intoView) {
              readTimer = $timeout(function () {
                readTimer = null;

                keepService
                .markDiscussionAsRead($scope.keep.pubId, $scope.comments)
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
            var oldComments = $scope.comments;
            keepService.deleteMessageFromKeepDiscussion(keepId, commentId)
            ['catch'](function () {
              $scope.comments = oldComments;
              $scope.visibleCount++;
              $scope.error = 'Something went wrong. Please refresh and try again.';
            });
            $scope.comments = $scope.comments.filter(function (comment) {
              return comment.id !== commentId;
            });
            $scope.visibleCount--;
          };

          var MESSAGES_PER_PAGE = 4;
          var MESSAGES_PER_LOAD = MESSAGES_PER_PAGE * 2;

          function fetchMessages(lastMsgId, batchSize) {
            return keepService
            .getMessagesForKeepDiscussion($scope.keep.pubId, batchSize || MESSAGES_PER_LOAD + 1, lastMsgId)
            .then(function (o) {
              var existingNewest = _.max($scope.comments, function (c) {
                return c.sentAt;
              });
              var batchOldest = _.min(o.messages, function (c) {
                return c.sentAt;
              });

              if (batchOldest.sentAt > existingNewest.sentAt) {
                fetchMessages(batchOldest.id);
              }

              var updatedComments = _.uniq($scope.comments.concat(o.messages), 'id').sort(bySentAt);
              if (lastMsgId && o.messages.length === 0) {
                $scope.hasMoreToFetch = false; // We're at the beginning, can't possible paginate more.
              } else if (updatedComments.length > $scope.visibleCount) {
                $scope.showViewPreviousComments = true;
              }

              $scope.comments = updatedComments;
              return $scope.comments;
            });
          }

          $scope.onViewPreviousComments = function () {
            $scope.visibleCount = Math.min($scope.visibleCount + MESSAGES_PER_PAGE, $scope.comments.length); // don't go over the comments length
            $scope.showViewPreviousComments = $scope.hasMoreToFetch = $scope.visibleCount < $scope.keep.discussion.numMessages;

            if ($scope.visibleCount + MESSAGES_PER_PAGE >= $scope.comments.length) { // if next time we paginate we won't have enough, preload next batch
              var last = $scope.comments.slice(0,1).pop();
              if (last) {
                fetchMessages(last.id);
              }
            }
          };
        }
      };
    }
  ]);
