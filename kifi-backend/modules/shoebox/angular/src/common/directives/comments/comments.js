'use strict';

angular.module('kifi')

  .directive('kfKeepComments', [
    '$window', '$timeout', 'profileService', 'keepService',
    function ($window, $timeout, profileService, keepService) {
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

      return {
        restrict: 'A',
        $scope: {
          keep: '='
        },
        templateUrl: 'common/directives/comments/comments.tpl.html',
        link: function ($scope, element) {
          var inputDiv = element[0].querySelector('.kf-keep-comments-input');

          $scope.comments = [];
          $scope.visibleCount = 0;

          $scope.me = profileService.me;
          $scope.threadId = null;

          if ($scope.keep.discussion) {
            $scope.threadId = $scope.keep.discussion.threadId;
            $scope.comments = $scope.keep.discussion.messages.slice().sort(bySentAt); // don't mutate the original array, in case we need it later
            $scope.visibleCount = Math.min(3, $scope.comments.length);
            $scope.showViewPreviousComments = ($scope.visibleCount < $scope.keep.discussion.numMessages);
          }

          // listeners

          $scope.keydown = function (e) {
            // if you are not holding the shift key while
            // hitting enter, then we take this as the desired comment.
            // else we continue normally
            if (!e.shiftKey && e.which === 13) {
              var msg = {
                sentAt: new Date().getTime(),
                sentBy: profileService.me,
                text: inputDiv.textContent
              };
              $scope.comments.push(msg);

              keepService
              .addMessageToKeepDiscussion($scope.keep.pubId, msg.text)
              .then(function () {
                $scope.visibleCount++;
                $scope.keep.discussion.numMessages++;
                resetCaret(inputDiv);
              })
              ['catch'](function () {
                $scope.error = 'Something went wrong. Try again?';
                $scope.comments.pop();
              });

              e.stopPropagation();
              e.preventDefault();
            }

            $scope.error = '';
          };

          $scope.keyup = function () {
            if (inputDiv.textContent === '') {
              inputDiv.innerHTML = '';
            }
          };

          // functions

          var readTimer;
          $scope.onInview = function (e, intoView) {
            if (intoView) {
              readTimer = $timeout(function () {
                readTimer = null;

                keepService
                .markDiscussionAsRead($scope.keep);
                // TODO(carlos): do something with the response
              }, MARK_READ_TIMEOUT);
            } else if (readTimer) {
              $timeout.cancel(readTimer);
            }
          };

          var MESSAGES_PER_PAGE = 4;
          var MESSAGES_PER_LOAD = MESSAGES_PER_PAGE * 2;

          $scope.onViewPreviousComments = function () {
            if ($scope.visibleCount < $scope.comments.length) {
              $scope.visibleCount = Math.min($scope.visibleCount + MESSAGES_PER_PAGE, $scope.keep.discussion.numMessages); // don't go over the comments length

              if ($scope.visibleCount < $scope.keep.discussion.numMessages) {
                var last = $scope.comments.slice(0,1).pop();
                if (last) {
                  keepService
                  .getMessagesForKeepDiscussion($scope.keep.pubId, MESSAGES_PER_LOAD + 1, last.id)
                  .then(function (messageData) {
                    var messages = messageData.messages;
                    $scope.comments = messages.slice(0, MESSAGES_PER_LOAD).sort(bySentAt).concat($scope.comments);
                  });
                }
              }
            }
          };
        }
      };
    }
  ]);
