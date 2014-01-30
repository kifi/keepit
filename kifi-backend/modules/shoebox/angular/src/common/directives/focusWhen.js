'use strict';

angular.module('focusWhen', [])

.directive('focusWhen', [
	'$timeout',
	function ($timeout) {
		return {
			restrict: 'A',
			scope: {
				focusWhen: '='
			},
			link: function (scope, element /*, attrs*/ ) {

				function focus() {
					element.focus();
					scope.focusWhen = false;
				}

				scope.$watch('focusWhen', function (val) {
					if (val) {
						$timeout(focus);
					}
				});
			}
		};
	}
]);
