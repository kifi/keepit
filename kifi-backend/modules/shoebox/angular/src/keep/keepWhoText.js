'use strict';

angular.module('kifi.keepWhoText', [])

.directive('kfKeepWhoText', [

	function () {
		return {
			restrict: 'A',
			replace: true,
			templateUrl: 'keep/keepWhoText.tpl.html',
			scope: {
				keep: '='
			},
			link: function (scope) {
				scope.isPrivate = function () {
					return scope.keep.isPrivate || false;
				};

				scope.hasKeepers = function () {
					var keep = scope.keep;
					return !!(keep.keepers && keep.keepers.length);
				};

				scope.hasOthers = function () {
					var keep = scope.keep;
					return keep.others > 0;
				};

				scope.getFriendText = function () {
					var keepers = scope.keep.keepers,
						len = keepers && keepers.length || 0;
					if (keepers.length === 1) {
						return '1 friend';
					}
					return len + ' friends';
				};

				scope.getOthersText = function () {
					var others = scope.keep.others || 0;
					if (others === 1) {
						return '1 other';
					}
					return others + ' others';
				};

				scope.isOnlyMine = function () {
					return !scope.hasKeepers() && !scope.keep.others;
				};
			}
		};
	}
]);
