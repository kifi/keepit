'use strict';

angular.module('antiscroll', [])

.directive('antiscroll', function () {
	return {
		restrict: 'A',
		scope: {},
		transclude: true,
		compile: function (element, attrs /*, transclude*/ ) {
			return {
				post: function (scope) {
					var options;
					if (attrs.antiscroll) {
						options = scope.$eval(attrs.antiscroll);
					}
					console.log('antiscroll', options);

					scope.scroller = element.antiscroll(options).data('antiscroll');
				}
			};
		},
		template: '<div class="antiscroll-inner" ng-transclude></div>'
	};
});
