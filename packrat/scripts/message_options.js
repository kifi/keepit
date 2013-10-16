// @require scripts/lib/jquery.js
// @require scripts/lib/underscore.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/lib/q.min.js
// @require scripts/render.js
// @require scripts/util.js
// @require scripts/scorefilter.js
// @require scripts/html/keeper/message_options.js
// @require styles/keeper/message_options.css
// @require styles/animate-custom.css

/**
 * ---------------------
 *    Message Options
 * ---------------------
 *
 * Message Options is an UI component that is a hub for plugins
 * related to current message context.
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-16-2013
 */

var messageOptions = this.messageOptions = (function ($, win) {
	'use strict';

	var util = win.util,
		Q = win.Q,
		_ = win._;

	// receive
	api.port.on({
		friends: function (friends) {}
	});

	api.onEnd.push(function () {
		messageOptions.destroy('api:onEnd');
		messageOptions = win.messageOptions = null;
	});

	return {

		/**
		 * A constructor of Message Options
		 *
		 * Renders and initializes a message options box if not already.
		 *
		 * @constructor
		 *
		 * @param {string} trigger - A triggering user action
		 */
		construct: function (trigger) {
			if (!this.alive) {
				this.init(trigger);
			}
		},

		/**
		 * Renders and initializes a Message Options.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		init: function (trigger) {
			this.alive = true;
			this.initMessageOptions();
			this.initPlugins();

			this.logEvent('init', {
				trigger: trigger
			});
		},

		/**
		 * Finds, initializes, and caches a container.
		 *
		 * @return {jQuery} A jQuery object for the container
		 */
		initMessageOptions: (function () {

			function onClick(e) {
				if (!this.contains(e.target)) {
					e.uiClosed = true;
					/*
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
          */
					this.hide(this.getClickInfo('outside'));
				}
			}

			function addDocListeners() {
				if (this.active) {
					var $doc = $(document);
					this.prevEscHandler = this.getData($doc, 'esc');
					$doc.data('esc', this.handleEsc.bind(this));

					var onDocClick = this.onDocClick = onClick.bind(this);
					document.addEventListener('click', onDocClick, true);
				}
			}

			return function () {
				var $el = $(this.renderContainer()).insertBefore($('body'));
				this.$el = $el;

				win.setTimeout(addDocListeners.bind(this), 50);

				return $el;
			};
		})(),

		handleEsc: function (e) {
			e.preventDefault();
			e.stopPropagation();
			e.stopImmediatePropagation();
			log('esc');

			if (this.currentSuggestion) {
				this.navigateTo(null, 'esc');
			}
			else {
				this.hide('key:esc');
			}
		},

		/**
		 * Finds, initializes, and caches a suggestion box.
		 *
		 * @return {jQuery} A jQuery object for suggestion list
		 */
		initSuggest: function () {
			log('initSuggest.start');

			var $suggest = this.$tagbox.find('.kifi-tagbox-suggest-inner');
			this.$suggest = $suggest;

			$suggest.on('click', '.kifi-tagbox-suggestion', this.onClickSuggestion.bind(this));
			$suggest.on('click', '.kifi-tagbox-new', this.onClickNewSuggestion.bind(this));
			$suggest.on('mouseover', this.onMouseoverSuggestion.bind(this));

			log('initSuggest.end');

			return $suggest;
		},

		/**
		 * Finds and caches a tag list container.
		 *
		 * @return {jQuery} A jQuery object for tag list
		 */
		initTagList: function () {
			log('initTagList.start');

			var $tagList = this.$tagbox.find('.kifi-tagbox-tag-list-inner');
			this.$tagList = $tagList;

			this.$tagbox.on('click', '.kifi-tagbox-tag-name', function () {
				this.logEvent('navigate', {
					trigger: this.getClickInfo('tag')
				});
			}.bind(this));

			$tagList.on('click', '.kifi-tagbox-tag-remove', this.onClickRemoveTag.bind(this));

			log('initTagList.end');

			return $tagList;
		},

		/**
		 * Add a clear event listener to clear button.
		 */
		initClearAll: function () {
			this.$tagbox.on('click', '.kifi-tagbox-clear', function () {
				this.clearTags(this.getClickInfo('clear'));
			}.bind(this));
		},

		/**
		 * Makes a request to the server to get all tags owned by the user.
		 * Makes a request to the server to get all tags on the page.
		 *
		 * @return {Object} A deferred promise object
		 */
		initTags: function () {
			log('initTags.start');
			this.requestTags()
				.then(this.onFetchTags.bind(this))
				.then(this.updateSuggestHeight.bind(this))
				.then(this.updateTagList.bind(this))
				.then(this.updateSuggestion.bind(this))
				.then(this.moveTileToBottom.bind(this))
				.then(this.setLoaded.bind(this, false))
				.then(this.focusInput.bind(this))
				.then(this.updateScroll.bind(this))
				.fail(function (err) {
				this.hide('error:failed to init');
				throw err;
			}.bind(this))
				.fail(this.logError.bind(this));
			log('initTags.end');
		},

		/**
		 * Destroys a tag box.
		 * It removes all event listeners and caches to elements.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		destroy: function (trigger) {
			if (this.active) {
				this.active = false;

				win.slider2.unshadePane();
				$(win.tile).css('transform', '');

				$(win).off('resize.kifi-tagbox-suggest', this.winResizeListener);

				'$input,$inputbox,$suggest,$suggestWrapper,$tagbox,$tagList,$tagListWrapper'.split(',').forEach(function (name) {
					var $el = this[name];
					if ($el) {
						$el.remove();
						this[name] = null;
					}
				}, this);

				$(document).data('esc', this.prevEscHandler);
				this.prevEscHandler = null;

				var onDocClick = this.onDocClick;
				if (onDocClick) {
					document.removeEventListener('click', onDocClick, true);
					this.onDocClick = null;
				}

				this.$slider = null;
				this.tags = [];
				this.tagsAdded = {};
				this.tagsBeingCreated = {};
				this.busyTags = {};

				this.logEvent('destroy', {
					trigger: trigger
				});
			}
		},

		/**
		 * Returns whether the given element is contained inside this UI container.
		 *
		 * @param {HTMLElement} el - an html element
		 *
		 * @return {boolean} Whether the given element is contained inside this UI container.
		 */
		contains: function (el) {
			var $el = this.$el;
			return $el != null && $el[0].contains(el);
		},

		/**
		 * Adds class from the root element.
		 *
		 * @param {string} A Class name to add.
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		addClass: function () {
			var $tagbox = this.$tagbox;
			return $tagbox && $tagbox.addClass.apply($tagbox, arguments);
		},

		/**
		 * Removes class from the root element.
		 *
		 * @param {string} A Class name to remove.
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		removeClass: function () {
			var $tagbox = this.$tagbox;
			return $tagbox && $tagbox.removeClass.apply($tagbox, arguments);
		},

		/**
		 * Toggles (Add/Remove) a class of the root element.
		 *
		 * @param {string} A Class name to toggle.
		 * @param {boolean} Whether to add or remove
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		toggleClass: function (classname, add) {
			var $tagbox = this.$tagbox;
			return $tagbox && $tagbox.toggleClass(classname, !! add);
		},

		moveTileToBottom: function (res) {
			var tile = win.tile,
				$tile = $(tile),
				dy = window.innerHeight - tile.getBoundingClientRect().bottom;

			if (!dy) {
				return res;
			}

			$tile.css('transform', 'translate(0,' + dy + 'px)');

			var deferred = Q.defer();

			$tile.on('transitionend', function onTransitionend(e) {
				if (e.target === this) {
					$tile.off('transitionend', onTransitionend);
					deferred.resolve();
				}
			});

			return deferred.promise;
		},

		/**
		 * Removes 'kifi-loading' class of the root element.
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		setLoaded: function () {
			this.removeClass('kifi-loading');
			win.slider2.shadePane();
		},

		/**
		 * Given an input string to match against,
		 * it rerenders tag suggestions.
		 *
		 * @param {string} text - An input string to match against
		 */
		suggest: function (text) {
			if (!this.active) {
				return;
			}

			//log('suggest', text);

			var tags = this.tags;
			tags = this.filterOutAddedTags(tags);
			tags = this.filterTagsByText(text, tags);

			var html = this.renderTagSuggestionsHtml(tags),
				$suggest = this.$suggest;
			$suggest.html(html);

			if (text.trim() && this.indexOfTagByName(text) === -1) {
				$suggest.append(this.renderNewTagSuggestionHtml(text));
			}

			this.updateSuggestedClass();
			this.updateScroll();

			this.navigateTo('first', 'suggest');
		},

		/**
		 * Updates suggestion according to the current states (tags + input).
		 */
		updateTagList: function () {
			if (this.active) {
				var tags = this.getAddedTags(),
					html = tags.map(this.renderTagHtml, this).join('');
				this.$tagList.html(html);

				this.toggleClass('kifi-tagged', tags.length);
			}
		},

		/**
		 * Removes a suggestion entry from a suggestion box with the given id.
		 *
		 * @param {string} tagId - A tag id to remove
		 *
		 * @return {number} Number of elements removed
		 */
		removeSuggestionById: function (tagId) {
			var $el = this.getSuggestion$ById(tagId),
				len = $el.length;
			$el.remove();
			return len;
		},

		/**
		 * Removes a suggestion entry from a suggestion box with the given name.
		 *
		 * @param {string} name - A tag name to remove
		 *
		 * @return {number} Number of elements removed
		 */
		removeNewSuggestionByName: function (name) {
			var $el = this.getNewTagSuggestion$ByName(name),
				len = $el.length;
			$el.remove();
			return len;
		},

		/**
		 * Tests whether a tag is added to a keep.
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {boolean} Whether a tag is already added to a keep
		 */
		isTagAddedById: function (tagId) {
			var map = this.tagsAdded;
			return map != null && (tagId in map);
		},

		/**
		 * Creates a new tag for user.
		 * It sends a request to server to create a tag and returns a deferred object.
		 *
		 * @param {string} name - A new tag name
		 * @param {string} trigger - A triggering user action
		 *
		 * @return {Object} A deferred promise object
		 */
		createTag: function (name, trigger) {
			name = tagNameToString(name);

			if (this.indexOfTagByName(name) !== -1) {
				log('createTag', 'tag already exists', name);
				return null;
			}

			if (this.isTagBusy(name)) {
				log('createTag', 'tag is already being created', name);
				return null;
			}

			this.addTagBusy(name, this.getNewTagSuggestion$ByName(name));

			var deferred = this.requestCreateAndAddTagByName(name)
				.then(this.onCreateResponse.bind(this))
				.fail(this.logError.bind(this))
				.fin(this.removeTagBusy.bind(this, name));

			this.logEvent('createTag', {
				trigger: trigger,
				name: name
			});

			return deferred;
		},

		/**
		 * Adds a tag to the current keep's tag list.
		 * It sends a request to server to add a tag and returns a deferred object.
		 *
		 * @param {string} tagId - A tag id
		 * @param {jQuery} [$suggestion] - jQuery object for suggestion
		 * @param {string} trigger - A triggering user action
		 *
		 * @return {Object} A deferred promise object
		 */
		addTagById: function (tagId, $suggestion, trigger) {
			var $el = this.getTag$ById(tagId);
			if ($el.length) {
				log('addTagById', 'tag already added', tagId);
				return null;
			}

			if (this.isTagBusy(tagId)) {
				log('addTagById', 'tag is already being added', tagId);
				return null;
			}

			if (!$suggestion) {
				$suggestion = this.getSuggestion$ById(tagId);
			}
			this.addTagBusy(tagId, $suggestion);

			var deferred = this.requestAddTagById(tagId)
				.then(this.onAddResponse.bind(this))
				.fail(this.logError.bind(this))
				.fin(this.removeTagBusy.bind(this, tagId));

			this.logEvent('addTag', {
				trigger: trigger,
				name: this.getTagNameById(tagId),
				input: this.getInputValue()
			});

			return deferred;
		},

		/**
		 * Removes a tag from current keep's tag list.
		 * It sends a request to server to remove a tag and returns a deferred object.
		 *
		 * @param {string} tagId - A tag id
		 * @param {string} trigger - A triggering user action
		 *
		 * @return {Object} A deferred promise object
		 */
		removeTagById: function (tagId, trigger) {
			var $el = this.getTag$ById(tagId);
			if (!$el.length) {
				log('removeTagById', 'tag is not added', tagId);
				return null;
			}

			if (this.isTagBusy(tagId)) {
				log('removeTagById', 'tag is already being removed', tagId);
				return null;
			}

			this.addTagBusy(tagId, $el);

			var deferred = this.requestRemoveTagById(tagId)
				.then(this.onRemoveResponse.bind(this, tagId))
				.fail(this.logError.bind(this))
				.fin(this.removeTagBusy.bind(this, tagId));

			this.logEvent('removeTag', {
				trigger: trigger,
				id: tagId
			});

			return deferred;
		},

		isTagBusy: function (tagId) {
			return tagId in this.busyTags;
		},

		addTagBusy: function (tagId, $el) {
			this.busyTags[tagId] = $el;
			$el.addClass('kifi-busy');
		},

		removeTagBusy: function (tagId) {
			var busyTags = this.busyTags,
				$el = busyTags[tagId];

			delete busyTags[tagId];

			$el.removeClass('kifi-busy');
		},

		/**
		 * Finds and returns jQuery object for a suggestion with the given id
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {jQuery} jQuery object for a suggestion with the given id
		 */
		getSuggestion$ById: function (tagId) {
			return this.$suggest.find('.kifi-tagbox-suggestion[data-id="' + tagId + '"]');
		},

		/**
		 * Finds and returns jQuery object for a new tag suggestion with the given name
		 *
		 * @param {string} name - A tag name
		 *
		 * @return {jQuery} jQuery object for a new tag suggestion with the given name
		 */
		getNewTagSuggestion$ByName: function (name) {
			return this.$suggest.find('.kifi-tagbox-new[data-name="' + name + '"]');
		},

		/**
		 * Finds and returns jQuery object for a tag with the given id
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {jQuery} jQuery object for a tag with the given id
		 */
		getTag$ById: function (tagId) {
			return this.$tagList.find('.kifi-tagbox-tag[data-id="' + tagId + '"]');
		},

		/**
		 * Renders and adds a tag element if not already added.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {jQuery} A jQuery object for tag element
		 */
		addTag$: function (tag) {
			var $tag = this.getTag$ById(tag.id);
			if (!$tag.length) {
				var html = this.renderTagHtml(tag);
				this.addClass('kifi-tagged');
				$tag = $(html).appendTo(this.$tagList);
				this.updateScroll();
			}
			return $tag;
		},

		/**
		 * Updates (add/remove) 'kifi-tagged' class of the tagbox.
		 */
		updateTaggedClass: function () {
			return this.toggleClass('kifi-tagged', this.$tagList.children().length);
		},

		/**
		 * Updates (add/remove) 'kifi-suggested' class of the tagbox.
		 */
		updateSuggestedClass: function () {
			return this.toggleClass('kifi-suggested', this.getInputValue() || this.$suggest.children().length);
		},

		updateTagName: function (tag) {
			addTag(this.tags, tag);
			this.updateTagName$(tag);
		},

		updateTagName$: function (tag) {
			var tagId = tag.id,
				name = tag.name,
				$tag = this.getTag$ById(tagId);
			if ($tag.length) {
				$tag.data('name', name);
				$tag.find('.kifi-tagbox-tag-name').text(name);
			}
			/*
      // TODO: @joon [10-11-2013 15:59] update suggestion tag
			else {
				$tag = this.getSuggestion$ById(tagId);
				if ($tag.length) {
					var name = tag.name;
					$tag.data('name', name);
					$tag.find('.kifi-tagbox-tag-name').text(name);
				}
			}
        */
		},

		/**
		 * Finds and removes a tag by its id
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {number} Number of elements removed
		 */
		removeTag$ById: function (tagId) {
			delete this.tagsAdded[tagId];

			var $el = this.getTag$ById(tagId),
				len = $el.length;
			if (len) {
				$el.remove();
				var tag = this.getTagById(tagId),
					tags = this.filterTagsByText(this.getInputValue(), [tag]);
				if (tags.length) {
					var $suggest = this.$suggest,
						$new = $suggest.find('.kifi-tagbox-new'),
						html = this.renderTagSuggestionHtml(tags[0]);
					if ($new.length) {
						$new.before(html);
					}
					else {
						$suggest.append(html);
						this.addClass('kifi-suggested');
					}
				}
				this.updateTaggedClass();
				this.updateScroll();
			}
			return len;
		},

		/**
		 * Clears all added tags
		 */
		clearTags$: function () {
			var $tagList = this.$tagList,
				len = $tagList.children().length;
			if (len) {
				$tagList.empty();
				var tags = this.getAddedTags();
				tags = this.filterTagsByText(this.getInputValue(), tags);
				if (tags.length) {
					var $suggest = this.$suggest,
						$new = $suggest.find('.kifi-tagbox-new'),
						html = this.renderTagSuggestionsHtml(tags);
					if ($new.length) {
						$new.before(html);
					}
					else {
						$suggest.append(html);
						this.addClass('kifi-suggested');
					}
				}
				this.removeClass('kifi-tagged');
				this.updateScroll();
			}
			return len;
		},

		//
		// REQUESTS
		//

		/**
		 * Retrieves all of the user's tags and a list of the ones applied to this page.
		 *
		 * @return {Object} A deferred promise object
		 */
		requestTags: function () {
			return this.request('get_tags', null, 'Could not load tags.');
		},

		/**
		 * Makes a request to the server to add a tag to a keep.
		 * Returns a promise object.
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {Object} A deferred promise object
		 */
		requestAddTagById: function (tagId) {
			return this.request('add_tag', tagId, 'Could not add tag, "' + tagId + '"');
		},

		/**
		 * Makes a request to the server to create/add a tag to a keep.
		 * Returns a promise object.
		 *
		 * @param {string} name - A tag name
		 *
		 * @return {Object} A deferred promise object
		 */
		requestCreateAndAddTagByName: function (name) {
			return this.request('create_and_add_tag', name, 'Could not add tag, "' + name + '"');
		},

		/**
		 * Makes a request to the server to remove a tag from a keep.
		 * Returns a promise object.
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {Object} A deferred promise object
		 */
		requestRemoveTagById: function (tagId) {
			return this.request('remove_tag', tagId, 'Could not remove tag, "' + tagId + '"');
		},

		/**
		 * Makes a request to the server to clear all tags from a keep.
		 * Returns a promise object.
		 *
		 * @return {Object} A deferred promise object
		 */
		requestClearAll: function () {
			return this.request('clear_tags', null, 'Could not clear tags');
		},

		/**
		 * Makes a request to the server and returns a deferred promise object.
		 *
		 * @param {string} name - A request name
		 * @param {*} data - A request payload
		 * @param {string} errorMsg - An error message
		 *
		 * @return {Object} A deferred promise object
		 */
		request: function (name, data, errorMsg) {
			var deferred = Q.defer();
			api.port.emit(name, data, function (result) {
				log(name + '.result', result);
				if (result && result.success) {
					deferred.resolve(result.response);
				}
				else {
					deferred.reject(new Error(errorMsg));
				}
			});
			return deferred.promise;
		},

		//
		// RESPONSE HANDLERS
		//

		/**
		 * GET_TAGS
		 *   Response: [{
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }]
		 */
		onFetchTags: function (tags) {
			log('onFetchTags', tags);
			this.setTags(tags.all);
			this.rebuildTagsAdded(tags.page);
			return arguments;
		},

		setTags: function (tags) {
			this.tags = tags || [];
			return tags;
		},

		getTagCount: function () {
			var tags = this.tags;
			return tags ? tags.length : 0;
		},

		getAddedTags: function () {
			var tagsAdded = this.tagsAdded,
				tags = this.tags;
			if (tagsAdded && tags) {
				return tags.filter(function (tag) {
					return tag.id in tagsAdded;
				});
			}
			return [];
		},

		getAddedTagCount: function () {
			return util.size(this.tagsAdded);
		},

		updateSuggestHeight: function () {
			if (this.active) {
				var height = util.minMax(32 * this.getTagCount(), 164, 265);
				this.$suggest.height(height);
			}
		},

		/**
		 * GET_TAGS_BY_URL
		 *   Response: [{
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }]
		 */
		rebuildTagsAdded: function (addedTags) {
			this.tagsAdded = util.toKeys(util.pluck(addedTags, 'id'), true);
			return addedTags;
		},

		/**
		 * CREATE
		 *   Request Payload: {"name":"hello"}
		 *   Response: {
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }
		 */
		onCreateResponse: function (tag) {
			if (!(tag && tag.id)) {
				throw new Error('Tag could not be created.');
			}

			this.tags.push(tag);

			this.removeNewSuggestionByName(tag.name);
			this.onAddResponse(tag);

			return tag;
		},

		/**
		 * A listener for server response from adding a tag to a keep.
		 *
		 * ADD
		 *   Request Payload: {
		 *     url: "my.keep.com"
		 *   }
		 *   Response: {
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }
		 */
		onAddResponse: function (tag) {
			log('onAddResponse', tag);

			var tagId = tag.id;

			this.tagsAdded[tagId] = true;

			this.removeSuggestionById(tagId);

			return this.addTag$(tag);
		},

		/**
		 * A listener for server response from removing a tag from a keep.
		 *
		 * REMOVE
		 *   Request Payload: {
		 *     url: "my.keep.com"
		 *   }
		 *   Response: {}
		 */
		onRemoveResponse: function (tagId, response) {
			log('onRemoveResponse', response);

			return this.removeTag$ById(tagId);
		},

		/**
		 * A listener for server response from removing a tag from a keep.
		 *
		 * REMOVE
		 *   Request Payload: {
		 *     url: "my.keep.com"
		 *   }
		 *   Response: {}
		 */
		onClearTagsResponse: function () {
			log('onClearTagsResponse');

			this.clearTags$();
			this.tagsAdded = {};

			return;
		},

		//
		// Key Handlers
		//

		/**
		 * Currently highlighted suggestion.
		 *
		 * @property {jQuery} currentSuggestion - Currently highlighted suggestion.
		 */
		currentSuggestion: null,

		/**
		 * Navigates and highlight a tag suggestion.
		 * Returns a newly highlighted suggestion.
		 *
		 * @param {string} dir - A direction to navigate
		 *
		 * @return {jQuery} newly highlighted suggestion
		 */
		navigate: function (dir) {
			var $prev = this.currentSuggestion,
				$next = null;
			switch (dir) {
			case 'up':
				if ($prev) {
					$next = $prev.prev();
				}
				else {
					$next = this.$suggest.children(':last');
				}
				break;
			case 'down':
				if ($prev) {
					$next = $prev.next();
				}
				else {
					$next = this.$suggest.children(':first');
				}
				break;
			default:
				return;
			}
			this.ignoreMouseover = true;
			return this.navigateTo($next, dir);
		},

		navigateTo: function ($suggestion, src) {
			if ($suggestion === 'first' || $suggestion === 'last') {
				$suggestion = this.$suggest.children(':' + $suggestion);
			}

			if (!($suggestion && $suggestion.length)) {
				$suggestion = null;
			}

			var $prev = this.currentSuggestion;
			this.currentSuggestion = $suggestion;

			if ($prev) {
				$prev.removeClass('kifi-focus');
			}

			if ($suggestion) {
				$suggestion.addClass('kifi-focus');

				if (src !== 'mouseover') {
					this.scrolledIntoViewLazy($suggestion[0], 10);
				}
			}
		},

		scrolledIntoViewLazy: function (el, padding) {
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
				view.scrollTop = elemBottom + padding - viewHeight;
			}
			else if (elemTop < viewTop) {
				view.scrollTop = elemTop - padding;
			}
		},

		/**
		 * Selects and add a highlighted tag suggestion.
		 *
		 * @param {jQuery} [$suggestion] - jQuery object to select
		 * @param {string} trigger - A triggering user action
		 */
		select: function ($suggestion, trigger) {
			if (!$suggestion) {
				$suggestion = this.currentSuggestion;
				if (!$suggestion) {
					var name = this.getInputValue();
					if (name) {
						var index = this.indexOfTagByName(name);
						if (index === -1) {
							return this.createTag(name, trigger);
						}

						return this.addTagById(this.tags[index].id, null, trigger);
					}
					return null;
				}
			}

			var data = this.getData($suggestion),
				id = data.id;
			if (id) {
				return this.addTagById(id, $suggestion, trigger);
			}

			return this.createTag(data.name, trigger);
		},

		//
		// TEMPLATE RENDERERS
		//

		/**
		 * Renders and returns a tag box html.
		 *
		 * @return {string} tag box html
		 */
		renderContainer: function () {
			return win.render('html/keeper/message_options');
		},

		/**
		 * Renders and returns a tag suggestion html for a given tag item.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {string} tag suggestion html
		 */
		renderTagSuggestionHtml: function (tag) {
			return win.render('html/keeper/tag-suggestion', tag);
		},

		/**
		 * Renders and returns html for a given tag items.
		 *
		 * @param {Object[]} tags - A list of tag items
		 *
		 * @return {string} html
		 */
		renderTagSuggestionsHtml: function (tags) {
			return (tags && tags.length) ? tags.map(this.renderTagSuggestionHtml, this).join('') : '';
		},

		/**
		 * Renders and returns a new tag suggestion html for a given tag name.
		 *
		 * @param {string} name - A tag name
		 *
		 * @return {string} new tag suggestion html
		 */
		renderNewTagSuggestionHtml: function (name) {
			return win.render('html/keeper/tag-new', {
				name: name
			});
		},

		/**
		 * Renders and returns a tag item.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {string} tag html
		 */
		renderTagHtml: function (tag) {
			return win.render('html/keeper/tagbox-tag', tag);
		},

		//
		// EVENT LISTENERS
		//

		/**
		 * On click listener for a tag suggestion.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickSuggestion: function (e) {
			var $suggestion = $(e.target).closest('.kifi-tagbox-suggestion'),
				tagId = this.getData($suggestion, 'id');
			this.addTagById(tagId, $suggestion, this.getClickInfo('autocomplete'));
		},

		/**
		 * On click listener for a new tag suggestion.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickNewSuggestion: function (e) {
			var $suggestion = $(e.target).closest('.kifi-tagbox-new'),
				tagName = this.getData($suggestion, 'name');
			this.createTag(tagName, this.getClickInfo('new'));
		},

		/**
		 * On mouseover listener for suggestion box.
		 *
		 * @param {Object} event - A mouseover event object
		 */
		onMouseoverSuggestion: function (e) {
			if (this.ignoreMouseover) {
				this.ignoreMouseover = false;
				return;
			}

			var $target = $(e.target),
				$suggestion = $target.closest('.kifi-tagbox-suggestion');
			if (!$suggestion.length) {
				$suggestion = $target.closest('.kifi-tagbox-new');
				if (!$suggestion.length) {
					return;
				}
			}
			this.navigateTo($suggestion, 'mouseover');
		},

		/**
		 * On click listener for removing tag.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickRemoveTag: function (e) {
			var $tag = $(e.target).closest('.kifi-tagbox-tag'),
				tagId = this.getData($tag, 'id');
			this.removeTagById(tagId, this.getClickInfo('X'));
		},

		/**
		 * Clears all added tags from current keep.
		 *
		 * @param {string} trigger - A triggering user action
		 *
		 * @return {Object} A deferred promise object
		 */
		clearTags: function (trigger) {
			var deferred = this.requestClearAll()
				.then(this.onClearTagsResponse.bind(this))
				.fail(this.logError.bind(this));

			this.logEvent('clearTags', {
				trigger: trigger
			});

			return deferred;
		},

		//
		// OTHER APIS
		//

		/**
		 * Whether a tag box is active and visible
		 *
		 * @property {boolean}
		 */
		active: false,

		/**
		 * Shows a tag box.
		 *
		 * @param {jQuery} $slider - A slider jQuery object
		 * @param {string} trigger - A triggering user action
		 */
		show: function ($slider, trigger) {
			this.$slider = $slider;
			this.construct(trigger);
		},

		/**
		 * Hides a tag box.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		hide: function (trigger) {
			this.destroy(trigger);
		},

		/**
		 * It toggles (shows/hides) a tag box.
		 *
		 * @param {jQuery} $slider - A slider jQuery object
		 * @param {string} trigger - A triggering user action
		 */
		toggle: function ($slider, trigger) {
			if (this.active) {
				this.hide(trigger);
			}
			else {
				this.show($slider, trigger);
			}
		},

		//
		// HELPER FUNCTIONS
		//

		/**
		 * Returns a data from a jQuery element.
		 *
		 * @param {jQuery} $el - A jQuery element to get data from
		 * @param {string} [name] - Data name
		 *
		 * @return {*} A data value
		 */
		getData: function ($el, name) {
			if ($el.length) {
				var dataset = $el[0].dataset;
				if (dataset) {
					if (name == null) {
						return dataset;
					}
					if (name in dataset) {
						return dataset[name];
					}
				}
			}
			return null;
		},

		/**
		 * Logs a tagbox user event to the server.
		 *
		 * @param {string} name - A event type name
		 * @param {Object} obj - A event data
		 * @param {boolean} withUrls - Whether to include url
		 */
		logEvent: function (name, obj, withUrls) {
			if (obj) {
				if (!withUrls) {
					obj = win.withUrls(obj);
				}
			}
			log(name, obj);
			win.logEvent('slider', 'tagbox.' + name, obj || null);
		},

		/**
		 * Logs error.
		 *
		 * @param {Error} err - An error object
		 */
		logError: function (err) {
			log('Error', err, err.message, err.stack);
		},

		/**
		 * Returns a trigger string for event logging.
		 *
		 * @param {string} name - What is being clicked
		 * @param {string} target - What element is being clicked
		 *
		 * @return {string} A trigger string
		 */
		getClickInfo: function (name, target) {
			var res = 'click:' + name;
			if (target) {
				res += '@' + target;
			}
			return res;
		}

	};

})(jQuery, this);
