'use strict';

angular.module('kifi')

.directive('kfSendKeepWidget', [
  '$document', '$templateCache', '$rootElement', '$location', '$timeout', '$window', '$compile', 'KEY', 'keepService', 'modalService',
  function($document, $templateCache, $rootElement, $location, $timeout, $window, $compile, KEY, keepService, modalService) {

    var NUM_SUGGESTIONS = 3;

    return {
      restrict: 'A',
      scope : {
        keep: '='
      },
      link: function(scope, element) {
        var currPage = 0;
        var widget = null;
        var filteredSuggestions = []; // ids of entities to filter from suggestions (keep.members + scope.selected)

        function init() {
          element.on('click', function () {
            initWidget();
          });

          scope.$on('$destroy', scope.removeWidget);
        }

        function initWidget() {
          widget = angular.element($templateCache.get('keep/sendKeepWidget.tpl.html'));
          $rootElement.find('html').append(widget);
          $compile(widget)(scope);
          widget.hide();

          //
          // Position widget.
          //

          var desiredMarginTop = 60;
          var desiredShiftVertical = 30;

          var elementOffsetTop = element.offset().top;
          var distanceFromBottom = $window.innerHeight - elementOffsetTop;

          var elementOffsetLeft = element.offset().left;
          var distanceFromRight = $window.innerWidth - elementOffsetLeft;

          refreshSuggestions(null, NUM_SUGGESTIONS).then(function () {
            // wait for the suggestions to populate the widget before looking at its height
            $timeout(function () {

              // Place the widget such that 1) it's on the screen, and 2) does not obscure the keep members UI on the keep card.
              // If the widget can be placed above the keep members chips, set the bottom to be above it.
              // Else if the widget can be placed below the keep members chips, set the bottom s.t. the top is below it.
              // Else, do our best by placing the widget in middle of the keep
              var widgetHeight = widget.height();
              var bottom = null;

              if (elementOffsetTop - widgetHeight - desiredShiftVertical >= desiredMarginTop) {
                bottom = distanceFromBottom + desiredShiftVertical;
              } else if (distanceFromBottom - widgetHeight - desiredShiftVertical >= 0) {
                bottom = distanceFromBottom - widgetHeight - desiredShiftVertical;
              } else {
                bottom = distanceFromBottom - (widgetHeight/2);
              }

              widget.css({ bottom: bottom + 'px', right: distanceFromRight  + 'px' });
              widget.show();

              $timeout(function() {
                resetInput();
              }, 0);
            }, 0);
          });

          scope.$error = false;
          scope.success = false;
          scope.sending = false;
          scope.suggestions = [];
          scope.selections = [];

          filteredSuggestions = computeKeepMembers(scope.keep); // ids of keep.members + scope.selections to omit

          scope.typeahead = '';
          currPage = 0;

          $document.on('mousedown', onClick);
        }

        function computeKeepMembers(keep) {
          var ids = [];
          keep.members.emails.forEach(function(member) {
            ids.push(member.email.email);
          });
          keep.members.users.forEach(function(member) {
            ids.push(member.user.id);
          });
          keep.members.libraries.forEach(function(member) {
            ids.push(member.library.id);
          });
          return ids;
        }

        function onClick(event) {
          if (!angular.element(event.target).closest('.kf-send-keep-widget').length) {
            scope.$apply(scope.removeWidget);
          }
        }

        scope.removeWidget = function() {
          scope.$error = false;
          scope.typeahead = '';
          scope.selections = [];
          if (widget) {
            widget.remove();
          }
          $document.off('mousedown', onClick);
        };

        function refreshSuggestions(query, limit, offset) {
          return keepService.suggestRecipientsForKeep(query, limit, offset, null).then(function (resultData) {
            var nonMemberResults = resultData.results.filter(function(suggestion) {
              return filteredSuggestions.indexOf(suggestion.id || suggestion.email) === -1;
            });

            if (nonMemberResults.length > 1) {
              scope.suggestions = nonMemberResults;
            } else if (nonMemberResults.length === 1 || resultData.mayHaveMore) {
              refreshSuggestions(query, limit, (offset || 0) + limit);
            } else {
              refreshSuggestions('', NUM_SUGGESTIONS, 0);
            }
          });
        }

        function resetInput() {
          scope.typeahead = '';
          resizeInput();
          $timeout(function() {
            widget.find('.kf-skw-input').focus();
          }, 500);
        }

        function resizeInput() {
          var input = widget.find('.kf-skw-input');
          if (scope.selections.length === 0) {
            input.width(156); // fit the placeholder text
          } else {
            var shadowInput = widget.find('.kf-skw-input-shadow');
            shadowInput[0].innerHTML = input[0].value;
            input.width(shadowInput.width() + 10);
          }
        }

        function updateSelectionsOnKeep() {
          scope.selections.forEach(function(selection) {
            if (selection.kind === 'user') {
              scope.keep.members.users.push({ 'user': selection });
            } else if (selection.kind === 'library') {
              scope.keep.members.libraries.push({ 'library': selection });
            } else if (selection.kind === 'email') {
              scope.keep.members.emails.push({ 'email': selection });
            }
          });
        }

        scope.onClickSuggestion = function(suggestion) {
          scope.selections.push(suggestion);
          filteredSuggestions.push(suggestion.id || suggestion.email);
          suggestion.isSelected = true;

          if (widget.find('.kf-skw-suggestion').length === 1) {
            currPage++;
            refreshSuggestions(scope.typeahead, NUM_SUGGESTIONS, currPage * NUM_SUGGESTIONS);
          }

          resetInput();
        };

        scope.removeSelection = function(selection) {
          scope.selections = scope.selections.filter(function(s) {
            return s !== selection;
          });
          filteredSuggestions = filteredSuggestions.filter(function(sId) {
            return sId !== (selection.id || selection.email);
          });
          selection.isSelected = false;
          resetInput();
        };

        scope.processKeyEvent = function (event) {
          switch (event.keyCode) {
            case KEY.BSPACE:
              if (scope.typeahead === '') {
                event.preventDefault();
                var popped = scope.selections.pop();
                popped.isSelected = false;
                resetInput();
              }
              break;
            case KEY.ENTER:
              event.preventDefault();
              scope.onSend();
              break;
          }
        };

        scope.onSend = function() {
          scope.sending = true;
          var userIds = [];
          var libraryIds = [];
          var emails = [];

          scope.selections.forEach(function(selection) {
            if (selection.kind === 'user') {
              userIds.push(selection.id);
            } else if (selection.kind === 'library') {
              libraryIds.push(selection.id);
            } else if (selection.kind === 'email') {
              emails.push(selection.email);
            }
          });

          keepService.modifyKeepRecipients(scope.keep.pubId, userIds, libraryIds, emails)
            .then(function() {
              scope.sending = false;
              scope.success = true;
              updateSelectionsOnKeep();
              $timeout(scope.removeWidget, 1000);
            })
            ['catch'](function() {
              scope.sending = false;
              scope.success = false;
              modalService.open({
                template: 'common/modal/genericErrorModal.tpl.html',
                modalData: {
                  genericErrorMessage: 'Something went wrong. Please try again later.'
                }
              });
              $timeout(scope.removeWidget, 1000);
            });
        };


        scope.onTypeaheadInputChanged = function(query) {
          currPage = 0;
          refreshSuggestions(query, NUM_SUGGESTIONS);
          resizeInput();
        };

        init();
      }
    };
  }
]);
