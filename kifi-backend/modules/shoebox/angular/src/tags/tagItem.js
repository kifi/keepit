'use strict';

angular.module('kifi.tagItem', ['kifi.tagService'])

.directive('kfTagItem', [
  '$timeout', '$document', 'tagService', 'keyIndices', 'util',
  function ($timeout, $document, tagService, keyIndices, util) {
    return {
      restrict: 'A',
      scope: {
        tag: '=',
        takeFocus: '&',
        releaseFocus: '&',
        watchTagReorder: '&',
        reorderTag: '&',
        hasNewLocation: '&',
        viewTag: '&',
        removeTag: '&'
      },
      replace: true,
      templateUrl: 'tags/tagItem.tpl.html',
      link: function (scope, element) {
        scope.isRenaming = false;
        scope.isWaiting = false;
        scope.isDropdownOpen = false;
        scope.renameTag = {};
        scope.isHovering = false;
        var input = element.find('input');
        var waitingTimeout;

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
          scope.removeTag({tag: scope.tag});
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

        scope.toggleDropdown = function (e) {
          e.stopPropagation();
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

        var keepDragMask = element.find('.kf-drag-mask');
        scope.isDragTarget = false;

        keepDragMask.on('dragenter', function () {
          scope.$apply(function () { scope.isDragTarget = true; });
        });

        keepDragMask.on('dragleave', function () {
          scope.$apply(function () { scope.isDragTarget = false; });
        });

        var tagDragMask = element.find('.kf-tag-drag-mask');
        var tagDraggedUpon = false;
        var yBoundary = parseInt(element.css('height'), 10) / 2;
        var isTop = null;

        function startTagDrag() {
          tagDraggedUpon = true;
          isTop = null;
        }

        function stopTagDrag() {
          tagDraggedUpon = false;
          tagDragMask.css({borderTopStyle: 'none', borderBottomStyle: 'none', margin: 0});
        }

        tagDragMask.on('dragenter', startTagDrag);
        tagDragMask.on('dragover', function (e) {
          if (tagDraggedUpon && scope.watchTagReorder()) {
            var posY = e.originalEvent.clientY - util.offset(element).top;
            if (posY > yBoundary) {
              isTop = false;
              tagDragMask.css({borderTopStyle: 'none', borderBottomStyle: 'dotted', marginTop: '1px'});
            } else {
              isTop = true;
              tagDragMask.css({borderTopStyle: 'dotted', borderBottomStyle: 'none', marginTop: 0});
            }
          }
        });
        tagDragMask.on('dragleave', stopTagDrag);

        scope.onTagDrop = function (tag) {
          stopTagDrag();
          if (isTop !== null && scope.watchTagReorder()) {
            // The "dragend" handler must be called before removing the element from the DOM.
            element.find('.kf-nav-link').triggerHandler('dragend');
            scope.reorderTag({isTop: isTop, srcTag: tag, dstTag: scope.tag});
          }
        };

        scope.isDragging = false;
        var clone;
        var mouseX, mouseY;
        element.bind('mousemove', function (e) {
          mouseX = e.pageX - util.offset(element).left;
          mouseY = e.pageY - util.offset(element).top;
        });
        element.bind('dragstart', function (e) {
          element.addClass('kf-dragged');
          clone = element.clone().css({
            position: 'absolute',
            left: 0,
            top: 0,
            width: element.css('width'),
            height: element.css('height'),
            zIndex: -1
          });
          element.parent().after(clone);
          e.dataTransfer.setDragImage(clone[0], mouseX, mouseY);
          scope.$apply(function () { scope.isDragging = true; });
        });
        element.bind('dragend', function () {
          element.removeClass('kf-dragged');
          clone.remove();
          scope.$apply(function () { scope.isDragging = false; });
        });

        var newLocationMask = element.find('.kf-tag-new-location-mask');
        scope.$watch(scope.hasNewLocation, function (value) {
          if (value) {
            newLocationMask.removeClass('hidden');
            $timeout(function () {
              newLocationMask.addClass('hidden');
            });
          }
        });

        scope.enableHover = function () {
          scope.isHovering = true;
        };

        scope.disableHover = function () {
          scope.isHovering = false;
        };
      }
    };
  }
]);
