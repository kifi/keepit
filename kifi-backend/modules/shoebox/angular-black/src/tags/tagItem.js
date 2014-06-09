'use strict';

angular.module('kifi.tagItem', ['kifi.tagService', 'kifi.dragService'])

.directive('kfTagItem', [
  '$timeout', '$document', 'tagService', 'keyIndices', 'dragService',
  function ($timeout, $document, tagService, keyIndices, dragService) {
    return {
      restrict: 'A',
      scope: {
        tag: '=',
        watchTagReorder: '&',
        viewTag: '&',
        removeTag: '&',
        tagDragSource: '=',
        targetIdx: '=',
        index: '@'
      },
      replace: true,
      templateUrl: 'tags/tagItem.tpl.html',
      link: function (scope, element, attrs) {
        scope.isFake = attrs.fake !== undefined;
        scope.isHovering = false;
        scope.isRenaming = false;
        scope.isWaiting = false;
        scope.isDropdownOpen = false;
        scope.renameTag = {};
        var input = element.find('input');
        var waitingTimeout;
        var clone, cloneMask;
        var tagList = element.parent();

        element.attr('draggable', true);

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
          // Firefox requires data to be set
          e.dataTransfer.setData('text/plain', '');
          e.dataTransfer.effectAllowed = 'none';
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
        .on('dragend', function (e) {
          e.preventDefault();
          tagList.removeClass('kf-tag-list-reordering');
          function removeAnimate() {
            element.removeClass('animate');
            element.off('animationend webkitAnimationEnd oanimationend MSAnimationEnd', removeAnimate);
          }
          element.on('animationend webkitAnimationEnd oanimationend MSAnimationEnd', removeAnimate);
          element.addClass('animate');

          element.removeClass('kf-dragged');
          if (clone) { clone.remove(); }
          if (cloneMask) { cloneMask.remove(); }
        })
        .on('drag', function () {
          var dragPosition = dragService.getDragPosition(),
            x = dragPosition.pageX,
            y = dragPosition.pageY,
            left = tagList.offset().left - window.pageXOffset,
            top = tagList.offset().top - window.pageYOffset,
            right = left + tagList.width(),
            bottom = top + tagList.height();
          if (x < left || x > right || y < top || y > bottom) {
            clone.after(element);
          }
        })
        .on('dragover', function (e) {
          e.preventDefault();
        })
        .on('drop', function (e) {
          e.preventDefault();
          tagService.reorderTag(scope.tagDragSource, scope.targetIdx);
        })
        .on('mouseenter', function () {
          scope.$apply(function () { scope.isHovering = true; });
        })
        .on('mouseleave', function () {
          scope.$apply(function () { scope.isHovering = false; });
        });

        // Setup tag drag target
        element.find('.kf-tag-drag-mask')
        .on('dragenter', function () {
          scope.$apply(function () {
            var el = element.parent().find('.kf-dragged');
            element.before(el);
            if (!scope.tag || scope.tag.id !== scope.tagDragSource.id) {
              scope.targetIdx = parseInt(scope.index, 10);
            }
          });
        });
      }
    };
  }
]);
