'use strict';

angular.module('kifi.layout.nav', [])

.directive('kfNav', [
	'$location',
	function ($location) {
		return {
			//replace: true,
			restrict: 'A',
			templateUrl: 'layout/nav/nav.tpl.html',
			link: function (scope /*, element, attrs*/ ) {
				scope.counts = {
					keepCount: 483,
					friendsNotiConut: 18
				};

				scope.isActive = function (path) {
					var loc = $location.path();
					return loc === path || loc.lastIndexOf(path + '/', 0) === 0;
				};
			}
		};
	}
]);
