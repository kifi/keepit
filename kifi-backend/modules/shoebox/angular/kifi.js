'use strict';

angular.module('kifi', [
  'ngCookies',
  'ngResource',
  'ngRoute',
  'ngSanitize',
  'ngAnimate',
  'ui.bootstrap',
  //'ui.router',
  'util',
  'dom',
  'antiscroll',
  'jun.smartScroll',
  'angularMoment',
  'kifi.home',
  'kifi.search',
  'kifi.profile',
  'kifi.focus',
  'kifi.youtube',
  'kifi.templates',
  'kifi.profileCard',
  'kifi.profileService',
  'kifi.detail',
  'kifi.tags',
  'kifi.keeps',
  'kifi.keep',
  'kifi.layout.leftCol',
  'kifi.layout.main',
  'kifi.layout.nav',
  'kifi.layout.rightCol'
])

.run(['$route', angular.noop])

.config([
  '$routeProvider', '$locationProvider', '$httpProvider',
  function ($routeProvider, $locationProvider, $httpProvider) {
    $locationProvider
      .html5Mode(true)
      .hashPrefix('!');

    $routeProvider.otherwise({
      redirectTo: '/'
    });

    $httpProvider.defaults.withCredentials = true;
  }
])

.factory('env', [
  '$location',
  function ($location) {
    var host = $location.host(),
      dev = /^dev\.ezkeep\.com|localhost$/.test(host),
      local = $location.port() === '9000',
      origin = local ? $location.protocol() + '//' + host : 'https://www.kifi.com';

    return {
      local: local,
      dev: dev,
      production: !dev,
      xhrBase: origin + '/site',
      xhrBaseEliza: origin.replace('www', 'eliza') + '/eliza/site',
      xhrBaseSearch: origin.replace('www', 'search') + '/search',
      picBase: (local ? '//d1scct5mnc9d9m' : '//djty7jcqog9qu') + '.cloudfront.net'
    };
  }
])

.controller('AppCtrl', [

  function () {}
]);

'use strict';

angular.module('antiscroll', [])

.directive('antiscroll', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      transclude: true,
      link: function (scope, element, attrs /*, ctrl, transclude*/ ) {
        var options;
        if (attrs.antiscroll) {
          options = scope.$eval(attrs.antiscroll);
        }
        scope.scroller = element.antiscroll(options).data('antiscroll');

        scope.refreshScroll = function () {
          return $timeout(function () {
            if (scope.scroller) {
              scope.scroller.refresh();
            }
          });
        };

        scope.refreshScroll();

        scope.$on('refreshScroll', scope.refreshScroll);
      },
      template: '<div class="antiscroll-inner" ng-transclude></div>'
    };
  }
]);

'use strict';

angular.module('kifi.focus', [])

.directive('focusWhen', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      scope: {
        focusWhen: '='
      },
      link: function (scope, element /*, attrs*/ ) {

        function focus() {
          element.focus();
          scope.focusWhen = false;
        }

        scope.$watch('focusWhen', function (val) {
          if (val) {
            $timeout(focus);
          }
        });
      }
    };
  }
])

.directive('withFocus', [
  function () {
    return {
      restrict: 'A',
      scope: {
        withFocus: '&'
      },
      link: function (scope, element /*, attrs*/ ) {
        if (scope.withFocus()) {
          element.focus();
        }
      }
    };
  }
]);

'use strict';

angular.module('kifi.youtube', [])

.directive('kfYoutube', [

  function () {

    function videoIdToSrc(videoId) {
      return '//www.youtube.com/v/' + videoId +
        '&rel=0&theme=light&showinfo=0&disablekb=1&modestbranding=1&controls=0&hd=1&autohide=1&color=white&iv_load_policy=3';
    }

    function videoEmbed(src) {
      return '<embed src="' + src +
        '" type="application/x-shockwave-flash" allowfullscreen="true" style="width:100%; height: 100%;" allowscriptaccess="always"></embed>';
    }

    return {
      restrict: 'A',
      replace: true,
      scope: {
        videoId: '='
      },
      templateUrl: 'common/directives/youtube.tpl.html',
      link: function (scope, element) {

        var lastId = null;

        function updateSrc(videoId) {
          if (lastId === videoId) {
            return;
          }
          lastId = videoId;

          if (videoId) {
            element.html(videoEmbed(videoIdToSrc(videoId)));
          }
        }

        updateSrc(scope.videoId);

        scope.$watch('videoId', updateSrc);
      }
    };
  }
]);

'use strict';

angular.module('dom', [])

.value('dom', {
  scrollIntoViewLazy: function (el, padding) {
    var view;
    if (!(el && (view = el.offsetParent))) {
      return;
    }

    var viewTop = view.scrollTop,
      viewHeight = view.clientHeight,
      viewBottom = viewTop + viewHeight,
      elemTop = el.offsetTop,
      elemBottom = elemTop + el.offsetHeight;

    if (elemBottom > viewBottom) {
      view.scrollTop = elemBottom + (padding || 0) - viewHeight;
    }
    else if (elemTop < viewTop) {
      view.scrollTop = elemTop - (padding || 0);
    }
  }
});

/*
 * angular-ui-bootstrap
 * http://angular-ui.github.io/bootstrap/

 * Version: 0.10.0 - 2014-01-14
 * License: MIT
 */
angular.module("ui.bootstrap", ["ui.bootstrap.tpls", "ui.bootstrap.dropdownToggle"]);
angular.module("ui.bootstrap.tpls", []);
/*
 * dropdownToggle - Provides dropdown menu functionality in place of bootstrap js
 * @restrict class or attribute
 * @example:
   <li class="dropdown">
     <a class="dropdown-toggle">My Dropdown Menu</a>
     <ul class="dropdown-menu">
       <li ng-repeat="choice in dropChoices">
         <a ng-href="{{choice.href}}">{{choice.text}}</a>
       </li>
     </ul>
   </li>
 */

angular.module('ui.bootstrap.dropdownToggle', []).directive('dropdownToggle', ['$document', '$location', function ($document, $location) {
  var openElement = null,
      closeMenu   = angular.noop;
  return {
    restrict: 'CA',
    link: function(scope, element, attrs) {
      scope.$watch('$location.path', function() { closeMenu(); });
      element.parent().bind('click', function() { closeMenu(); });
      element.bind('click', function (event) {

        var elementWasOpen = (element === openElement);

        event.preventDefault();
        event.stopPropagation();

        if (!!openElement) {
          closeMenu();
        }

        if (!elementWasOpen && !element.hasClass('disabled') && !element.prop('disabled')) {
          element.parent().addClass('open');
          openElement = element;
          closeMenu = function (event) {
            if (event) {
              event.preventDefault();
              event.stopPropagation();
            }
            $document.unbind('click', closeMenu);
            element.parent().removeClass('open');
            closeMenu = angular.noop;
            openElement = null;
          };
          $document.bind('click', closeMenu);
        }
      });
    }
  };
}]);

/*
 * angular-ui-bootstrap
 * http://angular-ui.github.io/bootstrap/

 * Version: 0.10.0 - 2014-01-14
 * License: MIT
 */
angular.module("ui.bootstrap",["ui.bootstrap.tpls","ui.bootstrap.dropdownToggle"]),angular.module("ui.bootstrap.tpls",[]),angular.module("ui.bootstrap.dropdownToggle",[]).directive("dropdownToggle",["$document","$location",function(a){var b=null,c=angular.noop;return{restrict:"CA",link:function(d,e){d.$watch("$location.path",function(){c()}),e.parent().bind("click",function(){c()}),e.bind("click",function(d){var f=e===b;d.preventDefault(),d.stopPropagation(),b&&c(),f||e.hasClass("disabled")||e.prop("disabled")||(e.parent().addClass("open"),b=e,c=function(d){d&&(d.preventDefault(),d.stopPropagation()),a.unbind("click",c),e.parent().removeClass("open"),c=angular.noop,b=null},a.bind("click",c))})}}}]);
'use strict';

angular.module('kifi.detail',
	['kifi.keepService', 'kifi.tagService', 'kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.youtube', 'kifi.profileService', 'kifi.focus']
)

