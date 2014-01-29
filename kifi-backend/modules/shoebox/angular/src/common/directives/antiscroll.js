'use strict';

angular.module('antiscroll', [])

.directive('antiscroll', function () {
	return {
		restrict: 'A',
		transclude: true,
		link: function (scope, element, attrs) {
			var options;
			if (attrs.antiscroll) {
				options = scope.$eval(attrs.antiscroll);
			}
			scope.scroller = element.antiscroll(options).data('antiscroll');
		},
		template: '<div class="antiscroll-inner" ng-transclude></div>'
	};
});
