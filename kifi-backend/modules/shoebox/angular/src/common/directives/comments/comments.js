'use strict';

angular.module('kifi')

  .directive('kfKeepComments', [ 'profileService', 'keepService',
    function (profileService, keepService) {
      return {
        restrict: 'A',
        $scope: {
          keep: '='
        },
        templateUrl: 'common/directives/comments/comments.tpl.html',
        link: function ($scope, element) {
          var inputDiv = element[0].querySelector('.kf-keep-comments-input');

          $scope.visibleComments = [];
          $scope.pendingComments = [];
          $scope.myAddedComments = [];
          $scope.me = profileService.me;
          $scope.threadId = null;

          if ($scope.keep.discussion) {
            $scope.threadId = $scope.keep.discussion.threadId;
            $scope.pendingComments = ($scope.keep.discussion && $scope.keep.discussion.messages) || [];
            $scope.pendingComments.sort(function(a, b) {
              return b.sentAt - a.sentAt;
            });
            $scope.visibleComments = $scope.pendingComments.slice(0, Math.min(3, $scope.pendingComments.length));
            $scope.showViewPreviousComments = ($scope.visibleComments.length < $scope.keep.discussion.numMessages);
          }


          // listeners

          $scope.keydown = function (event) {
            // if you are not holding the shift key while
            // hitting enter, then we take this as the desired comment.
            // else we continue normally
            if (!event.shiftKey && event.which === 13) {

              //var txt = inputDiv.innerText;
              var msg = {
                sentAt: new Date().getTime(),
                sentBy: profileService.me,
                text: inputDiv.innerText
              };
              $scope.visibleComments.splice(0, 0, msg);
              $scope.myAddedComments.push(msg);

              keepService.addMessageToKeepDiscussion($scope.keep.pubId, {text: msg.text});

              event.stopPropagation();
              event.preventDefault();
              // resetting the caret on a contenteditable div is not so trivial, see link
              // http://stackoverflow.com/a/24117242/3381000
              inputDiv.innerText = '';
              var textNode = inputDiv;
              var caret = 0; // insert caret after the 10th character say
              var range = document.createRange();
              range.setStart(textNode, caret);
              range.setEnd(textNode, caret);
              var sel = window.getSelection();
              sel.removeAllRanges();
              sel.addRange(range);
            }
          };

          // functions
          $scope.onViewPreviousComments = function() {
            var pos = ($scope.visibleComments.length - $scope.myAddedComments.length);
            if ($scope.pendingComments.length - pos > 0) {
              var pending = $scope.pendingComments.slice(pos);
              $scope.visibleComments.splice.apply($scope.visibleComments, [$scope.visibleComments.length, 0].concat(pending));
              $scope.showViewPreviousComments = $scope.pendingComments.length < $scope.keep.discussion.numMessages;
            } else {
              var last = ($scope.visibleComments.length && $scope.visibleComments[$scope.visibleComments.length - 1]) || null;
              last = last && last.id;
              // asking for 9, only displaying 8, we will then know if we are at the end or not
              keepService.getMessagesForKeepDiscussion($scope.keep.pubId, 9, last)
                .then(function(data) {
                  var messages = data.messages;
                  if (messages.length < 9) {
                    // done
                    $scope.showViewPreviousComments = false;
                  } else {
                    // remove the last one, we only actually wanted 8
                    messages = messages.splice(0, messages.length - 1);
                  }
                  $scope.visibleComments = $scope.visibleComments.concat(messages);
                  $scope.$apply();
                });
            }
          };

        }
      };
    }
  ]);
