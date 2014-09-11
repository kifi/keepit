'use strict';

angular.module('kifi')

.directive('kfTagItem', [
  '$timeout', '$document', '$rootScope', 'tagService', 'keepService', 'keyIndices', 'dragService',
  function ($timeout, $document, $rootScope, tagService, keepService, keyIndices, dragService) {
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
        if (!scope.tag) {
          // fake tag element
          element.addClass('kf-fake-tag-item');
        }

        scope.isFake = attrs.fake !== undefined;
        scope.isHovering = false;
        scope.isWaiting = false;
        scope.isDropdownOpen = false;
        var clone, cloneMask;
        var tagList = element.parent();

        if (window.jQuery && !window.jQuery.event.props.dataTransfer) {
          window.jQuery.event.props.push('dataTransfer');
        }

        /* We shouldn't need custom logic for this - it looks like the ="true" on the attribute gets
         * stripped off at some point (angular bug?)
         */
        scope.$watch('watchTagReorder()', function (res) {
          if (res) {
            element.attr('draggable', 'true');
          } else {
            element.attr('draggable', 'false');
          }
        });

        scope.navigateToTag = function (event) {
          if (!scope.tag) {
            event.stopPropagation();
          } else {
            scope.viewTag({tagId: scope.tag.id});
          }
        };

        function removeCloseDropdownHandler() {
          document.documentElement.removeEventListener('click', applyCloseDropdown, true);
        }

        function applyCloseDropdown(e) {
          e.stopPropagation();
          e.preventDefault();
          removeCloseDropdownHandler();
          scope.$apply(function () {
            scope.isDropdownOpen = false;
          });
        }

        scope.toggleDropdown = function (e) {
          e.stopPropagation();
          e.preventDefault();
          scope.isDropdownOpen = true;
          document.documentElement.addEventListener('click', applyCloseDropdown, true);
        };

        scope.cancelNavigation = function (e) {
          e.stopPropagation();
        };

        scope.$on('$destroy', function () {
          removeCloseDropdownHandler();
        });

        var keepDragMask = element.find('.kf-keep-drag-mask');
        scope.isDragTarget = false;

        function addKeepDragoverStyling() {
          if ($rootScope.DRAGGING_KEEP) {
            element.addClass('kf-keep-dragover-tag');
          }
        }
        function removeKeepDragoverStyling() {
          if ($rootScope.DRAGGING_KEEP) {
            element.removeClass('kf-keep-dragover-tag');
          }
        }

        keepDragMask.on('dragenter', addKeepDragoverStyling)
        .on('dragleave', removeKeepDragoverStyling)
        .on('drop', removeKeepDragoverStyling);

        function animate() {
          function removeAnimate() {
            element.removeClass('animate');
            element.off('animationend webkitAnimationEnd oanimationend MSAnimationEnd', removeAnimate);
          }
          element.on('animationend webkitAnimationEnd oanimationend MSAnimationEnd', removeAnimate);
          element.addClass('animate');
        }

        if (scope.tag && scope.tag.isNew) {
          animate();
        }

        element.on('dragstart', function (e) {
          // Firefox requires data to be set
          e.dataTransfer.setData('text', '');
          //e.dataTransfer.effectAllowed = 'none';
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
            if (typeof(e.dataTransfer.setDragImage) === 'function') {
              e.dataTransfer.setDragImage(clone[0], 0, 0);
            }
          });
        })
        .on('dragend', function (e) {
          e.preventDefault();
          tagList.removeClass('kf-tag-list-reordering');
          animate();

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
          var data = e.dataTransfer.getData('Text');
          if (scope.tag && data.length > 0) {
            // keep drop
            var keeps = angular.fromJson(data);
            // TODO(yiping): figure out what buildKeep does here.
            // keeps.forEach(keepService.buildKeep);
            // keeps.forEach(function (keep) { keep.buildKeep(keep); });
            tagService.addKeepsToTag(scope.tag, keeps);
            animate();
          } else {
            if (scope.targetIdx !== null) {
              tagService.reorderTag(scope.tagDragSource, scope.targetIdx);
              scope.targetIdx = null;
            }
          }
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