.directive('kfDetail', [
  'keepService', '$filter', '$sce', '$document', 'profileService',
  function (keepService, $filter, $sce, $document, profileService) {

    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'detail/detail.tpl.html',
      link: function (scope, element/*, attrs*/ ) {
        scope.isSingleKeep = keepService.isSingleKeep;
        scope.getLength = keepService.getSelectedLength;
        scope.isDetailOpen = keepService.isDetailOpen;
        scope.getPreviewed = keepService.getPreviewed;
        scope.getSelected = keepService.getSelected;
        scope.closeDetail = keepService.togglePreview.bind(null, null);
        scope.me = profileService.me;

        scope.$watch(scope.getPreviewed, function (keep) {
          scope.keep = keep;
        });

        scope.getSelectedKeeps = function () {
          if (scope.isSingleKeep()) {
            return [scope.getPreviewed()];
          }
          else {
            return scope.getSelected();
          }
        };

        scope.getPrivateConversationText = function () {
          return scope.keep.conversationCount === 1 ? 'Private Conversation' : 'Private Conversations';
        };

        scope.getTitleText = function () {
          return keepService.getSelectedLength() + ' Keeps selected';
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

        scope.togglePrivate = function () {
          var keeps = scope.getSelectedKeeps();
          return keepService.togglePrivate(keeps);
        };

      }
    };
  }
])

.directive('kfTagList', [
  'keepService', 'tagService', '$filter', '$sce', '$document',
  function (keepService, tagService, $filter, $sce, $document) {
    var KEY_UP = 38,
      KEY_DOWN = 40,
      KEY_ENTER = 13,
      KEY_ESC = 27,
      KEY_DEL = 46,
      KEY_F2 = 113;
    var dropdownSuggestionCount = 5;

    return {
      scope: {
        'keep': '=',
        'getSelectedKeeps': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'detail/tagList.tpl.html',
      link: function (scope, element/*, attrs*/ ) {
        scope.data = {};
        scope.data.isClickingInList = false;
        scope.newTagLabel = 'NEW';
        scope.tagFilter = { name: '' };
        scope.tagTypeAheadResults = [];
        scope.shouldGiveFocus = false;

        tagService.fetchAll().then(function (res) {
          scope.allTags = res;
          filterTags(null);
        });

        scope.$watch('keep', function (keep) {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.hideAddTagDropdown();
        });

        scope.getCommonTags = function () {
          var tagLists = _.pluck(scope.getSelectedKeeps(), 'tagList');
          var tagIds = _.map(tagLists, function (tagList) { return _.pluck(tagList, 'id'); });
          var commonTagIds = _.intersection.apply(this, tagIds);
          var tagMap = _.indexBy(_.flatten(tagLists, true), 'id');
          return _.map(commonTagIds, function (tagId) { return tagMap[tagId]; });
        };

        scope.$watch(function () {
          return _.pluck(scope.getSelectedKeeps(), 'tagList');
        }, function () {
          scope.commonTags = scope.getCommonTags();
        }, true);

        function indexOfTag(tag) {
          if (tag) {
            return scope.tagTypeAheadResults.indexOf(tag);
          }
          return -1;
        }

        function filterTags(tagFilterTerm) {
          function keepHasTag(tagId) {
            return scope.keep && scope.allTags && scope.commonTags && !!_.find(scope.commonTags, function (keepTag) {
              return keepTag.id === tagId;
            });
          }
          function allTagsExceptPreexisting() {
            return scope.allTags.filter(function (tag) {
              return !keepHasTag(tag.id);
            }).slice(0, dropdownSuggestionCount);
          }
          function generateDropdownSuggestionCount() {
            var elem = element.find('.page-coll-list');
            if (elem && elem.offset().top) {
              return Math.min(10, Math.max(3, ($document.height() - elem.offset().top) / 24 - 1));
            }
            return dropdownSuggestionCount;
          }
          var splitTf = tagFilterTerm && tagFilterTerm.split(/[\W]+/);
          dropdownSuggestionCount = generateDropdownSuggestionCount();
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
            var safe = tag.name.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            // todo: highlight matching terms
            tag.prettyHtml = $sce.trustAsHtml(safe);
          });
        }

        scope.addTag = function (tag) {
          tagService.addKeepsToTag(tag, scope.getSelectedKeeps());
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
        scope.checkHighlight = function () {
          if (!_.find(scope.tagTypeAheadResults, function (tag) { return scope.highlightedTag === tag; })) {
            scope.highlightTag(null);
          }
        };

        scope.$watch('tagFilter.name', function (tagFilterTerm) {
          filterTags(tagFilterTerm);
          scope.checkHighlight();
        });

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
        };

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
          return scope.commonTags && scope.commonTags.length > 0;
        };

        scope.showAddTagDropdown = function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.shouldGiveFocus = true;

          return scope.isAddingTag = true;
        };

        scope.hideAddTagDropdown = function () {
          return scope.isAddingTag = false;
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

        scope.removeTagFromSelectedKeeps = function (tag) {
          tagService.removeKeepsFromTag(tag.id, _.pluck(scope.getSelectedKeeps(), 'id'));
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

        scope.addTagLabel = function () {
          if (scope.getSelectedKeeps().length === 1) {
            return 'Add a tag to this keep';
          } else {
            return 'Add a tag to these keeps';
          }
        };

        scope.highlightTag(null);
      }
    };
  }
])

