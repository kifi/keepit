'use strict';

angular.module('kifi')

.directive('kfSendKeepWidget', [
  '$document', '$templateCache', '$rootElement', '$timeout', '$window', '$compile', 'KEY', 'keepService', 'profileService', 'modalService', 'util',
  function($document, $templateCache, $rootElement, $timeout, $window, $compile, KEY, keepService, profileService, modalService, util) {

    var numSuggestions = 5;
    var maxSuggestionsToShowEmail = 2;
    var desiredMarginTop = 45;
    var initialBottom = 20;
    var initialRight = -20;

    return {
      restrict: 'A',
      scope : {
        keep: '='
      },
      link: function(scope, element) {
        var currPage = 0;
        var widget;

        var canAddParticipants = scope.keep.permissions && scope.keep.permissions.indexOf('add_participants') !== -1;
        var canAddLibraries = scope.keep.permissions && scope.keep.permissions.indexOf('add_libraries') !== -1;
        var typeaheadFilter = (canAddParticipants ? ['user', 'email'] : []).concat(canAddLibraries ? ['library'] : []).join(',');

        scope.hasExperiment = profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('add_keep_recipients') !== -1;
        scope.maxSuggestionsToShowEmail = maxSuggestionsToShowEmail;

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

          scope.typeahead = '';
          scope.validEmail = false;
          currPage = 0;

          refreshSuggestions(null, numSuggestions);

          widget = angular.element($templateCache.get('keep/sendKeepWidget.tpl.html'));
          element.parents('.kf-keep').append(widget);
          $compile(widget)(scope);
          widget.hide();
          setInitialPosition();
          scope.$watch('init', function () {
            if (scope.init) {
              $timeout(function () {
                widget.show();
                adjustWidgetPosition();
              }, 0);
            }
          });

          $document.on('mousedown', onClick);
        }


        function setInitialPosition() {
          widget.css({ bottom: initialBottom + 'px', right: initialRight  + 'px' });
        }

        function adjustWidgetPosition() {
          // rendering the create library page may increase the height of the widget,
          // so make sure it's not overflowing on top

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

        function refreshSuggestions(query, limit, offset) {
          query = query || '';
          limit = limit || numSuggestions;
          offset = offset || 0;
          return keepService.suggestRecipientsForKeep(query || '', limit, offset, typeaheadFilter, scope.keep.pubId).then(function (resultData) {
            var nonSelectedResults = resultData.results.filter(function (suggestion) {
              var suggId = suggestion.id || suggestion.email;
              return !scope.selections.find(function(selection) {
                return (selection.id || selection.email) === suggId;
              });
            });

            if (nonSelectedResults.length <= maxSuggestionsToShowEmail) {
              scope.showNewEmail = true;
            }

            if (nonSelectedResults.length === 0 && resultData.mayHaveMore) {
              refreshSuggestions(query, limit, offset + limit);
            } else {
              scope.suggestions = nonSelectedResults;
              if (widget && !scope.init) {
                scope.init = true;
                resetInput();
              } else {
                $timeout(adjustWidgetPosition, 50);
              }
            }
          });
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

        scope.removeWidget = function() {
          if (widget) {
            widget.remove();
          }
          $document.off('mousedown', onClick);
        };

        scope.selectSuggestion = function(suggestion) {
          scope.selections.push(suggestion);
          refreshSuggestions();
          resetInput();
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
              if (scope.typeahead === '') {
                event.preventDefault();
                scope.removeSelection(scope.selections.pop());
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
        };


        scope.onTypeaheadInputChanged = function(query) {
          currPage = 0;
          refreshSuggestions(query, numSuggestions);
          resizeInput();
          scope.validEmail = util.validateEmail(scope.typeahead);
        };

        listenForInit();
      }
    };
  }
]);
