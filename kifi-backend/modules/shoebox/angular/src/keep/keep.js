'use strict';

angular.module('kifi.keep', [])

.controller('KeepCtrl', [
	'$scope',
	function () {}
])

.directive('kfKeeps', [
	function () {
		return {
			restrict: 'A',
			scope: {
				keep: '='
			},
			controller: 'KeepCtrl',
			templateUrl: 'keep/keep.tpl.html',
			link: function (scope, element, attrs) {
				scope.isMine = function () {
					return scope.keep.isMyBookmark || false;
				};

				scope.isPrivate = function () {
					return scope.keep.isPrivate || false;
				};

				function hasExampleTag(tags) {
					if (tags && tags.length) {
						for (var i = 0, l = tags.length, tag; i < l; i++) {
							tag = tags[i];
							if ((tag.name && tag.name.toLowerCase()) === 'example keep') {
								return true;
							}
						}
					}
					return false;
				}

				scope.isExample = function () {
					var keep = scope.keep;
					if (keep.isExample == null) {
						keep.isExample = hasExampleTag(keep.collections);
					}
					return keep.isExample;
				};
			}
		};
	}
]);