.directive('kfTagSuggestions', [
  '$timeout',
  function ($timeout) {
    return function (scope, element/*, attrs*/) {
      $timeout(function () {
        var hiddenElement = element.find('.page-coll-opt-hidden');
        var input = element.find('input');
        scope.$watch('tagFilter.name', function (value) {
          var html = value;
          if (scope.isAddTagShown()) {
            html += scope.newTagLabel;
          }
          hiddenElement.html(html);
          var parentWidth = element.parents('.page-coll-list')[0].offsetWidth - 20; // a padding offset
          var width = hiddenElement[0].offsetWidth + 10;
          if (width > parentWidth) {
            width = parentWidth;
          }
          input.css('width', width + 'px');
        });
      });
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
            var url = keep && keep.url;
            if (url && isYoutubeVideo(url)) {
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

'use strict';

angular.module('kifi.me', [])

.service('me', function () {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'profileCard/profileCard.tpl.html',
    link: function (scope /*, element, attrs*/ ) {
      scope.firstName = 'Joon Ho';
      scope.lastName = 'Cho';
      scope.description = 'Porting to Angular.js';
    }
  };
});

'use strict';

angular.module('util', [])

.value('util', {
  startsWith: function (str, prefix) {
    return str === prefix || str.lastIndexOf(prefix, 0) === 0;
  },
  endsWith: function (str, suffix) {
    return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
  }
})

.directive('postRepeatDirective', [
  '$timeout', '$window',
  function ($timeout, $window) {
    return function (scope) {
      if (scope.$first) {
        if ($window.console && $window.console.time) {
          $window.console.time('postRepeatDirective');
        }
      }

      if (scope.$last) {
        $timeout(function () {
          if ($window.console && $window.console.time) {
            $window.console.time('postRepeatDirective');
            $window.console.timeEnd('postRepeatDirective');
          }
        });
      }
    };
  }
]);

'use strict';

angular.module('kifi.detail',
	['kifi.keepService', 'kifi.tagService', 'kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.youtube', 'kifi.profileService', 'kifi.focus']
)

.directive('kfDetail', [
  'keepService', '$filter', '$sce', '$document', 'profileService',
  function (keepService, $filter, $sce, $document, profileService) {

    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'detail/detail.tpl.html',
      link: function (scope, element/*, attrs*/ ) {
        scope.isSingleKeep = keepService.isSingleKeep;
        scope.getLength = keepService.getSelectedLength;
        scope.isDetailOpen = keepService.isDetailOpen;
        scope.getPreviewed = keepService.getPreviewed;
        scope.getSelected = keepService.getSelected;
        scope.closeDetail = keepService.togglePreview.bind(null, null);
        scope.me = profileService.me;

        scope.$watch(scope.getPreviewed, function (keep) {
          scope.keep = keep;
        });

        scope.getSelectedKeeps = function () {
          if (scope.isSingleKeep()) {
            return [scope.getPreviewed()];
          }
          else {
            return scope.getSelected();
          }
        };

        scope.getPrivateConversationText = function () {
          return scope.keep.conversationCount === 1 ? 'Private Conversation' : 'Private Conversations';
        };

        scope.getTitleText = function () {
          return keepService.getSelectedLength() + ' Keeps selected';
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

        scope.togglePrivate = function () {
          var keeps = scope.getSelectedKeeps();
          return keepService.togglePrivate(keeps);
        };

      }
    };
  }
])

.directive('kfTagList', [
  'keepService', 'tagService', '$filter', '$sce', '$document',
  function (keepService, tagService, $filter, $sce, $document) {
    var KEY_UP = 38,
      KEY_DOWN = 40,
      KEY_ENTER = 13,
      KEY_ESC = 27,
      KEY_DEL = 46,
      KEY_F2 = 113;
    var dropdownSuggestionCount = 5;

    return {
      scope: {
        'keep': '=',
        'getSelectedKeeps': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'detail/tagList.tpl.html',
      link: function (scope, element/*, attrs*/ ) {
        scope.data = {};
        scope.data.isClickingInList = false;
        scope.newTagLabel = 'NEW';
        scope.tagFilter = { name: '' };
        scope.tagTypeAheadResults = [];
        scope.shouldGiveFocus = false;

        tagService.fetchAll().then(function (res) {
          scope.allTags = res;
          filterTags(null);
        });

        scope.$watch('keep', function (keep) {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.hideAddTagDropdown();
        });

        scope.getCommonTags = function () {
          var tagLists = _.pluck(scope.getSelectedKeeps(), 'tagList');
          var tagIds = _.map(tagLists, function (tagList) { return _.pluck(tagList, 'id'); });
          var commonTagIds = _.intersection.apply(this, tagIds);
          var tagMap = _.indexBy(_.flatten(tagLists, true), 'id');
          return _.map(commonTagIds, function (tagId) { return tagMap[tagId]; });
        };

        scope.$watch(function () {
          return _.pluck(scope.getSelectedKeeps(), 'tagList');
        }, function () {
          scope.commonTags = scope.getCommonTags();
        }, true);

        function indexOfTag(tag) {
          if (tag) {
            return scope.tagTypeAheadResults.indexOf(tag);
          }
          return -1;
        }

        function filterTags(tagFilterTerm) {
          function keepHasTag(tagId) {
            return scope.keep && scope.allTags && scope.commonTags && !!_.find(scope.commonTags, function (keepTag) {
              return keepTag.id === tagId;
            });
          }
          function allTagsExceptPreexisting() {
            return scope.allTags.filter(function (tag) {
              return !keepHasTag(tag.id);
            }).slice(0, dropdownSuggestionCount);
          }
          function generateDropdownSuggestionCount() {
            var elem = element.find('.page-coll-list');
            if (elem && elem.offset().top) {
              return Math.min(10, Math.max(3, ($document.height() - elem.offset().top) / 24 - 1));
            }
            return dropdownSuggestionCount;
          }
          var splitTf = tagFilterTerm && tagFilterTerm.split(/[\W]+/);
          dropdownSuggestionCount = generateDropdownSuggestionCount();
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
            var safe = tag.name.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            // todo: highlight matching terms
            tag.prettyHtml = $sce.trustAsHtml(safe);
          });
        }

        scope.addTag = function (tag) {
          tagService.addKeepsToTag(tag, scope.getSelectedKeeps());
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
        scope.checkHighlight = function () {
          if (!_.find(scope.tagTypeAheadResults, function (tag) { return scope.highlightedTag === tag; })) {
            scope.highlightTag(null);
          }
        };

        scope.$watch('tagFilter.name', function (tagFilterTerm) {
          filterTags(tagFilterTerm);
          scope.checkHighlight();
        });

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
        };

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
          return scope.commonTags && scope.commonTags.length > 0;
        };

        scope.showAddTagDropdown = function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.shouldGiveFocus = true;

          return scope.isAddingTag = true;
        };

        scope.hideAddTagDropdown = function () {
          return scope.isAddingTag = false;
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

        scope.removeTagFromSelectedKeeps = function (tag) {
          tagService.removeKeepsFromTag(tag.id, _.pluck(scope.getSelectedKeeps(), 'id'));
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

        scope.addTagLabel = function () {
          if (scope.getSelectedKeeps().length === 1) {
            return 'Add a tag to this keep';
          } else {
            return 'Add a tag to these keeps';
          }
        };

        scope.highlightTag(null);
      }
    };
  }
])

.directive('kfTagSuggestions', [
  '$timeout',
  function ($timeout) {
    return function (scope, element/*, attrs*/) {
      $timeout(function () {
        var hiddenElement = element.find('.page-coll-opt-hidden');
        var input = element.find('input');
        scope.$watch('tagFilter.name', function (value) {
          var html = value;
          if (scope.isAddTagShown()) {
            html += scope.newTagLabel;
          }
          hiddenElement.html(html);
          var parentWidth = element.parents('.page-coll-list')[0].offsetWidth - 20; // a padding offset
          var width = hiddenElement[0].offsetWidth + 10;
          if (width > parentWidth) {
            width = parentWidth;
          }
          input.css('width', width + 'px');
        });
      });
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
            var url = keep && keep.url;
            if (url && isYoutubeVideo(url)) {
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

'use strict';

angular.module('kifi.home', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/', {
      templateUrl: 'home/home.tpl.html',
      controller: 'HomeCtrl'
    });
  }
])

.controller('HomeCtrl', [
  '$scope', 'tagService', 'keepService', '$q',
  function ($scope, tagService, keepService, $q) {
    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;
    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    $scope.loadingKeeps = true;

    keepService.getList().then(function () {
      $scope.loadingKeeps = false;
    });

    $scope.checkEnabled = true;

    $scope.mouseoverCheckAll = false;

    $scope.onMouseoverCheckAll = function () {
      $scope.mouseoverCheckAll = true;
    };

    $scope.onMouseoutCheckAll = function () {
      $scope.mouseoverCheckAll = false;
    };

    $scope.getSubtitle = function () {
      if ($scope.loadingKeeps) {
        return 'Loading...';
      }

      var selectedCount = keepService.getSelectedLength(),
        numShown = $scope.keeps && $scope.keeps.length || 0;

      if ($scope.mouseoverCheckAll) {
        if (selectedCount === numShown) {
          return 'Deselect all ' + numShown + ' Keeps below';
        }
        return 'Select all ' + numShown + ' Keeps below';
      }

      switch (selectedCount) {
      case 0:
        break;
      case 1:
        return selectedCount + ' Keep selected';
      default:
        return selectedCount + ' Keeps selected';
      }

      switch (numShown) {
      case 0:
        return 'You have no Keeps';
      case 1:
        return 'Showing your only Keep';
      case 2:
        return 'Showing both of your Keeps';
      default:
        /*
        if (numShown === $scope.results.numTotal) {
          return 'Showing all ' + numShown + ' of your Keeps';
        }
        */
        return 'Showing your ' + numShown + ' latest Keeps';
      }
    };

    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loadingKeeps) {
        return $q.when([]);
      }

      $scope.loadingKeeps = true;

      return keepService.getList().then(function (list) {
        $scope.loadingKeeps = false;
        return list;
      });
    };
  }
]);

'use strict';

angular.module('kifi.keep', ['kifi.keepWhoPics', 'kifi.keepWhoText'])

.controller('KeepCtrl', [
  '$scope',
  function ($scope) {
    $scope.isMyBookmark = function (keep) {
      return keep.isMyBookmark || false;
    };

    $scope.isPrivate = function (keep) {
      return keep.isPrivate || false;
    };

    $scope.isExampleTag = function (tag) {
      return (tag && tag.name && tag.name.toLowerCase()) === 'example keep';
    };

    function hasExampleTag(tags) {
      if (tags && tags.length) {
        for (var i = 0, l = tags.length; i < l; i++) {
          if ($scope.isExampleTag(tags[i])) {
            return true;
          }
        }
      }
      return false;
    }

    $scope.isExample = function (keep) {
      if (keep.isExample == null) {
        keep.isExample = hasExampleTag($scope.getTags());
      }
      return keep.isExample;
    };
  }
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
        scope.getTags = function () {
          return scope.keep.tagList;
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

        scope.onCheck = function (e) {
          // needed to prevent previewing
          e.stopPropagation();
          return scope.toggleSelect(scope.keep);
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.keepWhoPics', [])

.directive('kfKeepWhoPics', [

  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'keep/keepWhoPics.tpl.html',
      scope: {
        me: '=',
        keepers: '='
      },
      link: function (scope) {
        scope.getPicUrl = function (user) {
          if (user) {
            return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/100/' + user.pictureName;
          }
          return '';
        };
      }
    };
  }
]);

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
            len = keepers && keepers.length || 0,
            text;
          if (len === 1) {
            text = '1 friend';
          }
          text = len + ' friends';
          // todo: if is mine, return '+ ' + text. else, text.
          return '+ ' + text;
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

'use strict';

angular.module('kifi.keepService', [])

.factory('keepService', [
  '$http', 'env', '$q', '$timeout', '$document', '$rootScope',
  function ($http, env, $q, $timeout, $document, $rootScope) {

    var list = [],
      selected = {},
      before = null,
      end = false,
      previewed = null,
      limit = 30,
      isDetailOpen = false,
      singleKeepBeingPreviewed = false,
      previewUrls = {},
      doc = $document[0];

    $rootScope.$on('tags.remove', function (tagId) {
      _.forEach(list, function (keep) {
        if (keep.tagList) {
          keep.tagList = keep.tagList.filter(function (tag) {
            return tag.id !== tagId;
          });
        }
      });
    });

    $rootScope.$on('tags.removeFromKeep', function (e, data) {
      var tagId = data.tagId,
          keepId = data.keepId;
      _.forEach(list, function (keep) {
        if (keep.id === keepId && keep.tagList) {
          keep.tagList = keep.tagList.filter(function (tag) {
            return tag.id !== tagId;
          });
        }
      });
    });

    $rootScope.$on('tags.addToKeep', function (e, data) {
      var tag = data.tag,
          keepId = data.keep.id;
      _.forEach(list, function (keep) {
        if (keep.id === keepId && keep.tagList) {
          var isAlreadyThere = _.find(keep.tagList, function (existingTag) {
            return existingTag.id === tag.id;
          });
          if (!isAlreadyThere) {
            keep.tagList.push(tag);
          }
        }
      });
    });

    var api = {
      list: list,

      totalKeepCount: 0,

      isDetailOpen: function () {
        return isDetailOpen;
      },

      isSingleKeep: function () {
        return singleKeepBeingPreviewed;
      },

      getPreviewed: function () {
        return previewed || null;
      },

      isPreviewed: function (keep) {
        return !!previewed && previewed === keep;
      },

      preview: function (keep) {
        if (keep == null) {
          singleKeepBeingPreviewed = false;
          isDetailOpen = false;
        }
        else {
          singleKeepBeingPreviewed = true;
          isDetailOpen = true;
        }
        previewed = keep;
        api.getChatter(previewed);

        return keep;
      },

      togglePreview: function (keep) {
        if (api.isPreviewed(keep) && _.size(selected) > 1) {
          previewed = null;
          isDetailOpen = true;
          singleKeepBeingPreviewed = false;
          return null;
        }
        else if (api.isPreviewed(keep)) {
          return api.preview(null);
        }
        return api.preview(keep);
      },

      isSelected: function (keep) {
        return keep && keep.id && !!selected[keep.id];
      },

      select: function (keep) {
        var id = keep.id;
        if (id) {
          isDetailOpen = true;
          selected[id] = keep;
          if (_.size(selected) === 1) {
            api.preview(keep);
          }
          else {
            previewed = null;
            singleKeepBeingPreviewed = false;
          }
          return true;
        }
        return false;
      },

      unselect: function (keep) {
        var id = keep.id;
        if (id) {
          delete selected[id];
          var countSelected = _.size(selected);
          if (countSelected === 0 && isDetailOpen === true) {
            api.preview(keep);
          }
          else if (countSelected === 1 && isDetailOpen === true) {
            api.preview(api.getFirstSelected());
          }
          else {
            previewed = null;
            singleKeepBeingPreviewed = false;
          }
          return true;
        }
        return false;
      },

      toggleSelect: function (keep) {
        if (api.isSelected(keep)) {
          return api.unselect(keep);
        }
        return api.select(keep);
      },

      getFirstSelected: function () {
        var id = _.keys(selected)[0];
        if (!id) {
          return null;
        }

        for (var i = 0, l = list.length, keep; i < l; i++) {
          keep = list[i];
          if (keep.id === id) {
            return keep;
          }
        }

        return null;
      },

      getSelectedLength: function () {
        return _.keys(selected).length;
      },

      getSelected: function () {
        return list.filter(function (keep) {
          return keep.id in selected;
        });
      },

      selectAll: function () {
        selected = _.reduce(list, function (map, keep) {
          map[keep.id] = true;
          return map;
        }, {});
        if (list.length === 0) {
          api.clearState();
        }
        else if (list.length === 1) {
          api.preview(list[0]);
        }
        else {
          previewed = null;
          isDetailOpen = true;
          singleKeepBeingPreviewed = false;
        }
      },

      unselectAll: function () {
        selected = {};
        api.clearState();
      },

      clearState: function () {
        previewed = null;
        isDetailOpen = false;
        singleKeepBeingPreviewed = false;
      },

      isSelectedAll: function () {
        return list.length && list.length === api.getSelectedLength();
      },

      toggleSelectAll: function () {
        if (api.isSelectedAll()) {
          return api.unselectAll();
        }
        return api.selectAll();
      },

      resetList: function () {
        before = null;
        list.length = 0;
      },

      getList: function (params) {
        var url = env.xhrBase + '/keeps/all';
        params = params || {};
        params.count = params.count || limit;
        params.before = before || void 0;

        var config = {
          params: params
        };

        if (end) {
          return $q.when([]);
        }

        return $http.get(url, config).then(function (res) {
          var data = res.data,
            keeps = data.keeps || [];
          if (!keeps.length) {
            end = true;
          }

          if (!data.before) {
            list.length = 0;
          }

          list.push.apply(list, keeps);
          before = list.length ? list[list.length - 1].id : null;

          _.forEach(keeps, function (keep) {
            keep.isMyBookmark = true;
          });

          return keeps;
        }).then(function (list) {
          api.fetchScreenshotUrls(list).then(function (urls) {
            $timeout(function () {
              api.prefetchImages(urls);
            });
            _.forEach(list, function (keep) {
              keep.screenshot = urls[keep.url];
            });
          });
          return list;
        });
      },

      joinTags: function (keeps, tags) {
        var idMap = _.reduce(tags, function (map, tag) {
          if (tag && tag.id) {
            map[tag.id] = tag;
          }
          return map;
        }, {});

        _.forEach(keeps, function (keep) {
          keep.tagList = _.map(keep.collections || keep.tags, function (tagId) {
            return idMap[tagId] || null;
          }).filter(function (tag) {
            return tag != null;
          });
        });
      },

      getChatter: function (keep) {
        if (keep && keep.url) {
          var url = env.xhrBaseEliza + '/chatter';

          var data = {
            url: keep.url
          };

          return $http.post(url, data).then(function (res) {
            var data = res.data;
            keep.conversationCount = data.threads;
            return data;
          });
        }
        return $q.when({'threads': 0});
      },

      fetchScreenshotUrls: function (keeps) {
        if (keeps && keeps.length) {
          var url = env.xhrBase + '/keeps/screenshot';
          return $http.post(url, {
            urls: _.pluck(keeps, 'url')
          }).then(function (res) {
            return res.data.urls;
          });
        }
        return $q.when(keeps || []);
      },

      prefetchImages: function (urls) {
        _.forEach(urls, function (imgUrl, key) {
          if (!(key in previewUrls)) {
            previewUrls[key] = imgUrl;
            doc.createElement('img').src = imgUrl;
          }
        });
      },

      keep: function (keeps, isPrivate) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        isPrivate = !! isPrivate;

        var url = env.xhrBase + '/keeps/add';
        return $http.post(url, {
          keeps: keeps.map(function (keep) {
            return {
              title: keep.title,
              url: keep.url,
              isPrivate: isPrivate
            };
          })
        }).then(function () {
          _.forEach(keeps, function (keep) {
            keep.isMyBookmark = true;
            keep.isPrivate = isPrivate;
          });
          return keeps;
        });
      },

      unkeep: function (keeps) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        var url = env.xhrBase + '/keeps/remove';
        return $http.post(url, _.map(keeps, function (keep) {
          return {
            url: keep.url
          };
        })).then(function () {
          var map = _.reduce(keeps, function (map, keep) {
            map[keep.id] = true;
            return map;
          }, {});

          _.remove(list, function (keep) {
            return map[keep.id];
          });

          return keeps;
        });
      },

      toggleKeep: function (keeps, isPrivate) {
        var isKept = _.every(keeps, 'isMyBookmark');
        isPrivate = isPrivate == null ? _.some(keeps, 'isPrivate') : !! isPrivate;

        if (isKept) {
          return api.unkeep(keeps);
        }
        return api.keep(keeps, isPrivate);
      },

      togglePrivate: function (keeps) {
        return api.keep(keeps, !_.every(keeps, 'isPrivate'));
      }
    };

    return api;
  }
]);

'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService', 'kifi.tagService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService',
  function ($scope, profileService, keepService, tagService) {
    $scope.me = profileService.me;

    $scope.$watch(function () {
      return ($scope.keeps && $scope.keeps.length || 0) + ',' + tagService.list.length;
    }, function () {
      // update antiscroll
      $scope.refreshScroll();

      if ($scope.keeps && $scope.keeps.length && tagService.list.length) {
        keepService.joinTags($scope.keeps, tagService.list);
      }
    });
  }
])

