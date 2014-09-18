'use strict';

angular.module('kifi')

.directive('contenteditable', ['$sce', function($sce) {
  return {
    restrict: 'A', // only activate on element attribute
    require: '?ngModel', // get a hold of NgModelController
    link: function(scope, element, attrs, ngModel) {
      // TODO(yiping): implement maxlength on contenteditable.
      // Note that there is a library for maxlength (and lots more);
      // investigate whether using it would be a good idea.
      // See: http://jakiestfu.github.io/Medium.js/docs/
      
      if (!ngModel) {
        return; // do nothing if no ng-model
      }

      // Write data to the model
      function read() {
        var html = element.html();
        // When we clear the content editable the browser leaves a <br> behind
        // If strip-br attribute is provided then we strip this out
        if ( attrs.stripBr && html === '<br>' ) {
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