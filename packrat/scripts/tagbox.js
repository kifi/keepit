// @require scripts/lib/jquery.js
// @require scripts/lib/fuzzy-min.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/lib/q.min.js
// @require scripts/render.js
// @require scripts/util.js
// @require scripts/livechange.js
// @require scripts/html/keeper/tagbox.js
// @require scripts/html/keeper/tag-suggestion.js
// @require scripts/html/keeper/tag-new.js
// @require scripts/html/keeper/tagbox-tag.js
// @require styles/keeper/tagbox.css
// @require styles/animate-custom.css

/**
 * ---------------
 *     Tag Box    
 * ---------------
 *
 * Tag box is an UI component that lets you conveniently tag your keep
 * into existing and/or new collection(s).
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-02-2013
 */

this.tagbox = (function ($, win) {
	'use strict';

	var util = win.util;

	function log() {
		return win.log.apply(win, arguments)();
	}

	// receive
	/*
  api.port.on({
		create_tag: function (response) {
			if (response.success) {}
		}
	});
  */

	return {
		/**
		 * An array containing user's all tags
		 *
		 * @property {Object[]} tagbox.tags - User's tags
		 */
		tags: [],

		/**
		 * Cache for added tags
		 *
		 * @property {Object} tagsAdded
		 */
		tagsAdded: {},

		/**
		 * Cache for tag names being created
		 *
		 * @property {Object} tagsBeingCreated
		 */
		tagsBeingCreated: {},

		/**
		 * Cache for busy tag ids waiting for server response
		 *
		 * @property {Object} busyTags
		 */
		busyTags: {},

		/**
		 * A TagBox constructor
		 *
		 * Renders and initializes a tag box if there is no live tag box available.
		 *
		 * @constructor
		 */
		construct: function () {
			log('tagbox:construct');
			if (!this.$tagbox) {
				this.init();
			}
		},

		/**
		 * Renders and initializes a tag box.
		 */
		init: function () {
			log('tagbox:init');
			this.active = true;
			this.initTagBox();
			this.initSuggest();
			this.initTagList();
			this.initInput();
			this.initCloseIcon();
			this.initClearAll();
			this.initTags();
			this.initScroll();
		},

		/**
		 * Finds, initializes, and caches a suggestion box.
		 *
		 * @return {jQuery} A jQuery object for suggestion list
		 */
		initTagBox: (function () {

			var KEY_ESC = 27;

			function onKeydown(e) {
				if (e.which === KEY_ESC) {
					log('tagbox:document.esc');
					this.hide();
					e.stopPropagation();
					e.stopImmediatePropagation();
					e.preventDefault();
				}
			}

			function onClick(e) {
				if (!$.contains(this.$tagbox[0], e.target)) {
					log('tagbox:clickout', e.target);
					this.hide();
				}
			}

			function addDocListeners() {
				if (this.$tagbox) {
					var $doc = $(document),
						onDocKeydown = this.onDocKeydown = onKeydown.bind(this),
						onDocClick = this.onDocClick = onClick.bind(this);

					this.$doc = $doc;
					$doc.on('keydown', onDocKeydown);
					$doc.on('click', onDocClick);
				}
			}

			return function () {
				//var $tagbox = $(this.renderTagBoxHtml()).appendTo(this.$slider);
				var $tagbox = $(this.renderTagBoxHtml()).appendTo($('body'));
				this.$tagbox = $tagbox;

				win.setTimeout(addDocListeners.bind(this), 50);

				return $tagbox;
			};
		})(),

		/**
		 * Initializes a input box inside a tag box.
		 * Finds and caches input elements.
		 * Add event listeners to the input element.
		 */
		initInput: function () {
			var $inputbox = this.$inputbox = this.$tagbox.find('.kifi-tagbox-input-box');
			this.$input = $inputbox.find('input.kifi-tagbox-input');

			this.addInputEvents();
			win.setTimeout(this.focusInput.bind(this), 1);
		},

		focusInput: function () {
			return this.$input && this.$input.focus();
		},

		/**
		 * Add event listeners to the input element.
		 * This is called inside {@link initInput}
		 *
		 * @see initInput
		 */
		addInputEvents: (function () {
			function onFocus() {
				$(this).closest('.kifi-tagbox-input-box').addClass('focus');
			}

			function onBlur() {
				$(this).closest('.kifi-tagbox-input-box').removeClass('focus');
			}

			var KEY_UP = 38,
				KEY_DOWN = 40,
				KEY_ENTER = 13,
				KEY_ESC = 27,
				KEY_TAB = 9;

			function onLiveChange(e) {
				var text = e.value;
				text = text.trim();

				this.$inputbox.toggleClass('empty', !text);

				this.suggest(text);
			}

			function onKeydown(e) {
				var preventDefault = false;
				switch (e.which) {
				case KEY_UP:
					this.navigate('up');
					preventDefault = true;
					break;
				case KEY_DOWN:
					this.navigate('down');
					preventDefault = true;
					break;
				case KEY_ENTER:
					this.select();
					this.setInputValue();
					preventDefault = true;
					break;
				case KEY_ESC:
					log('tagbox:input.esc', this.currentSuggestion);
					if (this.currentSuggestion) {
						this.navigateTo(null);
						e.stopPropagation();
						e.stopImmediatePropagation();
					}
					else {
						this.hide();
						e.stopPropagation();
						e.stopImmediatePropagation();
					}
					preventDefault = true;
					break;
				case KEY_TAB:
					this.select();
					this.setInputValue();
					preventDefault = true;
					break;
				}
				if (preventDefault) {
					e.preventDefault();
				}
			}

			return function () {
				var $input = this.$input;
				$input.on('focus', onFocus);
				$input.on('blur', onBlur);
				$input.livechange({
					on: onLiveChange,
					context: this,
					init: true
				});
				$input.on('keydown', onKeydown.bind(this));
			};
		})(),

		/**
		 * Add a close event listener to close button.
		 */
		initCloseIcon: function () {
			this.$tagbox.on('click', '.kifi-tagbox-close', this.hide.bind(this));
		},

		/**
		 * Finds, initializes, and caches a suggestion box.
		 *
		 * @return {jQuery} A jQuery object for suggestion list
		 */
		initSuggest: function () {
			var $suggest = this.$tagbox.find('.kifi-tagbox-suggest-inner');
			this.$suggest = $suggest;

			$suggest.on('click', '.kifi-tagbox-suggestion', this.onClickSuggestion.bind(this));
			$suggest.on('click', '.kifi-tagbox-new', this.onClickNewSuggestion.bind(this));
			$suggest.on('mouseover', this.onMouseoverSuggestion.bind(this));

			return $suggest;
		},

		/**
		 * Finds and caches a tag list container.
		 *
		 * @return {jQuery} A jQuery object for tag list
		 */
		initTagList: function () {
			var $tagList = this.$tagbox.find('.kifi-tagbox-tag-list-inner');
			this.$tagList = $tagList;

			$tagList.on('click', '.kifi-tagbox-tag-remove', this.onClickRemoveTag.bind(this));

			return $tagList;
		},

		/**
		 * Add a clear event listener to clear button.
		 */
		initClearAll: function () {
			this.$tagbox.on('click', '.kifi-tagbox-clear', this.clearTags.bind(this));
		},

		/**
		 * Makes a request to the server to get all tags owned by the user.
		 * Makes a request to the server to get all tags on the page.
		 *
		 * @return {Object} A deferred promise object
		 */
		initTags: function () {
			log('initTags: get all tags');
			Q.all([this.requestTags(), this.requestTagsByUrl()])
				.spread(this.onFetchTags.bind(this))
				.then(this.updateSuggestHeight.bind(this))
				.then(this.updateTagList.bind(this))
				.then(this.updateSuggestion.bind(this))
				.then(this.toggleLoading.bind(this, false))
				.then(this.focusInput.bind(this))
				.then(this.updateScroll.bind(this))
				.fail(function (err) {
				this.hide();
				throw err;
			}.bind(this))
				.fail(this.alertError.bind(this));
		},

		initScroll: function () {
			var $suggestWrapper = this.$tagbox.find('.kifi-tagbox-suggest');
			this.$suggestWrapper = $suggestWrapper;
			$suggestWrapper.antiscroll({
				x: false
			});

			var $tagListWrapper = this.$tagbox.find('.kifi-tagbox-tag-list');
			this.$tagListWrapper = $tagListWrapper;
			$tagListWrapper.antiscroll({
				x: false
			});

			var winResizeListener = this.winResizeListener = this.updateScroll.bind(this);
			$(win).on('resize.kifi-tagbox-suggest', winResizeListener);
		},

		updateScroll: function () {
			log('updateScroll');
			this.$tagListWrapper.data('antiscroll').refresh();
			this.$suggestWrapper.data('antiscroll').refresh();
		},

		/**
		 * Destroys a tag box.
		 * It removes all event listeners and caches to elements.
		 */
		destroy: function () {
			log('tagbox:destroy');
			if (this.$tagbox) {
				this.active = false;
				log('tagbox:destroy-inner');
				$(win).off('resize.kifi-tagbox-suggest', this.winResizeListener);

				'$input,$inputbox,$suggest,$suggestWrapper,$tagbox,$tagList,$tagListWrapper'.split(',').forEach(function (name) {
					var $el = this[name];
					if ($el) {
						$el.remove();
						this[name] = null;
					}
				}, this);

				var $doc = this.$doc;
				if ($doc) {
					$doc.off('keydown', this.onDocKeydown);
					$doc.off('click', this.onDocClick);
				}

				this.$doc = null;
				this.onDocKeydown = null;
				this.onDocClick = null;
				this.$slider = null;
				this.tags = [];
				this.tagsAdded = {};
				this.tagsBeingCreated = {};
				this.busyTags = {};
			}
		},

		/**
		 * Returns an index of a tag with the given id.
		 * Returns -1 if not found.
		 *
		 * @param {string} tagId - An tag id to search for
		 *
		 * @return {number} An index of a tag. -1 if not found.
		 */
		indexOfTagById: function (tagId) {
			var tags = this.tags;
			if (tagId && tags) {
				return util.keyOf(tags, function (tag) {
					return tag.id === tagId;
				});
			}
			return -1;
		},

		/**
		 * Returns an index of a tag with the given tag name.
		 * Returns -1 if not found.
		 *
		 * @param {string} name - An tag name to search for
		 *
		 * @return {number} An index of a tag. -1 if not found.
		 */
		indexOfTagByName: function (name) {
			name = this.normalizeTagNameForSearch(name);
			var tags = this.tags;
			if (name && tags) {
				return util.keyOf(tags, function (tag) {
					return this.getNormalizedTagName(tag) === name;
				}, this);
			}
			return -1;
		},

		/**
		 * Returns a normalized tag name for a tag item.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {string} A normalized tag name
		 */
		getNormalizedTagName: function (tag) {
			return tag.normName || (tag.normName = this.normalizeTagNameForSearch(tag.name));
		},

		/**
		 * Returns a tag item with the given tag id.
		 * Returns null if not found.
		 *
		 * @param {string} tagId - An tag id to search for
		 *
		 * @return {Object} A tag item. null if not found.
		 */
		getTagById: function (tagId) {
			var index = this.indexOfTagById(tagId);
			return index === -1 ? null : this.tags[index];
		},

		/**
		 * Normalizes a tag name and returns the result.
		 *
		 * @param {string} name - A tag name to normalize
		 *
		 * @return {string} A normalized tag name
		 */
		normalizeTagName: function (name) {
			return name && name.trim().replace(/\s+/g, ' ');
		},

		/**
		 * Normalizes a tag name for search (case insensitive) and returns the result.
		 *
		 * @param {string} name - A tag name to normalize
		 *
		 * @return {string} A normalized tag name
		 */
		normalizeTagNameForSearch: function (name) {
			return name && this.normalizeTagName(name).toLowerCase();
		},

		/**
		 * Given an input string to match against,
		 * it (fuzzy) filters and returns a new array of matched tags.
		 *
		 * @param {string} text - An input string to match against
		 * @param {Object[]} [tags] - An array of tags to search from
		 *
		 * @return {Object[]} A new array of filtered tags
		 */
		filterTagsByText: (function () {
			var options = {
				pre: '<b>',
				post: '</b>',
				extract: function (tag) {
					return tag.name;
				}
			};

			function extractData(match) {
				return {
					id: match.original.id,
					name: match.original.name,
					html: match.string
				};
			}

			return function (text, tags) {
				if (!(tags || (tags = this.tags))) {
					return [];
				}
				if (text) {
					return win.fuzzy.filter(text, tags, options).map(extractData);
				}
				return tags;
			};
		})(),

		/**
		 * Filters out added tags and returns a new array of unadded tags.
		 *
		 * @param {Object[]} tags - An array of tags to apply filter to
		 *
		 * @return {Object[]} A new array of filtered tags
		 */
		filterOutAddedTags: function (tags) {
			if (!(tags || (tags = this.tags))) {
				return [];
			}
			return tags.filter(this.unaddedTagFilter, this);
		},

		/**
		 * Returns true if a tag has not been added to a keep, false otherwise.
		 *
		 * @param {Object} tag - A tag to test
		 *
		 * @return {boolean} true if a tag has not been added to a keep, false otherwise.
		 */
		unaddedTagFilter: function (tag) {
			return !this.isTagAddedById(tag.id);
		},

		/**
		 * Returns current input value (trimmed).
		 *
		 * @return {string} current input value (trimmed).
		 */
		getInputValue: function () {
			var text = this.$input && this.$input.val();
			return text && text.trim() || '';
		},

		/**
		 * Sets a value to the input.
		 *
		 * @param {string} new input value.
		 *
		 * @return {jQuery} An jQuery object of the input
		 */
		setInputValue: function (val) {
			var $input = this.$input || null;
			if ($input) {
				$input.val(val || '');
				$input.focus();
			}
			return $input;
		},

		/**
		 * Updates suggestion according to the current states (tags + input).
		 */
		updateSuggestion: function () {
			this.suggest(this.getInputValue());
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
			return $tagbox.addClass.apply($tagbox, arguments);
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
			return $tagbox.removeClass.apply($tagbox, arguments);
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
			return this.$tagbox.toggleClass(classname, add ? true : false);
		},

		/**
		 * Toggles a 'loading' class of the root element.
		 *
		 * @param {boolean} Whether to add or remove
		 *
		 * @return {jQuery} A jQuery object for the root element
		 */
		toggleLoading: function (loading) {
			return this.toggleClass('loading', loading);
		},

		/**
		 * Given an input string to match against,
		 * it rerenders tag suggestions.
		 *
		 * @param {string} text - An input string to match against
		 */
		suggest: function (text) {
			log('tagbox.suggest', text);
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

			this.navigateTo('first');
		},

		/**
		 * Updates suggestion according to the current states (tags + input).
		 */
		updateTagList: function () {
			var tags = this.getAddedTags(),
				html = tags.map(this.renderTagHtml, this).join('');
			this.$tagList.html(html);

			this.toggleClass('tagged', tags.length);
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
		 *
		 * @return {Object} A deferred promise object
		 */
		createTag: function (name, $suggestion) {
			if (this.indexOfTagByName(name) !== -1) {
				log('createTag: tag already exists. name=' + name);
				return null;
			}

			if (this.isTagBusy(name)) {
				log('createTag: tag is already being created. name=' + name);
				return null;
			}

			this.addTagBusy(name, $suggestion || this.getNewTagSuggestion$ByName(name));

			log('createTag: create a tag', name);
			return this.requestCreateAndAddTagByName(name)
				.then(this.onCreateResponse.bind(this))
				.fail(this.alertError.bind(this))
				.fin(this.removeTagBusy.bind(this, name));
		},

		/**
		 * Adds a tag to the current keep's tag list.
		 * It sends a request to server to add a tag and returns a deferred object.
		 *
		 * @param {string} tagId - A tag id
		 * @param {jQuery} [$suggestion] - jQuery object for suggestion
		 *
		 * @return {Object} A deferred promise object
		 */
		addTagById: function (tagId, $suggestion) {
			var $el = this.getTag$ById(tagId);
			if ($el.length) {
				log('addTagById: tag already added. tagId=' + tagId);
				return null;
			}

			if (this.isTagBusy(tagId)) {
				log('addTagById: tag is already being added. tagId=' + tagId);
				return null;
			}

			this.addTagBusy(tagId, $suggestion || this.getSuggestion$ById(tagId));

			log('addTagById: request to server. tagId=' + tagId);
			return this.requestAddTagById(tagId)
				.then(this.onAddResponse.bind(this, tagId))
				.fail(this.alertError.bind(this))
				.fin(this.removeTagBusy.bind(this, tagId));
		},

		/**
		 * Removes a tag from current keep's tag list.
		 * It sends a request to server to remove a tag and returns a deferred object.
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {Object} A deferred promise object
		 */
		removeTagById: function (tagId) {
			var $el = this.getTag$ById(tagId);
			if (!$el.length) {
				log('removeTagById: tag is not added. tagId=' + tagId);
				return null;
			}

			if (this.isTagBusy(tagId)) {
				log('removeTagById: tag is already being removed. tagId=' + tagId);
				return null;
			}

			this.addTagBusy(tagId, $el);

			log('removeTagById: request to server. tagId=' + tagId);
			return this.requestRemoveTagById(tagId)
				.then(this.onRemoveResponse.bind(this, tagId))
				.fail(this.alertError.bind(this))
				.fin(this.removeTagBusy.bind(this, tagId));
		},

		isTagBusy: function (tagId) {
			return tagId in this.busyTags;
		},

		addTagBusy: function (tagId, $el) {
			this.busyTags[tagId] = $el;
			$el.addClass('busy');
		},

		removeTagBusy: function (tagId) {
			var busyTags = this.busyTags,
				$el = busyTags[tagId];

			delete busyTags[tagId];

			$el.removeClass('busy');
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
				this.addClass('tagged');
				$tag = $(html).appendTo(this.$tagList);
        this.updateScroll();
			}
			return $tag;
		},

		/**
		 * Updates (add/remove) 'tagged' class of the tagbox.
		 */
		updateTaggedClass: function () {
			return this.toggleClass('tagged', this.$tagList.children().length);
		},

		/**
		 * Updates (add/remove) 'suggested' class of the tagbox.
		 */
		updateSuggestedClass: function () {
			return this.toggleClass('suggested', this.getInputValue() || this.$suggest.children().length);
		},

		/**
		 * Finds and removes a tag by its id
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {number} Number of elements removed
		 */
		removeTag$ById: function (tagId) {
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
						this.addClass('suggested');
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
						this.addClass('suggested');
					}
				}
				this.removeClass('tagged');
        this.updateScroll();
			}
			return len;
		},

		//
		// REQUESTS
		//

		/**
		 * Makes a request to the server to get all tags for the user.
		 * Returns a promise object.
		 * 
		 * @return {Object} A deferred promise object
		 */
		requestTags: function () {
			return this.request('get_tags', null, 'Could not load tags.');
		},

		/**
		 * Makes a request to the server to get all tags associated with the current page for the user.
		 * Returns a promise object.
		 * 
		 * @return {Object} A deferred promise object
		 */
		requestTagsByUrl: function () {
			return this.request('get_tags_by_url', null, 'Could not load tags for the page.');
		},

		/**
		 * Makes a request to the server to create a tag for the user.
		 * Returns a promise object.
		 *
		 * @param {string} name - A tag name
		 * 
		 * @return {Object} A deferred promise object
		 */
		requestCreateTagByName: function (name) {
			return this.request('create_tag', name, 'Could not create tag, "' + name + '"');
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
				if (result.success) {
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
		onFetchTags: function (tags, addedTags) {
			log('onFetchTags', tags, addedTags);
			this.setTags(tags);
			this.rebuildTagsAdded(addedTags);
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
			var height = util.minMax(32 * this.getTagCount(), 164, 265);
			this.$suggest.height(height);
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
			this.onAddResponse.bind(this, tag.id, tag);

			return tag;
		},

		/**
		 * A listener for server response from adding a tag to a keep.
		 * 
		 * ADD
		 *   Request Payload: {
		 *     url: "my.keep.com"
		 *   }
		 *   Response: {}
		 */
		onAddResponse: function (tagId, response) {
			log('onAddResponse', response);

			this.tagsAdded[tagId] = true;

			this.removeSuggestionById(tagId);

			var tag = this.getTagById(tagId);
			if (!tag) {
				throw new Error('Tag not found.');
			}

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

			delete this.tagsAdded[tagId];
			//this.addSuggestionById(tagId);

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
			return this.navigateTo($next, dir);
		},

		navigateTo: function ($suggestion) {
			if ($suggestion === 'first' || $suggestion === 'last') {
				$suggestion = this.$suggest.children(':' + $suggestion);
			}
			if (!($suggestion && $suggestion.length)) {
				$suggestion = null;
			}

			var $prev = this.currentSuggestion;
			this.currentSuggestion = $suggestion;

			if ($prev) {
				$prev.removeClass('focus');
			}

			if ($suggestion) {
				$suggestion.addClass('focus');
				this.scrolledIntoViewLazy($suggestion[0], 10);
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
		 */
		select: function ($suggestion) {
			if (!($suggestion || ($suggestion = this.currentSuggestion))) {
				return null;
			}

			var data = $suggestion.data(),
				id = data.id;
			if (id) {
				return this.addTagById(id, $suggestion);
			}

			return this.createTag(data.name);
		},

		//
		// TEMPLATE RENDERERS
		//

		/**
		 * Renders and returns a tag box html.
		 *
		 * @return {string} tag box html
		 */
		renderTagBoxHtml: function () {
			return win.render('html/keeper/tagbox');
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
			var $suggestion = $(e.target).closest('.kifi-tagbox-suggestion');
			this.addTagById($suggestion.data('id'), $suggestion);
		},

		/**
		 * On click listener for a new tag suggestion.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickNewSuggestion: function (e) {
			var $suggestion = $(e.target).closest('.kifi-tagbox-new');
			this.createTag($suggestion.data('name'));
		},

		/**
		 * On mouseover listener for suggestion box.
		 *
		 * @param {Object} event - A mouseover event object
		 */
		onMouseoverSuggestion: function (e) {
			var $target = $(e.target),
				$suggestion = $target.closest('.kifi-tagbox-suggestion');
			if (!$suggestion.length) {
				$suggestion = $target.closest('.kifi-tagbox-new');
				if (!$suggestion.length) {
					return;
				}
			}
			this.navigateTo($suggestion);
		},

		/**
		 * On click listener for removing tag.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickRemoveTag: function (e) {
			var tagId = $(e.target).closest('.kifi-tagbox-tag').data('id');
			this.removeTagById(tagId);
		},

		/**
		 * Clears all added tags from current keep.
		 *
		 * @return {Object} A deferred promise object
		 */
		clearTags: function () {
			return this.requestClearAll()
				.then(this.onClearTagsResponse.bind(this))
				.fail(this.alertError.bind(this));
		},

		//
		// OTHER APIS
		//

		/**
		 * Whether a tag bax is active and visible
		 *
		 * @property {boolean}
		 */
		active: false,

		/**
		 * Shows a tag box.
		 */
		show: function ($slider) {
			this.$slider = $slider;
			this.construct();
		},

		/**
		 * Hides a tag box.
		 */
		hide: function () {
			this.destroy();
		},

		/**
		 * It toggles (shows/hides) a tag box.
		 */
		toggle: function ($slider) {
			if (this.$tagbox) {
				this.hide();
			}
			else {
				this.show($slider);
			}
		},

		//
		// HELPER FUNCTIONS
		//

		/**
		 * Alerts user for an error.
		 *
		 * @param {Error} err - An error object
		 */
		alertError: function (err) {
			log('Error: ' + err.message);
			log(err.stack);
			this.alert('Error: ' + err.message);
		},

		/**
		 * Alerts user for a message.
		 *
		 * @param {string} msg - A message to display
		 */
		alert: function (msg) {
			win.alert(msg);
		}
	};

})(jQuery, this);