.directive('kfKeeps', [

  function () {

    function delegateFn(scope, name) {
      return function (keep) {
        return scope[name]({
          keep: keep
        });
      };
    }

    return {
      restrict: 'A',
      scope: {
        keeps: '=',
        checkKeep: '&',
        uncheckKeep: '&',
        toggleCheckKeep: '&',
        isCheckedKeep: '&',
        previewKeep: '&',
        togglePreviewKeep: '&',
        isPreviewedKeep: '&',
        scrollDistance: '=',
        scrollDisabled: '=',
        scrollNext: '&'
      },
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.select = delegateFn(scope, 'checkKeep');
        scope.unselect = delegateFn(scope, 'uncheckKeep');
        scope.toggleSelect = delegateFn(scope, 'toggleCheckKeep');
        scope.isSelected = delegateFn(scope, 'isCheckedKeep');
        scope.preview = delegateFn(scope, 'previewKeep');
        scope.togglePreview = delegateFn(scope, 'togglePreviewKeep');
        scope.isPreviewed = delegateFn(scope, 'isPreviewedKeep');

        scope.getSubtitle = function () {
          var subtitle = scope.subtitle;
          var numShown = scope.results.numShown;
          switch (subtitle.type) {
          case 'tag':
            switch (numShown) {
            case 0:
              return 'No Keeps in this tag';
            case 1:
              return 'Showing the only Keep in this tag';
            case 2:
              return 'Showing both Keeps in this tag';
            }
            if (numShown === scope.results.numTotal) {
              return 'Showing all ' + numShown + ' Keeps in this tag';
            }
            return 'Showing the ' + numShown + ' latest Keeps in this tag';
          }
          return subtitle.text;
        };

        scope.onClickKeep = function (keep, $event) {
          if ($event.target.tagName !== 'A') {
            scope.togglePreview(keep);
          }
        };

        scope.isScrollDisabled = function () {
          return scope.scrollDisabled;
        };

        if (scope.scrollDistance == null) {
          scope.scrollDistance = '100%';
        }
      }
    };
  }
]);

