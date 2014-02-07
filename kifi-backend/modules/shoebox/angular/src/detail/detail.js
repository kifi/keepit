'use strict';

angular.module('kifi.detail', [])

.directive('kfDetail', [

	function () {
		return {
			replace: true,
			restrict: 'A',
			templateUrl: 'detail/detail.tpl.html',
			link: function (scope /*, element, attrs*/ ) {

				scope.selectedKeeps = [];

				scope.getLength = function () {
					return scope.selectedKeeps.length;
				};

				scope.showSingleKeep = function () {
					return scope.selectedKeeps.length === 1;
				};

				scope.getTitleText = function () {
					var keeps = scope.selectedKeeps,
						len = keeps.length;
					if (len === 1) {
						return keeps[0].title;
					}
					return len + ' Keeps selected';
				};
			}
		};
	}
])

.directive('kfKeepDetail', [
	function () {
		return {
			replace: true,
			restrict: 'A',
			templateUrl: 'detail/keepDetail.tpl.html',
			link: function (scope /*, element, attrs*/ ) {
			}
		};
	}
]);
