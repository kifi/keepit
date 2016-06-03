'use strict';

angular.module('kifi')

.directive('kfSendKeepWidget', [
  '$document', '$templateCache', '$rootElement', '$timeout', '$window', '$compile', 'KEY', 'keepService', 'profileService', 'modalService',
  function($document, $templateCache, $rootElement, $timeout, $window, $compile, KEY, keepService, profileService, modalService) {

    var numSuggestions = 5;

    var desiredMarginTop = 60;
    var desiredShiftVertical = 30;

    return {
      restrict: 'A',
      scope : {
        keep: '='
      },
      link: function(scope, element) {
        var currPage = 0;
        var widget;
        var init;
        var filteredSuggestions = []; // ids of entities to filter from suggestions (keep.members + scope.selected)

        scope.hasExperiment = profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('add_keep_recipients') !== -1;

        function listenForInit() {
          element.on('click', function () {
            initWidget();
          });

          scope.$on('$destroy', scope.removeWidget);
        }

        function initWidget() {
          scope.success = false;
          scope.sending = false;
          scope.showCreateLibrary = false;
          scope.init = false; // set to true once widget is ready to be shown

          scope.suggestions = [];
          scope.selections = [];
          init = false;

          filteredSuggestions = computeKeepMembers(scope.keep); // ids of keep.members + scope.selections to omit

          scope.typeahead = '';
          currPage = 0;

          refreshSuggestions(null, numSuggestions);

          widget = angular.element($templateCache.get('keep/sendKeepWidget.tpl.html'));
          $rootElement.find('html').append(widget);
          $compile(widget)(scope);
          widget.hide();
          $timeout(setInitialPosition, 0);
          scope.$watch('init', function () {
            if (scope.init) {
              $timeout(function () {
                widget.show();
              }, 0);
            }
          });

          $document.on('mousedown', onClick);
        }


        function setInitialPosition() {

          // try to load the first batch of suggestions before calculating the widget position,
          // if we can't wait for the response, go with a height estimate

          var elementOffsetTop = element.offset().top;
          var distanceFromBottom = $window.innerHeight - elementOffsetTop;

          var elementOffsetLeft = element.offset().left;
          var distanceFromRight = $window.innerWidth - elementOffsetLeft;

          // Place the widget such that 1) it's on the screen, and 2) does not obscure the keep members UI on the keep card.
          // If the widget can be placed above the keep members chips, set the bottom to be above it.
          // Else if the widget can be placed below the keep members chips, set the bottom s.t. the top is below it.
          // Else, do our best by placing the widget in middle of the keep
          var widgetHeight;

          if (scope.suggestions.length) {
            widgetHeight = widget.height();
          } else {
            var suggestionItem = widget.find('.kf-skw-suggestion'); // the No Results Found element
            widgetHeight = widget.height() + ((numSuggestions-3) * suggestionItem.height());
          }

          var bottom = null;

          if (elementOffsetTop - widgetHeight - desiredShiftVertical >= desiredMarginTop) {
            bottom = distanceFromBottom + desiredShiftVertical;
          } else if (distanceFromBottom - widgetHeight - desiredShiftVertical >= 0) {
            bottom = distanceFromBottom - widgetHeight - desiredShiftVertical;
          } else {
            bottom = distanceFromBottom - (widgetHeight/2);
          }

          widget.css({ bottom: bottom + 'px', right: distanceFromRight  + 'px' });
        }

        function setCreateLibraryPosition() {
          // rendering the create library page may increase the height of the widget,
          // so make sure it's not overflowing on top

          var widgetHeight = widget.height();
          var widgetOffsetTop = widget.offset().top;
          var distanceFromBottom = $window.innerHeight - widgetHeight - widgetOffsetTop;


          if (widgetOffsetTop < desiredMarginTop) {
            var bottom = distanceFromBottom - (desiredMarginTop - widgetOffsetTop);
            widget.css({ bottom: bottom + 'px' });
          }
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

        function refreshSuggestions(query, limit, offset) {
          return keepService.suggestRecipientsForKeep(query, limit, offset, null).then(function (resultData) {
            var nonMemberResults = resultData.results.filter(function(suggestion) {
              return filteredSuggestions.indexOf(suggestion.id || suggestion.email) === -1;
            });

            if (nonMemberResults.length === 0 && resultData.mayHaveMore) {
              refreshSuggestions(query, limit, (offset || 0) + limit);
            } else {
              scope.suggestions = nonMemberResults;
              if (widget && !init) {
                init = true;
                widget.show();
                resetInput();
              }
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
            input.css('width', ''); // reset to fit the placeholder text
          } else {
            var shadowInput = widget.find('.kf-skw-input-shadow');
            shadowInput[0].innerHTML = input[0].value;
            input.css('width', shadowInput.width() + 10);
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

        scope.removeWidget = function() {
          if (widget) {
            widget.remove();
          }
          $document.off('mousedown', onClick);
        };

        scope.selectSuggestion = function(suggestion) {
          scope.selections.push(suggestion);
          filteredSuggestions.push(suggestion.id || suggestion.email);
          suggestion.isSelected = true;

          if (widget.find('.kf-skw-suggestion').length === 1) {
            currPage++;
            refreshSuggestions(scope.typeahead, numSuggestions, currPage * numSuggestions);
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

        scope.onClickCreateLibrary = function() {
          scope.showCreateLibrary = true;

          $timeout(function() {
            setCreateLibraryPosition();
          }, 0);
        };

        scope.exitCreateLibrary = function() {
          scope.showCreateLibrary = false;
          resetInput();
        };

        scope.onceLibraryCreated = function(library) {
          var suggestion = convertLibraryToSuggestion(library);
          scope.selectSuggestion(suggestion);
          scope.showCreateLibrary = false;
          resetInput();
        };

        function convertLibraryToSuggestion(library) {
          var me = profileService.me;
          return {
            collaborators: [],
            color: library.color,
            hasCollaborators: false,
            id: library.id,
            kind: 'library',
            membership: library.membership,
            name: library.name,
            path: library.path,
            spaceName: me.firstName + (me.lastName ? ' ' + me.lastName : ''),
            visibility: library.visibility
          };
        }

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
              if (scope.selections.length) {
                scope.onSend();
              }
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
          refreshSuggestions(query, numSuggestions);
          resizeInput();
        };

        listenForInit();
      }
    };
  }
]);