'use strict';

angular.module('kifi.layout.leftCol', [])

.controller('LeftColCtrl', [
  '$scope', '$window',
  function ($scope, $window) {
    $window.console.log('LeftColCtrl');
  }
]);


'use strict';

angular.module('kifi.layout.main', [])

.controller('MainCtrl', [
  '$scope',
  function ($scope) {
    var KEY_ESC = 27;

    $scope.search = {};

    $scope.isEmpty = function () {
      return !$scope.search.text;
    };

    $scope.onKeydown = function (e) {
      if (e.keyCode === KEY_ESC) {
        $scope.clear();
      }
    };

    $scope.onFocus = function () {
      $scope.focus = true;
    };

    $scope.onBlur = function () {
      $scope.focus = false;
    };

    $scope.clear = function () {
      $scope.search.text = '';
    };

    $scope.undoAction = {
      message: 'hi'
    };

    $scope.undo = function () {
      $scope.undoAction = null;
    };
  }
]);

'use strict';

angular.module('kifi.layout.nav', ['util'])

.directive('kfNav', [
  '$location', 'util', 'keepService',
  function ($location, util, keepService) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.counts = {
          keepCount: keepService.totalKeepCount,
          friendsNotifCount: 0
        };

        scope.$watch(function () {
          return keepService.totalKeepCount;
        }, function (val) {
          scope.counts.keepCount = val;
        });

        scope.isActive = function (path) {
          var loc = $location.path();
          return loc === path || util.startsWith(loc, path + '/');
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.layout.rightCol', [])

.controller('RightColCtrl', [
  '$scope', '$window',
  function ($scope, $window) {
    $window.console.log('RightColCtrl');
  }
]);


'use strict';

angular.module('kifi.profile', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/profile', {
      templateUrl: 'profile/profile.tpl.html',
      controller: 'ProfileCtrl'
    });
  }
])

.controller('ProfileCtrl', [
  '$scope', 'keepService',
  function ($scope, keepService) {

  }
]);

'use strict';

angular.module('kifi.profileCard', ['kifi.profileService'])

.directive('kfProfileCard', [
  'profileService',
  function (profileService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'profileCard/profileCard.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.me = profileService.me;
        profileService.fetchMe();
      }
    };
  }
]);

'use strict';

angular.module('kifi.profileService', [])

.factory('profileService', [
  '$http', 'env',
  function ($http, env) {
    var me = {
      seqNum: 0
    };

    function formatPicUrl(userId, pictureName, size) {
      return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
    }

    return {
      me: me, // when mutated, you MUST increment me.seqNum
      fetchMe: function () {
        var url = env.xhrBase + '/user/me';
        return $http.get(url).then(function (res) {
          angular.forEach(res.data, function (val, key) {
            me[key] = val;
          });
          me.picUrl = formatPicUrl(me.id, me.pictureName);
          me.seqNum++;
          return me;
        });
      }

    };
  }
]);

'use strict';

angular.module('kifi.search', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/find', {
      templateUrl: 'search/search.tpl.html',
      controller: 'SearchCtrl'
    });
  }
])

.controller('SearchCtrl', [
  '$scope', 'keepService',
  function ($scope, keepService) {
    $scope.results = {
      numShown: 0,
      myTotal: 300,
      friendsTotal: 0,
      othersTotal: 12342
    };

    $scope.filter = {
      type: 'm'
    };

    $scope.isFilterSelected = function (type) {
      return $scope.filter.type === type;
    };

    function getFilterCount(type) {
      switch (type) {
      case 'm':
        return $scope.results.myTotal;
      case 'f':
        return $scope.results.friendsTotal;
      case 'a':
        return $scope.results.othersTotal;
      }
    }

    $scope.isEnabled = function (type) {
      if ($scope.isFilterSelected(type)) {
        return false;
      }
      return !!getFilterCount(type);
    };

    $scope.getFilterUrl = function (type) {
      if ($scope.isEnabled(type)) {
        var count = getFilterCount(type);
        if (count) {
          return '/find?q=' + ($scope.results.query || '') + '&f=' + type + '&maxHits=30';
        }
      }
      return '';
    };

    $scope.getSubtitle = function () {
      var numShown = $scope.results.numShown;

      if ($scope.isSearching) {
        return 'Searching...';
      }

      switch (numShown) {
      case 0:
        return 'Sorry, no results found for &#x201c;' + ($scope.results.query || '') + '&#x202c;';
      case 1:
        return '1 result found';
      default:
        return 'Top ' + numShown + ' results';
      }

    };
  }
]);

