'use strict';

angular.module('kifi.tagItem', ['kifi.tagService'])

.directive('kfTagItem', [
  '$timeout', '$document', 'tagService', 'keyIndices',
  function ($timeout, $document, tagService, keyIndices) {
    return {
      restrict: 'A',
      scope: {
        tag: '=',
        watchTagReorder: '&',
        reorderTag: '&',
        hasNewLocation: '&',
        viewTag: '&',
        removeTag: '&',
        tagDragSource: '=',
        tagDragTarget: '='
      },
      replace: true,
      templateUrl: 'tags/tagItem.tpl.html',
      link: function (scope, element) {
        scope.isRenaming = false;
        scope.isWaiting = false;
        scope.isDropdownOpen = false;
        scope.renameTag = {};
        var input = element.find('input');
        var waitingTimeout;
        var clone, cloneMask;
        var tagList = element.parent();

        scope.onKeepDrop = function (keeps) {
          waitingTimeout = $timeout(function () {
            scope.isWaiting = true;
          }, 500);
          scope.isDragTarget = false;
          tagService.addKeepsToTag(scope.tag, keeps).then(function () {
            $timeout.cancel(waitingTimeout);
            scope.isWaiting = false;
          });
        };

        scope.navigateToTag = function (event) {
          if (scope.isRenaming) {
            event.stopPropagation();
          } else {
            scope.viewTag({tagId: scope.tag.id});
          }
        };

        scope.setRenaming = function () {
          scope.isRenaming = true;
          scope.renameTag.value = scope.tag.name;
          $timeout(function () {
            input.focus();
            input.select();
          });
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
        };

        function removeCloseDropdownHandler() {
          $document.unbind('mousedown', applyCloseDropdown);
        }

        function closeDropdown() {
          scope.isDropdownOpen = false;
          removeCloseDropdownHandler();
        }

        function applyCloseDropdown() {
          scope.$apply(closeDropdown);
        }

        scope.toggleDropdown = function (e) {
          console.log("toggling");
          e.stopPropagation();
          e.preventDefault();
          if (!scope.isDropdownOpen) {
            scope.isDropdownOpen = true;
            $document.bind('mousedown', applyCloseDropdown);
          } else {
            closeDropdown();
          }
        };

        scope.$on('$destroy', function () {
          removeCloseDropdownHandler();
        });

        input.on('blur', function () {
          scope.$apply(function () { scope.cancelRename(); });
        });

        var keepDragMask = element.find('.kf-drag-mask');
        scope.isDragTarget = false;

        keepDragMask.on('dragenter', function () {
          scope.$apply(function () { scope.isDragTarget = true; });
        });

        keepDragMask.on('dragleave', function () {
          scope.$apply(function () { scope.isDragTarget = false; });
        });

        element.on('dragstart', function (e) {
          scope.$apply(function () {
            if (!scope.watchTagReorder()) { return; }
            scope.tagDragSource = scope.tag;
            tagList.addClass('kf-tag-list-reordering');

            var clonePositioning = {
              position: 'fixed',
              left: 0,
              top: 0,
              width: element.css('width'),
              height: element.css('height'),
              zIndex: -1
            };

            clone = element.clone()
              .css(clonePositioning)
              .addClass('kf-dragged-clone');
            cloneMask = angular.element('<li></li>')
              .css(clonePositioning)
              .addClass('kf-dragged-clone-mask');
            element.addClass('kf-dragged')
              .after(clone, cloneMask);
            e.dataTransfer.setDragImage(clone[0], 0, 0);
          });
        })
        .on('dragend', function () {
          tagList.removeClass('kf-tag-list-reordering');

          element.removeClass('kf-dragged');
          if (clone) { clone.remove(); }
          if (cloneMask) { cloneMask.remove(); }
          //scope.tagDragTarget = null;
        })
        .on('drag', function (e) {
          var x = e.originalEvent.pageX,
            y = e.originalEvent.pageY,
            top = tagList.offset().top,
            left = tagList.offset().left,
            right = left + tagList.width(),
            bottom = top + tagList.height();
          if (x < left || x > right || y < top || y > bottom) {
            clone.after(element);
          }
        })
        .on('dragover', function (e) {
          e.preventDefault();
        })
        .on('drop', function () {
          scope.reorderTag({srcTag: scope.tagDragSource, dstTag: scope.tagDragTarget, isAfter: false});
        });

        // Setup tag drag target
        element.find('.kf-tag-drag-mask')
        .on('dragenter', function () {
          scope.$apply(function () {
            var el = element.parent().find('.kf-dragged');
            element.before(el);
            if (scope.tag.id !== scope.tagDragSource.id) {
              scope.tagDragTarget = scope.tag;
            }
          });
        })

        var newLocationMask = element.find('.kf-tag-new-location-mask');
        scope.$watch(scope.hasNewLocation, function (value) {
          if (value) {
            newLocationMask.removeClass('hidden');
            $timeout(function () {
              newLocationMask.addClass('hidden');
            });
          }
        });
      }
    };
  }
]);
