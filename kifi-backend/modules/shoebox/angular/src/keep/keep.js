'use strict';

angular.module('kifi.keep', ['kifi.profileService'])

.controller('KeepCtrl', [
	'$scope',
	function () {}
])

.directive('kfKeep', [
	'profileService',
	function (profileService) {
		return {
			restrict: 'A',
			scope: {
				keep: '='
			},
			controller: 'KeepCtrl',
			templateUrl: 'keep/keep.tpl.html',
			link: function (scope/*, element, attrs*/) {
				scope.me = profileService.me;

				scope.isMine = function () {
					return scope.keep.isMyBookmark || false;
				};

				scope.isPrivate = function () {
					return scope.keep.isPrivate || false;
				};

				function hasExampleTag(tags) {
					if (tags && tags.length) {
						for (var i = 0, l = tags.length; i < l; i++) {
							if (scope.isExampleTag(tags[i])) {
								return true;
							}
						}
					}
					return false;
				}

				scope.isExampleTag = function (tag) {
					return (tag && tag.name && tag.name.toLowerCase()) === 'example keep';
				};

				scope.isExample = function () {
					var keep = scope.keep;
					if (keep.isExample == null) {
						keep.isExample = hasExampleTag(keep.collections);
					}
					return keep.isExample;
				};

				scope.getTitle = function () {

				};

				scope.getPicUrl = function (user) {
					return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/100/' + user.pictureName;
				};

				scope.getName = function (user) {
					return (user.firstName || '') + ' ' + (user.lastName || '');
				};

				scope.hasKeepers = function () {
					var keep = scope.keep;
					return !!(keep.keepers && keep.keepers.length);
				};

				scope.showOthers = function () {
					return !scope.hasKeepers() && !! scope.keep.others;
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
