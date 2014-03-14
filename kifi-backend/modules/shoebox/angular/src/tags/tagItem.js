'use strict';

angular.module('kifi.tagItem', ['kifi.tagService'])

.directive('kfTagItem', [
  '$timeout', '$location', '$document', 'tagService', 'keyIndices',
  function ($timeout, $location, $document, tagService, keyIndices) {
    return {
      restrict: 'A',
      scope: {
        tag: '=',
        takeFocus: '&',
        releaseFocus: '&'
      },
      replace: true,
      templateUrl: 'tags/tagItem.tpl.html',
      link: function (scope, element) {
        scope.isDragging = false;
        scope.isRenaming = false;
        scope.isDropdownOpen = false;
        scope.renameTag = {};
        var input = element.find('input');
        element.bind('dragstart', function () {
          scope.$apply(function () { scope.isDragging = true; });
        });
        element.bind('dragend', function () {
          scope.$apply(function () { scope.isDragging = false; });
        });

        scope.onKeepDrop = function (keep, tag) {
          tagService.addKeepToTag(tag, keep);
          scope.isDragTarget = false;
        };

        scope.navigateToTag = function (event) {
          if (scope.isRenaming) {
            event.stopPropagation();
          } else {
            $location.path('/tag/' + scope.tag.id);
          }
        };

        scope.setRenaming = function () {
          closeDropdown();
          scope.takeFocus();
          scope.isRenaming = true;
          scope.renameTag.value = scope.tag.name;
          $timeout(function () {
            input.focus();
            input.select();
          });
        };

        scope.remove = function () {
          closeDropdown();
          return tagService.remove(scope.tag.id);
        };

        scope.onRenameKeydown = function (e) {
          switch (e.keyCode) {
            case keyIndices.KEY_ENTER:
              scope.submitRename();
              break;
            case keyIndices.KEY_ESC:
              scope.cancelRename();
              break;
          }
        };

        scope.submitRename = function () {
          var newName = scope.renameTag.value;
          if (newName && newName !== scope.tag.name) {
            return tagService.rename(scope.tag.id, newName).then(function () {
              scope.cancelRename();
            });
          }
          scope.cancelRename();
        };

        scope.cancelRename = function () {
          scope.isRenaming = false;
          scope.releaseFocus();
        };

        function closeDropdown() {
          scope.isDropdownOpen = false;
          $document.unbind('click', applyCloseDropdown);
        }

        function applyCloseDropdown() {
          scope.$apply(closeDropdown);
        }

        scope.toggleDropdown = function () {
          if (!scope.isDropdownOpen) {
            scope.isDropdownOpen = true;
            $document.bind('click', applyCloseDropdown);
          } else {
            closeDropdown();
          }
        };

        input.on('blur', function () {
          scope.$apply(function () { scope.cancelRename(); });
        });

        var dragMask = element.find('.kf-drag-mask');
        scope.isDragTarget = false;

        dragMask.on('dragenter', function () {
          scope.$apply(function () { scope.isDragTarget = true; });
        });

        dragMask.on('dragleave', function () {
          scope.$apply(function () { scope.isDragTarget = false; });
        });
      }
    };
  }
]);
