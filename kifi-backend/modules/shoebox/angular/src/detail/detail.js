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
				scope.isSingleKeep = keepService.isSingleKeep;
				scope.getLength = keepService.getSelectedLength;
				scope.isDetailOpen = keepService.isDetailOpen;
				scope.getPreviewed = keepService.getPreviewed;
				scope.getSelected = keepService.getSelected;

				scope.getTitleText = function () {
					return keepService.getSelectedLength() + ' Keeps selected';
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