'use strict';

angular.module('kifi.tagService', ['kifi.keepService'])

.factory('tagService', [
  '$http', 'env', '$q', '$rootScope', 'keepService',
  function ($http, env, $q, $rootScope, keepService) {
    var list = [],
      fetchAllPromise = null;

    function indexById(id) {
      for (var i = 0, l = list.length; i < l; i++) {
        if (list[i].id === id) {
          return i;
        }
      }
      return -1;
    }

    function updateKeepCount(id, delta) {
      var index = indexById(id);
      if (index !== -1) {
        var tag = list[index];
        tag.keeps = (tag.keeps || 0) + delta;
        return tag;
      }
      return null;
    }

    return {
      list: list,

      fetchAll: function (force) {
        if (!force && fetchAllPromise) {
          return fetchAllPromise;
        }

        var url = env.xhrBase + '/collections/all';
        var config = {
          params: {
            sort: 'user',
            _: Date.now().toString(36)
          }
        };

        fetchAllPromise = $http.get(url, config).then(function (res) {
          var tags = res.data && res.data.collections || [];
          list.length = 0;
          list.push.apply(list, tags);

          keepService.totalKeepCount = res.data.keeps; // a bit weird...

          return list;
        });

        return fetchAllPromise;
      },

      create: function (name) {
        var url = env.xhrBase + '/collections/create';

        return $http.post(url, {
          name: name
        }).then(function (res) {
          var tag = res.data;
          tag.keeps = tag.keeps || 0;
          list.unshift(tag);
          return tag;
        });
      },

      remove: function (tagId) {
        function removeTag(id) {
          var index = indexById(id);
          if (index !== -1) {
            list.splice(index, 1);
          }
        }

        var url = env.xhrBase + '/collections/' + tagId + '/delete';
        return $http.post(url).then(function () {
          removeTag(tagId);
          $rootScope.$emit('tags.remove', tagId);
          return tagId;
        });
      },

      rename: function (tagId, name) {
        function renameTag(id, name) {
          var index = indexById(id);
          if (index !== -1) {
            var tag = list[index];
            tag.name = name;
            return tag;
          }
          return null;
        }

        var url = env.xhrBase + '/collections/' + tagId + '/update';
        return $http.post(url, {
          name: name
        }).then(function (res) {
          var tag = res.data;
          return renameTag(tag.id, tag.name);
        });
      },

      removeKeepsFromTag: function (tagId, keepIds) {
        var url = env.xhrBase + '/collections/' + tagId + '/removeKeeps';
        $http.post(url, keepIds).then(function (res) {
          updateKeepCount(tagId, -keepIds.length);
          // broadcast change to interested parties
          keepIds.forEach(function (keepId) {
            $rootScope.$emit('tags.removeFromKeep', {tagId: tagId, keepId: keepId});
          });
          return res;
        });
      },

      addKeepsToTag: function (tag, keeps) {
        var url = env.xhrBase + '/keeps/add';
        var payload = {
          collectionId: tag.id,
          keeps: keeps
        };
        $http.post(url, payload).then(function (res) {
          updateKeepCount(tag.id, keeps.length);
          // broadcast change to interested parties
          keeps.forEach(function (keep) {
            $rootScope.$emit('tags.addToKeep', {tag: tag, keep: keep});
          });
          return res;
        });
      }
    };
  }
]);

'use strict';

angular.module('kifi.tags', ['util', 'dom', 'kifi.tagService'])

.controller('TagsCtrl', [
  '$scope', '$timeout', 'tagService',
  function ($scope, $timeout, tagService) {
    $scope.create = function (name) {
      if (name) {
        return tagService.create(name)
          .then(function (tag) {
            tag.isNew = true;
            $scope.clearFilter();

            $timeout(function () {
              delete tag.isNew;
            }, 3000);

            return tag;
          });
      }
    };

    $scope.rename = function (tag) {
      if (tag) {
        $scope.lastHighlight = $scope.highlight;
        $scope.renameTag = {
          value: tag.name
        };
        $scope.renaming = tag;
      }
    };

    $scope.remove = function (tag) {
      if (tag && tag.id) {
        return tagService.remove(tag.id);
      }
    };
  }
])

.directive('kfTags', [
  '$timeout', '$location', 'util', 'dom', 'tagService', 'profileService',
  function ($timeout, $location, util, dom, tagService, profileService) {
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
      controller: 'TagsCtrl',
      link: function (scope, element /*, attrs*/ ) {
        scope.tags = tagService.list;

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

        function rehighlight() {
          if (scope.lastHighlight && !scope.highlight) {
            scope.highlight = scope.lastHighlight;
          }
          scope.lastHighlight = null;
        }

        scope.submitRename = function () {
          // different scope
          var newName = scope.renameTag.value,
            tag = scope.renaming;
          if (newName && newName !== tag.name) {
            return tagService.rename(tag.id, newName).then(function (tag) {
              scope.cancelRename();
              return tag;
            });
          }
          return scope.cancelRename();
        };

        scope.cancelRename = function () {
          scope.renaming = null;
          scope.focusFilter = true;
          rehighlight();
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
            if (scope.highlight) {
              scope.dehighlight();
            }
            else {
              scope.clearFilter();
            }
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
        var hidden = element.find('.kf-tag-list-hidden');

        function positionTagsList() {
          list.css({
            position: 'absolute',
            top: hidden.position().top,
            bottom: 0
          });
        }
        $timeout(positionTagsList);

        scope.$watch(function () {
          return profileService.me.seqNum;
        }, function () {
          positionTagsList();
        });

        scope.$watch('filter.name', function () {
          $timeout(scope.refreshHighlight);
          scope.refreshScroll();
        });

        scope.$watch('tags.length', function () {
          scope.refreshScroll();
        });

        tagService.fetchAll();
      }
    };
  }
]);

angular.module('kifi.templates', ['common/directives/youtube.tpl.html', 'detail/detail.tpl.html', 'detail/keepDetail.tpl.html', 'detail/tagList.tpl.html', 'home/home.tpl.html', 'keep/keep.tpl.html', 'keep/keepWhoPics.tpl.html', 'keep/keepWhoText.tpl.html', 'keeps/keeps.tpl.html', 'layout/footer/footer.tpl.html', 'layout/leftCol/leftCol.tpl.html', 'layout/main/main.tpl.html', 'layout/nav/nav.tpl.html', 'layout/rightCol/rightCol.tpl.html', 'profile/profile.tpl.html', 'profileCard/profileCard.tpl.html', 'search/search.tpl.html', 'tags/tags.tpl.html']);

angular.module('common/directives/youtube.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('common/directives/youtube.tpl.html',
    '<div class="kf-youtube"></div>');
}]);

angular.module('detail/detail.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('detail/detail.tpl.html',
    '<div class="kf-detail-pane" ng-show="isDetailOpen()"><div class="kf-detail-scroll" antiscroll="{ autoHide: false }"><div class="kf-detail-inner" ng-class="{ single: isSingleKeep(), multiple: !isSingleKeep(), private: isPrivate(), public: isPublic() }"><a class="kf-detail-x" href="javascript:" ng-click="closeDetail()"></a><div class="kf-page-meta" data-n="{{getLength()}}"><div kf-keep-detail="" ng-if="isSingleKeep()"></div><h2 class="kf-detail-header page-title" ng-hide="isSingleKeep()" ng-bind="getTitleText()"></h2><a class="kf-page-keep" href="javascript:" ng-click="toggleKeep()"></a> <a class="kf-page-priv" href="javascript:" ng-click="togglePrivate()"></a></div><div ng-if="isSingleKeep()"><div class="kf-page-who"><h2 class="kf-detail-header">Who kept this:</h2><div class="kf-page-who-pics"><span kf-keep-who-pics="" me="me" keepers="keep.keepers"></span></div><div class="kf-page-who-text" kf-keep-who-text="" keep="keep"></div></div><div class="kf-page-chatter"><h2 class="kf-detail-header">Talking about this Keep:</h2><a class="kf-page-chatter-messages" href="{{url}}" target="_blank" data-locator="/messages"><span class="chatter-count">{{keep.conversationCount || 0}}</span>{{getPrivateConversationText()}}</a></div></div><span kf-tag-list="" keep="keep" get-selected-keeps="getSelectedKeeps()"></span></div></div></div>');
}]);

