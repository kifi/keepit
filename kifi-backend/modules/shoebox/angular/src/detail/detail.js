'use strict';

angular.module('kifi.detail', ['kifi.keepService'])

.directive('kfDetail', [
	'keepService',
	function (keepService) {
		return {
			replace: true,
			restrict: 'A',
			templateUrl: 'detail/detail.tpl.html',
			link: function (scope /*, element, attrs*/ ) {
				scope.getLength = function () {
					return keepService.getSelectedLength();
				};

				scope.showSingleKeep = function () {
					return keepService.getSelectedLength() === 1;
				};

				scope.getTitleText = function () {
					var len = keepService.getSelectedLength();
					if (len === 1) {
						return keepService.getFirstSelected().title;
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
			link: function (scope /*, element, attrs*/ ) {}
		};
	}
]);
