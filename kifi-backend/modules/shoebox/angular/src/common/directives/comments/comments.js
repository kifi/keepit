'use strict';

angular.module('kifi')

  .directive('kfKeepComments', [ 'profileService',
    function (profileService) {
      return {
        restrict: 'A',
        scope: {
          keep: '='
        },
        templateUrl: 'common/directives/comments/comments.tpl.html',
        link: function (scope, element) {
          var inputDiv = element[0].querySelector('.kf-keep-comments-input');
          scope.comments = [];
          scope.me = profileService.me;
          scope.keydown = function (event) {
            // if you are not holding the shift key while
            // hitting enter, then we take this as the desired comment.
            // else we continue normally
            if (!event.shiftKey && event.which === 13) {

              //var txt = inputDiv.innerText;
              scope.comments.push({
                user: profileService.me,
                text: inputDiv.innerText
              });

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

        }
      };
    }
  ]);