angular.module('detail/keepDetail.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('detail/keepDetail.tpl.html',
    '<div class="kf-keep-detail"><h2 class="kf-detail-header page-title" ng-bind="keep.title"></h2><a class="kf-keep-detail-url long-text" ng-href="{{keep.url}}" target="_blank">{{keep.url}}</a><div class="kf-page-pic-wrap"><div class="kf-page-pic-special" ng-class="{ \'kf-page-pic-special-cell\': keep.isEmbed }" ng-if="keep.isEmbed"><div kf-youtube="" video-id="keep.videoId"></div></div><a class="kf-page-pic" ng-if="!keep.isEmbed" ng-href="{{keep.url}}" target="_blank" ng-attr-style="background-image: url({{keep.screenshot}})"><div class="kf-page-pic-1"><div class="kf-page-pic-2"><div class="kf-page-pic-3"><div class="kf-page-pic-soon">Preview of this page<br>not yet available</div><span class="kf-page-pic-tip">Visit page</span></div></div></div></a><div class="kf-page-how"><div class="kf-page-how-0"><div class="kf-page-how-pub"><div class="kf-page-how-1"><div class="kf-page-how-2"><div class="kf-page-how-3">Public</div></div></div></div><div class="kf-page-how-pri"><div class="kf-page-how-1"><div class="kf-page-how-2"><div class="kf-page-how-3">Private</div></div></div></div></div></div></div></div>');
}]);

angular.module('detail/tagList.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('detail/tagList.tpl.html',
    '<div class="page-colls"><h2 class="kf-detail-header" ng-show="commonTags.length > 0">Tags:</h2><ul class="page-coll-list"><li class="page-coll" data-id="{{tag.id}}" ng-repeat="tag in commonTags"><a class="page-coll-a" href="/tag/{{tag.id}}">{{tag.name}}</a> <a class="page-coll-x" href="javascript:" ng-click="removeTagFromSelectedKeeps(tag)">×</a></li><li class="page-coll-new"><span ng-show="!isAddingTag"><a class="page-coll-add-circle" href="javascript:" ng-show="hasTags()" ng-click="showAddTagDropdown()">+</a> <a class="page-coll-add" href="javascript:" ng-show="!hasTags()" ng-click="showAddTagDropdown()"><span class="page-coll-add-circle">+</span> {{addTagLabel()}}</a></span> <span ng-show="isAddingTag" kf-tag-suggestions=""><input focus-when="shouldGiveFocus" class="page-coll-input" type="text" placeholder="tag name" ng-model="tagFilter.name" ng-keydown="onKeydown($event)" ng-blur="hideAddTagDropdown()"><ul class="page-coll-opts"><li class="page-coll-opt" ng-repeat="tag in tagTypeAheadResults" ng-class="{ current: isTagHighlighted(tag) }" ng-mousedown="addTag(tag, keep)" ng-bind-html="tag.prettyHtml" ng-mouseover="highlightTag(tag)"></li><li class="page-coll-opt" ng-if="isAddTagShown()" ng-mousedown="createAndAddTag(keep)" ng-class="{ current: isTagHighlighted(null) }" ng-mouseover="highlightTag(null)">{{tagFilter.name}}<span class="new-label">{{newTagLabel}}</span></li></ul><span class="page-coll-opt-hidden"></span></span></li></ul></div>');
}]);

angular.module('home/home.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('home/home.tpl.html',
    '<div><div class="kf-main-head"><h1 class="kf-main-title">Browse your Keeps</h1><div class="kf-subtitle"><span class="kf-check-all" ng-class="{ enabled: checkEnabled, checked: isSelectedAll() }" ng-click="toggleSelectAll()" ng-mouseover="onMouseoverCheckAll()" ng-mouseout="onMouseoutCheckAll()"></span> <span class="kf-subtitle-text">{{getSubtitle()}}</span></div></div><div kf-keeps="" keeps="keeps" check-keep="keepService.select(keep)" uncheck-keep="keepService.unselect(keep)" toggle-check-keep="keepService.toggleSelect(keep)" is-checked-keep="keepService.isSelected(keep)" preview-keep="keepService.preview(keep)" toggle-preview-keep="keepService.togglePreview(keep)" is-previewed-keep="keepService.isPreviewed(keep)" scroll-distance="scrollDistance" scroll-disabled="scrollDisabled" scroll-next="getNextKeeps()"></div></div>');
}]);

angular.module('keep/keep.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keep/keep.tpl.html',
    '<li class="kf-keep" ng-class="{ mine: isMyBookmark(keep), example: isExample(keep), private: isPrivate(keep), detailed: isPreviewed(keep), selected: isSelected(keep) }"><time class="kf-keep-time" ng-attr-datetime="{{keep.createdAt}}" ng-show="keep.createdAt" am-time-ago="keep.createdAt"></time><div class="kf-keep-handle"><div class="kf-keep-checkbox" ng-click="onCheck($event)"></div></div><div class="kf-keep-title"><span class="kf-example-tag">Example keep</span> <a class="kf-keep-title-link" ng-href="{{keep.url}}" target="_blank" ng-attr-title="{{getTitle()}}" ng-bind-html="keep.titleHtml"></a></div><div class="kf-keep-url" ng-attr-title="{{keep.url}}" ng-bind-html="keep.descHtml"></div><div class="kf-keep-tags"><span class="kf-keep-tag" ng-class="{ example: isExampleTag(tag) }" ng-repeat="tag in getTags()"><a class="kf-keep-tag-link" ng-href="/tag/{{tag.id}}">{{tag.name}}</a></span></div><div class="kf-keep-who"><span kf-keep-who-pics="" me="me" keepers="keep.keepers"></span> <span class="kf-keep-who-others" ng-if="showOthers()"></span> <span kf-keep-who-text="" keep="keep"></span></div><div class="kf-keep-arrow-1"><div class="kf-keep-arrow-2"><div class="kf-keep-arrow-3"><div class="kf-keep-arrow-4"></div></div></div></div><div class="kf-keep-hover-button-wrapper"><div class="kf-keep-hover-button-table"><div class="kf-keep-hover-button-cell"><div class="kf-keep-hover-button"><span class="kf-keep-button-preview">Preview</span> <span class="kf-keep-button-close">Close</span></div></div></div></div></li>');
}]);

angular.module('keep/keepWhoPics.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keep/keepWhoPics.tpl.html',
    '<span class="kf-keep-who-pics"><a class="kf-keep-who-pic me" title="You! You look great!" ng-attr-style="background-image: url({{getPicUrl(me)}})"></a> <a class="kf-keep-who-pic" href="javascript:" data-id="{{id}}" data-name="{{getName(keeper)}}" ng-attr-style="background-image: url({{getPicUrl(keeper)}})" ng-repeat="keeper in keepers | limitTo: 9"></a></span>');
}]);

angular.module('keep/keepWhoText.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keep/keepWhoText.tpl.html',
    '<span class="kf-keep-who-text"><span class="kf-keep-you">You <span class="kf-keep-private" ng-class="{ on: isPrivate() }" ng-show="hasKeepers()">Private</span></span> <span class="kf-keep-friends" ng-show="hasKeepers()">{{getFriendText()}}</span> <span class="kf-keep-others" ng-show="hasOthers()">{{getOthersText()}}</span> <span class="kf-keep-kept-this">kept this</span> <span class="kf-keep-private" ng-class="{ on: isPrivate() }" ng-if="isOnlyMine()">Private</span></span>');
}]);

angular.module('keeps/keeps.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keeps/keeps.tpl.html',
    '<div class="kf-main-keeps" antiscroll="{ autoHide: false }"><div smart-scroll="" scroll-distance="scrollDistance" scroll-disabled="isScrollDisabled()" scroll-next="scrollNext()"><ol id="kf-search-results"></ol><ol id="kf-my-keeps"><div kf-keep="" ng-repeat="keep in keeps" ng-click="onClickKeep(keep, $event)" post-repeat-directive=""></div></ol><div class="kf-keep-group-title-fixed"></div><img class="kf-keeps-loading" src="/img/wait.gif"> <a class="kf-keeps-load-more hidden" href="javascript:">Show more</a></div></div>');
}]);

