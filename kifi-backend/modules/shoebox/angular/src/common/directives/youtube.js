'use strict';

angular.module('kifi.youtube', [])

.directive('kfYoutube', [
	function () {
		return {
			restrict: 'A',
			scope: {
				videoId: '='
			},
			templateUrl: 'common/directives/youtube.tpl.html'
		};
	}
]);
