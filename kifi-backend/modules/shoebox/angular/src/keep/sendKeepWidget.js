'use strict';

angular.module('kifi')

.directive('kfSendKeepWidget', [
  '$compile', '$templateCache', '$document', '$window', '$rootElement', '$state', '$timeout', '$analytics',
  'keepService', 'profileService', 'modalService', 'KEY', 'Paginator', 'util',
  function($compile, $templateCache, $document, $window, $rootElement, $state, $timeout,  $analytics,
    keepService, profileService, modalService, KEY, Paginator, util) {

    var numSuggestions = 5;
    var maxSuggestionsToShowEmail = 2;
    var desiredMarginTop = 45;
    var initialRight = -20;

    return {
      restrict: 'A',
      scope : {
        keep: '='
      },
      link: function(scope, element) {
        var currPage = 0;
        var widget;
        var highlightedIndex = 0;
        var suggestionPaginator;
        var justScrolled;
        var keepCardElement;

        var canAddParticipants = scope.keep.permissions && scope.keep.permissions.indexOf('add_participants') !== -1;
        var canAddLibraries = scope.keep.permissions && scope.keep.permissions.indexOf('add_libraries') !== -1;
        var typeaheadFilter = (canAddParticipants ? ['user', 'email'] : []).concat(canAddLibraries ? ['library'] : []).join(',');

        scope.hasExperiment = profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('add_keep_recipients') !== -1;
        scope.maxSuggestionsToShowEmail = maxSuggestionsToShowEmail;

        function listenForInit() {
          element.on('click', function () {
            initWidget();
            clickTrack('open');
          });

          scope.$on('$destroy', scope.removeWidget);
        }

        function initWidget() {
          scope.success = false;
          scope.sending = false;
          scope.justScrolled = false;
          scope.showCreateLibrary = false;
          scope.init = false; // set to true once widget is ready to be shown

          scope.suggestions = [];
          scope.selections = [];

          suggestionPaginator = new Paginator(suggestionSource, numSuggestions, Paginator.DONE_WHEN_RESPONSE_IS_EMPTY);

          scope.typeahead = '';
          scope.validEmail = false;
          currPage = 0;

          widget = angular.element($templateCache.get('keep/sendKeepWidget.tpl.html'));
          keepCardElement = element.parents('.kf-keep');
          keepCardElement.append(widget);
          $compile(widget)(scope);
          widget.hide();
          setInitialPosition();
          scope.$watch('init', function () {
            if (scope.init) {
              $timeout(function () {
                widget.show();
                adjustWidgetPosition();
                if (scope.suggestions.length) {
                  scope.suggestions[0].isHighlighted = true;
                }
                resetInput();
              }, 0);
              $analytics.eventTrack('user_viewed_page', { type: 'addParticipantsWidget' });
            }
          });

          $document.on('mousedown', onClick);
        }


        function setInitialPosition() {
          var elementOffset = element.offset();
          var keepCardElementOffset = keepCardElement.offset();
          var keepCardHeight = keepCardElement.height();

          widget.css({ bottom: keepCardHeight - (elementOffset.top - keepCardElementOffset.top) + 'px', right: initialRight  + 'px' });
        }

        function adjustWidgetPosition() {
          // shift widget down to avoid top-overflow

          var widgetOffsetTop = widget.offset().top;

          if (widgetOffsetTop < desiredMarginTop) {
            var shiftDown = (desiredMarginTop - widgetOffsetTop);
            var bottom = parseInt(widget.css('bottom').slice(0,-2), 10);
            widget.css({ bottom: (bottom - shiftDown) + 'px' });
          }
        }

        function onClick(event) {
          if (!angular.element(event.target).closest('.kf-send-keep-widget').length) {
            scope.$apply(scope.removeWidget);
          }
        }

        function resetInput() {
          scope.typeahead = '';
          scope.validEmail = false;
          $timeout(function() {
            resizeInput();
          }, 0);
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

        function updateKeepMembers() {
          scope.selections.forEach(function(selection) {
            if (selection.kind === 'user') {
              scope.keep.members.users.push({ 'user': selection });
            } else if (selection.kind === 'library') {
              scope.keep.members.libraries.push({ 'library': selection });
            } else if (selection.kind === 'email') {
              scope.keep.members.emails.push(selection);
            }
          });
        }

        function suggestionSource(pageNumber, pageSize) {
          return keepService.suggestRecipientsForKeep(scope.typeahead, pageSize, pageNumber * pageSize, typeaheadFilter, scope.keep.pubId).then(function(res) {
            return res.results.filter(function (suggestion) {
              var suggId = suggestion.id || suggestion.email;
              return !scope.selections.find(function (selection) {
                return suggId === (selection.id || selection.email);
              });
            });
          });
        }

        function clearHighlights() {
          scope.suggestions.forEach(function(suggestion) {
            suggestion.isHighlighted = false;
          });
        }

        function adjustScroll(selectedIndex) {
          var suggestionListElement = widget.find('.kf-skw-suggestions');
          var suggestionElementHeight = widget.find('.kf-skw-suggestion').height();
          var suggestionListElementHeight = suggestionListElement.height();
          var numSuggestionsVisible = suggestionListElementHeight / suggestionElementHeight;

          var selectionTop = selectedIndex * suggestionElementHeight;
          var visibleTop = suggestionListElement.scrollTop();
          var visibleBottom = visibleTop + suggestionListElementHeight;

          if (selectionTop < visibleTop) {
            suggestionListElement.scrollTop(selectionTop);
          } else if (selectionTop > visibleBottom) {
            suggestionListElement.scrollTop(selectionTop - (numSuggestionsVisible * suggestionElementHeight));
          }

          justScrolled = true;
          $timeout(function() {
            justScrolled = false;
          }, 500);
        }

        function clickTrack(action) {
          $analytics.eventTrack('user_clicked_page', { type: $state.$current.name, action: 'clickedAddParticipants:' + action });
        }

        scope.removeWidget = function() {
          if (widget) {
            widget.remove();
            clickTrack('close');
          }

          $document.off('mousedown', onClick);
        };

        scope.onHoverSuggestion = function(suggestion) {
          if (!justScrolled) {
            clearHighlights();
            highlightedIndex = _.indexOf(scope.suggestions, suggestion);
            suggestion.isHighlighted = true;
          }
        };

        scope.onUnhoverSuggestion = function(suggestion) {
          if (!justScrolled) {
            suggestion.isHighlighted = false;
          }
        };

        scope.fetchSuggestions = function () {
          return suggestionPaginator.fetch().then(function(suggestions) {
            scope.suggestions = suggestions;
            if (widget && !scope.init) {
              scope.init = true;
            }
          });
        };

        scope.selectSuggestion = function(suggestion) {
          scope.selections.push(suggestion);
          resetInput();
          suggestionPaginator.reset();
          scope.fetchSuggestions().then(function() {
            suggestionPaginator.items = suggestionPaginator.items.filter(function (s) {
              return (s.id || s.email) !== (suggestion.id || suggestion.email);
            });
            scope.suggestions = scope.suggestions.filter(function (s) {
              return (s.id || s.email) !== (suggestion.id || suggestion.email);
            });
            highlightedIndex = 0;
            scope.suggestions[highlightedIndex].isHighlighted = true;
            adjustScroll(highlightedIndex);
          });
        };

        scope.removeSelection = function(selection) {
          scope.selections = scope.selections.filter(function(s) {
            return s !== selection;
          });
          scope.suggestions = [selection].concat(scope.suggestions);
          resetInput();
        };

        scope.onClickCreateLibrary = function() {
          scope.showCreateLibrary = true;

          $timeout(function() {
            adjustWidgetPosition();
          }, 100);

          clickTrack('createLibrary');
        };

        scope.exitCreateLibrary = function() {
          scope.showCreateLibrary = false;
          setInitialPosition();
          $timeout(adjustWidgetPosition, 0);
          resetInput();
        };

        scope.onceLibraryCreated = function(library) {
          var suggestion = convertLibraryToSuggestion(library);
          scope.selectSuggestion(suggestion);
          scope.showCreateLibrary = false;
          setInitialPosition();
          $timeout(adjustWidgetPosition, 0);
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
              event.preventDefault();
              if (scope.selections.length && scope.typeahead === '') {
                scope.removeSelection(scope.selections[scope.selections.length-1]);
                clearHighlights();
                highlightedIndex++;
                scope.suggestions[highlightedIndex].isHighlighted = true;
              } else if (scope.typeahead !== '') {
                scope.typeahead = scope.typeahead.slice(0, -1);
                scope.onTypeaheadInputChanged();
              }
              break;
            case KEY.ENTER:
              event.preventDefault();
              var highlightedSuggestion = scope.suggestions.find(function (sugg) {
                return sugg.isHighlighted;
              });
              if (highlightedSuggestion) {
                scope.selectSuggestion(highlightedSuggestion);
                if (scope.suggestions.length) {
                  clearHighlights();
                  highlightedIndex = highlightedIndex < scope.suggestions.length ? highlightedIndex : scope.suggestions.length - 1;
                  scope.suggestions[highlightedIndex].isHighlighted = true;
                }
              }
              break;
            case KEY.DOWN:
            case KEY.UP:
              event.preventDefault();
              clearHighlights();
              if (event.keyCode === KEY.DOWN) {
                if (highlightedIndex === scope.suggestions.length-1) {
                  scope.fetchSuggestions();
                }
                highlightedIndex = highlightedIndex === scope.suggestions.length-1 ? highlightedIndex : highlightedIndex + 1;
                scope.suggestions[highlightedIndex].isHighlighted = true;
                adjustScroll(highlightedIndex+2);
              } else {
                highlightedIndex = highlightedIndex === 0 ? 0 : highlightedIndex - 1;
                scope.suggestions[highlightedIndex].isHighlighted = true;
                adjustScroll(highlightedIndex);
              }
              break;
            case KEY.ESC:
              event.preventDefault();
              scope.removeWidget();
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
              updateKeepMembers();
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

          clickTrack('send');
        };


        scope.onTypeaheadInputChanged = function() {
          currPage = 0;
          suggestionPaginator.reset();
          scope.fetchSuggestions().then(function () {
            clearHighlights();
            highlightedIndex = 0;
            if (scope.suggestions.length) {
              clearHighlights();
              scope.suggestions[highlightedIndex].isHighlighted = true;
            }
            adjustScroll(highlightedIndex);
          });
          resizeInput();
          scope.validEmail = util.validateEmail(scope.typeahead);
        };

        listenForInit();
      }
    };
  }
]);
