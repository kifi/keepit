'use strict';

angular.module('kifi.keep', [])

.controller('KeepCtrl', [
	'$scope',
	function () {}
])

.directive('kfKeep', [
	'$document',
	function ($document) {
		return {
			restrict: 'A',
			scope: true,
			controller: 'KeepCtrl',
			templateUrl: 'keep/keep.tpl.html',
			link: function (scope /*, element, attrs*/ ) {
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

				scope.getTags = function () {
					return scope.keep.tagList;
				};

				scope.isExample = function () {
					var keep = scope.keep;
					if (keep.isExample == null) {
						keep.isExample = hasExampleTag(scope.getTags());
					}
					return keep.isExample;
				};

				var aUrlParser = $document[0].createElement('a');
				var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
				var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
				var fileNameToSpaceRe = /[\/._-]/g;

				function formatTitleFromUrl(url, matches) {
					aUrlParser.href = url;

					var domain = aUrlParser.hostname;
					var domainIdx = url.indexOf(domain);
					var domainMatch = domain.match(secLevDomainRe);
					if (domainMatch) {
						domainIdx += domainMatch.index;
						domain = domainMatch[0];
					}

					var fileName = aUrlParser.pathname;
					var fileNameIdx = url.indexOf(fileName, domainIdx + domain.length);
					var fileNameMatch = fileName.match(fileNameRe);
					if (fileNameMatch) {
						fileNameIdx += fileNameMatch.index;
						fileName = fileNameMatch[0];
					}
					fileName = fileName.replace(fileNameToSpaceRe, ' ').trimRight();

					for (var i = matches && matches.length; i--;) {
						var match = matches[i];
						var start = match[0],
							len = match[1];
						if (start >= fileNameIdx && start < fileNameIdx + fileName.length) {
							fileName = bolded(fileName, start - fileNameIdx, len);
						}
						else if (start >= domainIdx && start < domainIdx + domain.length) {
							domain = bolded(domain, start - domainIdx, len);
						}
					}
					fileName = fileName.trimLeft();

					return domain + (fileName ? ' · ' + fileName : '');
				}

				function bolded(text, start, len) {
					return text.substr(0, start) + '<b>' + text.substr(start, len) + '</b>' + text.substr(start + len);
				}

				function toTitleHtml(keep) {
					return keep.title || formatTitleFromUrl(keep.url);
				}

				var strippedSchemeRe = /^https?:\/\//;
				var domainTrailingSlashRe = /^([^\/]*)\/$/;

				function formatDesc(url, matches) {
					var strippedSchemeLen = (url.match(strippedSchemeRe) || [''])[0].length;
					url = url.substr(strippedSchemeLen).replace(domainTrailingSlashRe, '$1');
					for (var i = matches && matches.length; i--;) {
						matches[i][0] -= strippedSchemeLen;
					}
					return boldSearchTerms(url, matches);
				}

				function boldSearchTerms(text, matches) {
					for (var i = matches && matches.length; i--;) {
						var match = matches[i];
						var start = match[0];
						if (start >= 0) {
							text = bolded(text, start, match[1]);
						}
					}
					return text;
				}

				function updateTitleHtml() {
					scope.keep.titleHtml = toTitleHtml(scope.keep);
				}

				function updateDescHtml() {
					scope.keep.descHtml = formatDesc(scope.keep.url);
				}

				updateTitleHtml();
				updateDescHtml();

				scope.$watch('keep.title', function () {
					updateTitleHtml();
				});

				scope.$watch('keep.url', function () {
					updateTitleHtml();
					updateDescHtml();
				});

				scope.getTitle = function () {
					var keep = scope.keep;
					return keep.title || keep.url;
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

				scope.isSelected = function () {
					return scope.isSelectedKeep(scope.keep);
				};

				scope.isPreviewed = function () {
					return scope.isPreviewedKeep(scope.keep);
				};

				scope.onCheck = function (e) {
					// needed to prevent previewing
					e.stopPropagation();
					return scope.toggleSelectKeep(scope.keep);
				};
			}
		};
	}
]);
