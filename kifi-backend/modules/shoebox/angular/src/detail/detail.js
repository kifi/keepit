'use strict';

angular.module('kifi.detail', ['kifi.keepService', 'kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.youtube'])

.directive('kfDetail', [
	'keepService', 'tagService',
	function (keepService, tagService) {
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
				scope.closeDetail = keepService.togglePreview.bind(null, null);

				tagService.fetchAll().then(function (res) {
					scope.allTags = res;
				});

				scope.$watch(scope.getPreviewed, function (keep) {
					scope.keep = keep;
				});

				scope.getPrivateConversationText = function () {
					return scope.keep.conversationCount === 1 ? 'Private Conversation' : 'Private Conversations';
				};

				scope.getTitleText = function () {
					return keepService.getSelectedLength() + ' Keeps selected';
				};

				scope.removeTag = function (keep, tag) {
					console.log(keep, tag, tag.id);
					tagService.removeKeepsFromTag(tag.id, [keep.id]);
				}
			}
		};
	}
])

.directive('kfKeepDetail', [

	function () {
		var YOUTUBE_REGEX = /https?:\/\/(?:[0-9A-Z-]+\.)?(?:youtu\.be\/|youtube\.com\S*[^\w\-\s])([\w\-]{11})(?=[^\w\-]|$)[?=&+%\w.-]*/i;

		function isYoutubeVideo(url) {
			return url.indexOf('://www.youtube.com/') > -1 || url.indexOf('youtu.be/') > -1;
		}

		function getYoutubeVideoId(url) {
			var match = url.match(YOUTUBE_REGEX);
			if (match && match.length === 2) {
				return match[1];
			}
			return null;
		}

		return {
			replace: true,
			restrict: 'A',
			scope: {
				keep: '='
			},
			templateUrl: 'detail/keepDetail.tpl.html',
			link: function (scope /*, element, attrs*/ ) {

				function testEmbed(keep) {
					if (keep) {
						var url = keep.url;
						if (isYoutubeVideo(url)) {
							var vid = getYoutubeVideoId(url);
							if (vid) {
								keep.videoId = vid;
								keep.isEmbed = true;
								return;
							}
						}
						keep.isEmbed = false;
					}
				}

				testEmbed(scope.keep);

				scope.$watch('keep', testEmbed);
			}
		};
	}
]);
