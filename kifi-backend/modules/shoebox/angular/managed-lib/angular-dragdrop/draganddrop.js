/**
 * Created with IntelliJ IDEA.
 * User: Ganaraj.Pr
 * Date: 11/10/13
 * Time: 11:27
 * To change this template use File | Settings | File Templates.
 */
angular.module("ngDragDrop",[])
  .directive("uiDraggable", [
    '$parse',
    '$rootScope',
    function ($parse, $rootScope) {
      return function (scope, element, attrs) {
        if (window.jQuery && !window.jQuery.event.props.dataTransfer) {
          window.jQuery.event.props.push('dataTransfer');
        }
        element.attr("draggable", false);
        attrs.$observe("uiDraggable", function (newValue) {
          element.attr("draggable", newValue);
        });
        var dragData = "";
        scope.$watch(attrs.drag, function (newValue) {
          dragData = newValue;
        });
        element.bind("dragstart", function (e) {
          var sendData = angular.toJson(dragData);
          var sendChannel = attrs.dragChannel || "defaultchannel";
          e.dataTransfer.setData("Text", sendData);
          $rootScope.$broadcast("ANGULAR_DRAG_START", sendChannel);

        });

        //For IE
        element.bind("selectstart",function() {
          this.dragDrop();
          return false;
        }, false);

        element.bind("dragend", function (e) {
          console.log("triggered");
          var sendChannel = attrs.dragChannel || "defaultchannel";
          $rootScope.$broadcast("ANGULAR_DRAG_END", sendChannel);
          if (e.dataTransfer && e.dataTransfer.dropEffect !== "none") {
            if (attrs.onDropSuccess) {
              var fn = $parse(attrs.onDropSuccess);
              scope.$apply(function () {
                fn(scope, {$event: e});
              });
            }
          }
        });


      };
    }
  ])
  .directive("uiOnDrop", [
    '$parse',
    '$rootScope',
    function ($parse, $rootScope) {
      return function (scope, element, attr) {
        var dropChannel = "defaultchannel";
        var dragChannel = "";
        var dragEnterClass = attr.dragEnterClass || "on-drag-enter";

        function onDragOver(e) {

          if (e.preventDefault) {
            e.preventDefault(); // Necessary. Allows us to drop.
          }

          if (e.stopPropagation) {
            e.stopPropagation();
          }
          e.dataTransfer.dropEffect = 'move';
          return false;
        }

        function onDrop(e) {
          if (e.preventDefault) {
            e.preventDefault(); // Necessary. Allows us to drop.
          }
          if (e.stopPropagation) {
            e.stopPropagation(); // Necessary. Allows us to drop.
          }
          var data = e.dataTransfer.getData("Text");
          data = angular.fromJson(data);
          var fn = $parse(attr.uiOnDrop);
          scope.$apply(function () {
            fn(scope, {$data: data, $event: e});
          });
          element.removeClass(dragEnterClass);
        }


        $rootScope.$on("ANGULAR_DRAG_START", function (event, channel) {
          dragChannel = channel;
          if (dropChannel === channel) {

            element.bind("dragover", onDragOver);

            element.bind("drop", onDrop);
            element.addClass(dragEnterClass);
          }

        });



        $rootScope.$on("ANGULAR_DRAG_END", function (e, channel) {
          dragChannel = "";
          if (dropChannel === channel) {

            element.unbind("dragover", onDragOver);

            element.unbind("drop", onDrop);
            element.removeClass(dragEnterClass);
          }
        });


        attr.$observe('dropChannel', function (value) {
          if (value) {
            dropChannel = value;
          }
        });

      };
    }
  ]);
