'use strict';

angular.module('kifi')

// This directive is based on code from:
// https://docs.angularjs.org/api/ng/type/ngModel.NgModelController
.directive('contenteditable', ['$sce', function($sce) {
  return {
    restrict: 'A', // only activate on element attribute
    require: '?ngModel', // get a hold of NgModelController
    link: function(scope, element, attrs, ngModel) {
      if (!ngModel) {
        return; // do nothing if no ng-model
      }

      // Write data to the model
      function read() {
        var html = element.html();

        if (!element[0].textContent.trim()) {
          html = '';
        }

        ngModel.$setViewValue(html);
        ngModel.$render();
      }

      // Specify how UI should be updated
      ngModel.$render = function() {
        element.html($sce.getTrustedHtml(ngModel.$viewValue || ''));
      };

      // Listen for change events to enable binding
      element.on('blur keyup change', function() {
        scope.$apply(read);
      });

      read(); // initialize
    }
  };
}]);