angular.module('layout/footer/footer.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/footer/footer.tpl.html',
    '<div class="kf-footer" antiscroll="{ autoHide: false }"><h2 class="kf-footer-header">Getting Started with kifi</h2><ul class="kf-footer-list about-kifi"><li><a class="kf-footer-item-link kifi-tutorial" href="#"><span class="kf-footer-item-icon kf-footer-item-icon-tutorial"></span> Quick guide to get started</a></li><li class="install-kifi"><a class="kf-footer-item-link" href="/install"><span class="kf-footer-item-icon kf-footer-item-icon-install"></span> Install the extension</a></li><li><a class="kf-footer-item-link" href="friends/invite"><span class="kf-footer-item-icon kf-footer-item-icon-friends"></span> Find friends</a></li></ul><h2 class="kf-footer-header">kifi Support and Updates</h2><ul class="kf-footer-list about-us"><li><a class="kf-footer-item-link support-center" href="http://support.kifi.com"><span class="kf-footer-item-icon kf-footer-item-icon-support"></span> Support center</a></li><li><a class="kf-footer-item-link contact-us" href="http://support.kifi.com/customer/portal/emails/new"><span class="kf-footer-item-icon kf-footer-item-icon-contact"></span> Contact us</a></li><li><a class="kf-footer-item-link updates-features" href="http://blog.kifi.com"><span class="kf-footer-item-icon kf-footer-item-icon-blog"></span> kifi blog</a></li></ul><div class="kf-footer-more"><a href="/privacy">Privacy</a> <span class="kf-footer-sep">|</span> <a href="/terms">Terms</a> <span class="kf-footer-sep">|</span> <a href="/about">About kifi</a> <span class="kf-footer-sep">|</span> <a href="http://www.42go.com">About FortyTwo</a></div></div>');
}]);

angular.module('layout/leftCol/leftCol.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/leftCol/leftCol.tpl.html',
    '<div class="kf-col-inner" ng-controller="LeftColCtrl"><div class="kf-header-left"><a class="kf-header-logo" href="/"></a></div><div kf-profile-card=""></div><div kf-nav=""></div><div kf-tags=""></div></div>');
}]);

angular.module('layout/main/main.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/main/main.tpl.html',
    '<div class="kf-col-inner" ng-controller="MainCtrl"><div class="kf-query-wrap" ng-class="{ empty: isEmpty(), focus: focus }"><input class="kf-query" type="text" placeholder="Find anything..." ng-model="search.text" ng-keydown="onKeydown($event)" ng-focus="onFocus()" ng-blur="onBlur()"><span class="kf-query-icon"><b class="kf-query-mag"></b> <a class="kf-query-x" href="javascript:" ng-click="clear()"></a></span><div class="kf-undo" ng-if="undoAction"><span class="kf-undo-box"><span class="kf-undo-message">{{undoAction.message}}</span> <a class="kf-undo-link" href="javascript:" ng-click="undo()">Undo</a> <span></span></span></div></div><div ng-view=""></div></div>');
}]);

angular.module('layout/nav/nav.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/nav/nav.tpl.html',
    '<div class="kf-nav-group-name">You</div><ul class="kf-nav-group"><li class="kf-nav-item kf-nav-keeps" ng-class="{ active: isActive(\'/\') }"><a class="kf-nav-link" href="/"><span class="kf-nav-name">Your Keeps</span> <span class="kf-nav-count" ng-class="{ empty: !counts.keepCount }">{{counts.keepCount}}</span></a></li><li class="kf-nav-item kf-nav-friends" ng-class="{ active: isActive(\'/friends\') }"><a class="kf-nav-link" href="/friends"><span class="kf-nav-name">Your Friends</span> <span class="kf-nav-count" ng-class="{ empty: !counts.friendsNotifCount }">{{counts.friendsNotifCount}}</span></a></li></ul>');
}]);

angular.module('layout/rightCol/rightCol.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/rightCol/rightCol.tpl.html',
    '<div class="kf-col-inner" ng-controller="RightColCtrl"><nav class="kf-top-right-nav"><a href="/logout">Log out</a></nav><div ng-include="\'layout/footer/footer.tpl.html\'"></div><div kf-detail=""></div></div>');
}]);

angular.module('profile/profile.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profile/profile.tpl.html',
    '<div>Hello world!</div>');
}]);

angular.module('profileCard/profileCard.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profileCard/profileCard.tpl.html',
    '<div class="kf-my-identity"><a class="kf-my-settings-wrapper" href="/profile"><span class="kf-my-pic" ng-style="{\'background-image\': \'url(\' + me.picUrl + \')\'}"></span> <span class="kf-my-settings-shade"></span> <span class="kf-my-settings"><span class="kf-my-settings-icon"></span> <span class="kf-my-settings-text">Settings</span></span></a><div class="kf-my-name">{{me.firstName}} {{me.lastName}}</div><div class="kf-my-description">{{me.description}}</div></div>');
}]);

angular.module('search/search.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('search/search.tpl.html',
    '<div><div class="kf-main-head"><div class="kf-subtitle"><span class="kf-check-all" ng-class="{ enabled: checkEnabled, checked: isSelectedAll() }" ng-click="toggleSelectAll()" ng-mouseover="onMouseoverCheckAll()" ng-mouseout="onMouseoutCheckAll()"></span> <span class="kf-subtitle-text">{{getSubtitle()}}</span> <span class="kf-search-filters" ng-if="subtitle.type === \'query\' || true"><a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'m\'), enabled: isEnabled(\'m\') }" ng-href="{{getFilterUrl(\'m\')}}">You ({{results.myTotal}})</a> <a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'f\'), enabled: isEnabled(\'f\') }" ng-href="{{getFilterUrl(\'f\')}}">Friends ({{results.friendsTotal}})</a> <a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'a\'), enabled: isEnabled(\'a\') }" ng-href="{{getFilterUrl(\'a\')}}">All ({{results.myTotal + results.friendsTotal + results.othersTotal}})</a></span></div></div><div kf-keeps=""></div></div>');
}]);

angular.module('tags/tags.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('tags/tags.tpl.html',
    '<div class="kf-tags"><div class="kf-nav-group-name">Tags</div><label class="kf-nav-item kf-tag-input-box"><input name="filterName" placeholder="Find or add a tag..." ng-model="filter.name" focus-when="focusFilter" ng-keydown="onKeydown($event)" ng-blur="dehighlight()"><a class="kf-tag-input-clear" href="javascript:" ng-click="clearFilter(true)" ng-show="filter.name">&times;</a></label></div><span class="kf-tag-list-hidden"></span><ul class="kf-tag-list" antiscroll="{ autoHide: false }"><li class="kf-nav-item kf-tag" ng-class="{ active: isActiveTag(tag), highlight: isHighlight(tag), new: tag.isNew, renaming: isRenaming(tag) }" ng-repeat="tag in shownTags = (tags | filter: filter)"><a class="kf-nav-link" ng-href="/tag/{{tag.id}}"><span class="kf-tag-icon"></span> <span class="kf-tag-name">{{tag.name}}</span> <span class="kf-tag-count">{{tag.keeps}}</span></a><input class="kf-tag-rename" type="text" placeholder="Type new tag name" ng-model="renameTag.value" ng-keydown="onRenameKeydown($event)" ng-if="isRenaming(tag)" autofocus><div class="dropdown"><a class="dropdown-toggle"></a><ul class="dropdown-menu"><li><a href="javascript:" ng-click="rename(tag)">Rename</a></li><li><a href="javascript:" ng-click="remove(tag)">Remove</a></li></ul></div></li><li class="kf-nav-item kf-tag kf-tag-new" ng-class="{ highlight: isHighlightNew() }" ng-show="showAddTag()"><a href="javascript:" ng-click="create(filter.name)"><span class="kf-tag-icon kf-tag-icon-create"></span> <span class="kf-tag-caption">Add a new tag:</span> <span class="kf-tag-name">{{filter.name}}</span></a></li></ul>');
}]);
