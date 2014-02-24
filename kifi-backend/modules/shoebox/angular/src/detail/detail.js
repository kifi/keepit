'use strict';

angular.module('kifi.detail', ['kifi.keepService', 'kifi.tagService', 'kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.youtube', 'kifi.profileService'])

.directive('kfDetail', [
	'keepService', 'tagService', '$filter', '$sce', '$document', 'profileService', '$timeout',
	function (keepService, tagService, $filter, $sce, $document, profileService, $timeout) {
		var KEY_UP = 38,
			KEY_DOWN = 40,
			KEY_ENTER = 13,
			KEY_ESC = 27,
			//KEY_TAB = 9,
			KEY_DEL = 46,
			KEY_F2 = 113;
		var dropdownSuggestionCount = 5;

		return {
			replace: true,
			restrict: 'A',
			templateUrl: 'detail/detail.tpl.html',
			link: function (scope, element/*, attrs*/ ) {
				scope.data = {};
				scope.isSingleKeep = keepService.isSingleKeep;
				scope.getLength = keepService.getSelectedLength;
				scope.isDetailOpen = keepService.isDetailOpen;
				scope.getPreviewed = keepService.getPreviewed;
				scope.getSelected = keepService.getSelected;
				scope.closeDetail = keepService.togglePreview.bind(null, null);
				scope.me = profileService.me;
				scope.data.isClickingInList = false;

				tagService.fetchAll().then(function (res) {
					scope.allTags = res;
					filterTags(null);
				});

				scope.$watch(scope.getPreviewed, function (keep) {
					scope.keep = keep;
					scope.tagFilter.name = '';
					filterTags(null);
					scope.hideAddTagDropdown();
				});

				scope.tagFilter = {name: ''};

				scope.tagTypeAheadResults = [];

				function indexOfTag(tag) {
					if (tag) {
						return scope.tagTypeAheadResults.indexOf(tag);
					}
					return -1;
				}

				function getSelectedKeeps() {
					if (scope.isSingleKeep()) {
						return [scope.getPreviewed()];
					}
					else {
						return scope.getSelected();
					}
				}

				var filterTags = function (tagFilterTerm) {
					function keepHasTag(tagId) {
						return scope.keep && scope.allTags && scope.keep.tagList && !!_.find(scope.keep.tagList, function (keepTag) {
							return keepTag.id === tagId;
						});
					}
					function allTagsExceptPreexisting() {
						return scope.allTags.filter(function (tag) {
							return !keepHasTag(tag.id);
						}).slice(0, dropdownSuggestionCount);
					}
					var splitTf = tagFilterTerm && tagFilterTerm.split(/[\W]+/);
					dropdownSuggestionCount = Math.min(10, Math.max(3, ($document.height() - element.find('.page-coll-list').offset().top) / 24 - 1));
					if (scope.allTags && tagFilterTerm) {
						var filtered = scope.allTags.filter(function (tag) {
							// for given tagFilterTerm (user search value) and a tag, returns true if
							// every part of the tagFilterTerm exists at the beginning of a part of the tag

							return !keepHasTag(tag.id) && splitTf.every(function (tfTerm) {
								return _.find(tag.name.split(/[\W]+/), function (tagTerm) {
									return tagTerm.toLowerCase().indexOf(tfTerm.toLowerCase()) === 0;
								});
							});
						});
						scope.tagTypeAheadResults = filtered.slice(0, dropdownSuggestionCount);
					} else if (scope.allTags && !tagFilterTerm) {
						scope.tagTypeAheadResults = allTagsExceptPreexisting();
					}

					if (scope.tagTypeAheadResults.length > 0) {
						scope.highlightFirst();
					}

					scope.tagTypeAheadResults.forEach(function (tag) {
						var safe = tag.name.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
						// todo: highlight matching terms
						tag.prettyHtml = $sce.trustAsHtml(safe);
					});
				}

				scope.addTag = function (tag) {
					tagService.addKeepsToTag(tag, getSelectedKeeps());
					scope.tagFilter.name = '';
					return scope.hideAddTagDropdown();
				};

				scope.createAndAddTag = function (keep) {
					tagService.create(scope.tagFilter.name).then(function (tag) {
						scope.addTag(tag, keep);
					});
				};

				scope.isTagHighlighted = function (tag) {
					return scope.highlightedTag === tag;
				};

				// check if the highlighted tag is still in the list
				scope.checkHighlight = function() {
						if (!_.find(scope.tagTypeAheadResults, function(tag) { return scope.highlightedTag === tag; })) {
							scope.highlightTag(null);
						}
				};

				var refreshTagDropdown = function(tagFilterTerm) {
						filterTags(tagFilterTerm);
						scope.checkHighlight();
				}

				scope.$watch('tagFilter.name', refreshTagDropdown);

				scope.highlightTag = function (tag) {
					return scope.highlightedTag = tag;
				};

				scope.highlightNext = function () {
					var index = indexOfTag(scope.highlightedTag);
					if (index === -1) {
						// no highlight
						// highlight the first
						return scope.highlightFirst();
					}
					if (index === scope.tagTypeAheadResults.length - 1) {
						// last item on the list
						if (scope.isAddTagShown()) {
							// highlight the new tag if available
							return scope.highlightNewSuggestion();
						}
						// the first, otherwise
						return scope.highlightFirst();
					}
					// highlight the next item
					return scope.highlightAt(index + 1);
				};

				scope.highlightPrev = function () {
					var index = indexOfTag(scope.highlightedTag);
					if (index === -1) {
						// no highlight
						// highlight the last
						return scope.highlightLast();
					}
					if (index === 0) {
						// first item on the list
						if (scope.isAddTagShown()) {
							// highlight the new tag if available
							return scope.highlightNewSuggestion();
						}
						// the last, otherwise
						return scope.highlightLast();
					}
					// highlight the previous item
					return scope.highlightAt(index - 1);
				};

				scope.isAddTagShown = function () {
					return scope.tagFilter.name.length > 0 && _.find(scope.tagTypeAheadResults, function (tag) {
						return tag.name === scope.tagFilter.name;
					}) === undefined;
				}

				scope.highlightAt = function (index) {
					if (index == null) {
						return scope.highlightNewSuggestion();
					}
					var tags = scope.tagTypeAheadResults,
						len = tags.length;
					if (!len) {
						return scope.highlightNewSuggestion();
					}

					index = ((index % len) + len) % len;

					var tag = tags[index];
					scope.highlightTag(tag);
					return tag;
				};

				scope.highlightFirst = function () {
					scope.highlightAt(0);
				};

				scope.highlightLast = function () {
					return scope.highlightAt(-1);
				};

				scope.highlightNewSuggestion = function () {
					if (scope.isAddTagShown()) {
						return scope.highlightedTag = null;
					}
					return scope.highlightFirst();
				};

				scope.selectTag = function () {
					if (scope.highlightedTag) {
						return scope.addTag(scope.highlightedTag, scope.keep);
					}
					return scope.createAndAddTag(scope.keep);
				};

				scope.hasTags = function () {
					return scope.keep && scope.keep.tagList && scope.keep.tagList.length > 0;
				};

				scope.showAddTagDropdown = function () {
					scope.tagFilter.name = '';
					filterTags(null);
					element.find('.page-coll-input').focus();
					scope.data.focusInput = true;

					return scope.isAddingTag = true;
				};

				scope.hideAddTagDropdown = function () {
					return scope.isAddingTag = false;
				};

				scope.getPrivateConversationText = function () {
					return scope.keep.conversationCount === 1 ? 'Private Conversation' : 'Private Conversations';
				};

				scope.getTitleText = function () {
					return keepService.getSelectedLength() + ' Keeps selected';
				};

				scope.onKeydown = function (e) {
					switch (e.keyCode) {
					case KEY_UP:
						scope.highlightPrev();
						break;
					case KEY_DOWN:
						scope.highlightNext();
						break;
					case KEY_ENTER:
						scope.selectTag();
						break;
					case KEY_ESC:
						scope.hideAddTagDropdown();
						break;
					case KEY_DEL:
						scope.hideAddTagDropdown();
						break;
					case KEY_F2:
						// scope.rename(scope.highlight);
						break;
					}
				};

				scope.howKept = null;

				scope.$watch(function () {
					if (scope.isSingleKeep()) {
						if (scope.keep) {
							return scope.keep.isPrivate ? 'private' : 'public';
						}
						return null;
					}

					var selected = scope.getSelected();
					if (_.every(selected, 'isMyBookmark')) {
						return _.every(selected, 'isPrivate') ? 'private' : 'public';
					}
					return null;
				}, function (howKept) {
					scope.howKept = howKept;
				});

				scope.isPrivate = function () {
					return scope.howKept === 'private';
				};

				scope.isPublic = function () {
					return scope.howKept === 'public';
				};

				scope.removeTag = function (keep, tag) {
					tagService.removeKeepsFromTag(tag.id, [keep.id]);
				};

				scope.toggleKeep = function () {
					var keeps = getSelectedKeeps();
					return keepService.toggleKeep(keeps);
				};

				scope.togglePrivate = function () {
					var keeps = getSelectedKeeps();
					return keepService.togglePrivate(keeps);
				};

				element.on('mousedown', '.page-coll-opt', function () {
					scope.data.isClickingInList = true;
				}).on('mouseup', '.page-coll-opt', function () {
					scope.data.isClickingInList = false;
				});

				scope.blurTagFilter = function () {
					if (!scope.data.isClickingInList) {
						scope.hideAddTagDropdown();
					}
				};

				scope.highlightTag(null);
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
