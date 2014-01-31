'use strict';

angular.module('kifi.tags', ['util', 'dom', 'kifi.tagService'])

.directive('kfTags', [
	'$timeout', '$location', 'util', 'dom', 'tagService',
	function ($timeout, $location, util, dom, tagService) {
		var KEY_UP = 38,
			KEY_DOWN = 40,
			KEY_ENTER = 13,
			KEY_ESC = 27,
			//KEY_TAB = 9,
			KEY_DEL = 46,
			KEY_F2 = 113;

		return {
			restrict: 'A',
			templateUrl: 'tags/tags.tpl.html',
			scope: {},
			link: function (scope, element /*, attrs*/ ) {
				scope.tags = tagService.list;

				scope.create = function (name) {
					if (name) {
						return tagService.create(name)
							.then(function (tag) {
								tag.isNew = true;
								scope.clearFilter();

								$timeout(function () {
									delete tag.isNew;
								}, 3000);

								return tag;
							});
					}
				};

				scope.rename = function (tag) {
					if (tag) {
						scope.renameTag = {
							value: tag.name
						};
						scope.renaming = tag;
					}
				};

				scope.remove = function (tag) {
					if (tag && tag.id) {
						return tagService.remove(tag.id);
					}
				};

				scope.clearFilter = function (focus) {
					scope.filter.name = '';
					if (focus) {
						scope.focusFilter = true;
					}
				};

				scope.isRenaming = function (tag) {
					return scope.renaming === tag;
				};

				scope.onRenameKeydown = function (e) {
					switch (e.keyCode) {
					case KEY_ENTER:
						scope.submitRename();
						break;
					case KEY_ESC:
						scope.cancelRename();
						break;
					}
				};

				scope.submitRename = function () {
					alert('rename: ' + scope.renameTag.value);
					// different scope
					if (scope.renameTag.value) {
						scope.renaming.name = scope.renameTag.value;
						scope.renaming = null;
						return;
					}
					return scope.cancelRename();
				};

				scope.cancelRename = function () {
					scope.renaming = null;
				};

				function getFilterValue() {
					return scope.filter && scope.filter.name || '';
				}

				scope.showAddTag = function () {
					var name = getFilterValue(),
						res = false;
					if (name) {
						name = name.toLowerCase();
						res = !scope.tags.some(function (tag) {
							return tag.name.toLowerCase() === name;
						});
					}
					scope.isAddTagShown = res;
					return res;
				};

				scope.isActiveTag = function (tag) {
					return util.startsWith($location.path(), '/tag/' + tag.id);
				};

				scope.getShownTags = function () {
					var child = scope.$$childHead;
					while (child) {
						if (child.shownTags) {
							return child.shownTags;
						}
						child = child.$$nextSibling;
					}
					return scope.tags || [];
				};

				function indexOfTag(tag) {
					if (tag) {
						return scope.getShownTags().indexOf(tag);
					}
					return -1;
				}

				scope.viewTag = function (tag) {
					if (tag) {
						return $location.path('/tag/' + tag.id);
					}
				};

				scope.select = function () {
					if (scope.highlight) {
						return scope.viewTag(scope.highlight);
					}
					return scope.create(getFilterValue());
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
						scope.select();
						break;
					case KEY_ESC:
						scope.dehighlight();
						break;
					case KEY_DEL:
						scope.remove(scope.highlight);
						break;
					case KEY_F2:
						scope.rename(scope.highlight);
						break;
					}
				};

				scope.refreshHighlight = function () {
					var shownTags = scope.getShownTags();
					var highlight = scope.highlight;
					if (highlight) {
						var index = shownTags.indexOf(highlight);
						if (index !== -1) {
							// might scroll
							return scope.highlightAt(index);
						}
					}

					if (getFilterValue() && shownTags.length) {
						return scope.highlightFirst();
					}

					return scope.dehighlight();
				};

				scope.isHighlight = function (tag) {
					return scope.highlight === tag;
				};

				scope.isHighlightNew = function () {
					return !scope.highlight && !! getFilterValue();
				};

				scope.dehighlight = function () {
					scope.highlight = null;
					if (scope.isAddTagShown) {
						dom.scrollIntoViewLazy(element.find('.kf-tag-new')[0]);
					}
					return null;
				};

				scope.highlightAt = function (index) {
					if (index == null) {
						return scope.dehighlight();
					}

					var tags = scope.getShownTags(),
						len = tags.length;
					if (!len) {
						return scope.dehighlight();
					}

					index = ((index % len) + len) % len;
					var tag = tags[index];
					scope.highlight = tag;
					dom.scrollIntoViewLazy(element.find('.kf-tag')[index]);
					return tag;
				};

				scope.highlightFirst = function () {
					return scope.highlightAt(0);
				};

				scope.highlightLast = function () {
					return scope.highlightAt(-1);
				};

				scope.highlightNext = function () {
					if (scope.isHighlightNew()) {
						// new tag is highlighted
						// highlight the first
						return scope.highlightFirst();
					}

					var index = indexOfTag(scope.highlight);
					if (index === -1) {
						// no highlight
						// highlight the first
						return scope.highlightFirst();
					}

					if (index === scope.getShownTags().length - 1) {
						// last item on the list

						if (scope.isAddTagShown) {
							// highlight the new tag if available
							return scope.dehighlight();
						}

						// the first, otherwise
						return scope.highlightFirst();
					}

					// highlight the next item
					return scope.highlightAt(index + 1);
				};

				scope.highlightPrev = function () {
					if (scope.isHighlightNew()) {
						// new tag is highlighted
						// highlight the last
						return scope.highlightLast();
					}

					var index = indexOfTag(scope.highlight);
					if (index === -1) {
						// no highlight
						// highlight the last
						return scope.highlightLast();
					}

					if (index === 0) {
						// first item on the list

						if (scope.isAddTagShown) {
							// highlight the new tag if available
							return scope.dehighlight();
						}

						// the last, otherwise
						return scope.highlightLast();
					}

					// highlight the prev item
					return scope.highlightAt(index - 1);
				};

				var list = element.find('.kf-tag-list');
				list.css({
					position: 'absolute',
					top: list.position().top,
					bottom: 0
				});

				scope.$watch('filter.name', function () {
					$timeout(scope.refreshHighlight);
				});
				scope.$watch('filter.name', scope.refreshScroll);
				scope.$watch('tags', scope.refreshScroll);

				tagService.fetchAll();
			}
		};
	}
]);
