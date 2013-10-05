// @require scripts/lib/jquery.js
// @require scripts/lib/fuzzy-min.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/lib/q.min.js
// @require scripts/render.js
// @require scripts/livechange.js
// @require scripts/html/keeper/tagbox.js
// @require scripts/html/keeper/tag-suggestion.js
// @require scripts/html/keeper/tag-new.js
// @require scripts/html/keeper/tagbox-tag.js
// @require styles/keeper/tagbox.css

/**
 *
 * ---------------
 *     Tag Box    
 * ---------------
 *
 * Tag box is an UI component that lets you conveniently tag your keep
 * into existing and/or new collection(s).
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-02-2013
 *
 */

this.tagbox = (function ($, win) {
	'use strict';

	function onResize() {
		this.data('antiscroll').refresh();
	}

	function activateScroll(selector) {
		var $container = $(selector);
		$container.antiscroll({
			x: false
		});
		$(win).on('resize' + selector, onResize);
	}

	function deactivateScroll(selector) {
		$(win).off('resize' + selector);
	}

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
		 * A TagBox constructor
		 *
		 * Renders and initializes a tag box if there is no live tag box available.
		 *
		 * @constructor
		 */
		construct: function () {
			if (!this.$tagbox) {
				this.init();
			}
		},

		/**
		 * Renders and initializes a tag box.
		 */
		init: function () {
			this.initTagBox();
			this.initSuggest();
			this.initTagList();
			this.initInput();
			this.initCloseIcon();
			this.initPageTags();
			this.initTags();
			activateScroll('.kifi-tagbox-suggest');
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
					this.hide();
					e.preventDefault();
				}
			}

			function onClick(e) {
				if (!$(e.target).closest('.kifi-tagbox').length) {
					this.hide();
				}
			}

			return function () {
				var $tagbox = $(this.renderTagBoxHtml()).appendTo($('body'));
				this.$tagbox = $tagbox;

				var docKeydown = this.docKeydown = onKeydown.bind(this);
				$(document).keydown(docKeydown);

				//var docClick = this.docClick = onClick.bind(this);
				//$(document).click(docClick);

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
			var $input = this.$input = $inputbox.find('input.kifi-tagbox-input');

			this.addInputEvents();
			setTimeout(function () {
				$input.focus();
			}, 1);
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
					this.hide();
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
			var $tagList = this.$tagbox.find('.kifi-tagbox-tag-list');
			this.$tagList = $tagList;

			$tagList.on('click', '.kifi-tagbox-tag-remove', this.onClickRemoveTag.bind(this));

			return $tagList;
		},

		/**
		 * Makes a request to the server to get all tags owned by the user.
		 *
		 * @return {Object} A deferred promise object
		 */
		initTags: function () {
			log('initTags: get all tags');
			return this.requestTags()
				.then(this.setTags.bind(this))
				.then(this.updateSuggestion.bind(this))
				.fail(this.alertError.bind(this));
		},

		/**
		 * Makes a request to the server to get all tags on the page.
		 *
		 * @return {Object} A deferred promise object
		 */
		initPageTags: function () {
			log('initPageTags: get all tags by url');
			return this.requestTagsByUrl()
				.then(this.rebuildTagsAdded.bind(this))
				.then(this.updateTagList.bind(this))
				.then(this.updateSuggestion.bind(this))
				.fail(this.alertError.bind(this));
		},

		/**
		 * Destroys a tag box.
		 * It removes all event listeners and caches to elements.
		 */
		destroy: function () {
			if (this.$tagbox) {
				deactivateScroll('.kifi-tagbox-suggest');

				this.$input.remove();
				this.$inputbox.remove();
				this.$suggest.remove();
				this.$tagbox.remove();
				this.$tagList.remove();
				$(document).off('keydown', this.docKeydown);
				$(document).off('click', this.docClick);

				this.$tagbox = null;
				this.$inputbox = null;
				this.$input = null;
				this.$suggest = null;
				this.$tagList = null;
				this.docKeydown = null;
				this.docClick = null;
				this.tags = [];
				this.tagsAdded = {};
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
			if (tagId) {
				var tags = this.tags;
				for (var i = 0, l = tags.length; i < l; i++) {
					if (tagId === tags[i].id) {
						return i;
					}
				}
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
			if (name) {
				var tags = this.tags;
				for (var i = 0, l = tags.length; i < l; i++) {
					if (name === this.getNormalizedTagName(tags[i])) {
						return i;
					}
				}
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
		filterTags: (function () {
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
				if (!tags) {
					tags = this.tags;
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
			return (tags || this.tags).filter(this.unaddedTagFilter, this);
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
		 * Given an input string to match against,
		 * it rerenders tag suggestions.
		 *
		 * @param {string} text - An input string to match against
		 */
		suggest: function (text) {
			var tags = this.tags;
			tags = this.filterOutAddedTags(tags);
			tags = this.filterTags(text, tags);

			var html = tags.map(this.renderTagSuggestionHtml, this).join('');
			var $suggest = this.$suggest;
			$suggest.html(html);

			if (text.trim() && this.indexOfTagByName(text) === -1) {
				$suggest.append(this.renderNewTagSuggestionHtml(text));
			}

			this.updateSuggestedClass();

			this.navigateTo('first');
		},

		/**
		 * Updates suggestion according to the current states (tags + input).
		 *
		 * @param {string} tags - A list of tags to render added
		 */
		updateTagList: function (tags) {
			var html = tags.map(this.renderTagHtml, this).join('');
			this.$tagList.html(html);

			this.$tagbox.toggleClass('tagged', tags.length ? true : false);
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
			return tagId in this.tagsAdded;
		},

		/**
		 * Creates a new tag for user.
		 * It sends a request to server to create a tag and returns a deferred object.
		 *
		 * @param {string} name - A new tag name
		 *
		 * @return {Object} A deferred promise object
		 */
		createTag: function (name) {
			log('createTag: create a tag', name);
			return this.requestCreateTagByName(name)
				.then(this.onCreateResponse.bind(this))
				.fail(this.alertError.bind(this));
		},

		/**
		 * Adds a tag to the current keep's tag list.
		 * It sends a request to server to add a tag and returns a deferred object.
		 *
		 * @param {string} tagId - A tag id
		 *
		 * @return {Object} A deferred promise object
		 */
		addTagById: function (tagId) {
			var $el = this.getTag$ById(tagId);
			if ($el.length) {
				log('addTagById: tag already added. tagId=' + tagId);
				return this.promiseSuccess($el);
			}

			log('addTagById: request to server. tagId=' + tagId);
			return this.requestAddTagById(tagId)
				.then(this.onAddResponse.bind(this, tagId))
				.fail(this.alertError.bind(this));
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
				return this.promiseSuccess(false);
			}

			log('removeTagById: request to server. tagId=' + tagId);
			return this.requestRemoveTagById(tagId)
				.then(this.onRemoveResponse.bind(this, tagId))
				.fail(this.alertError.bind(this));
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
				this.$tagbox.addClass('tagged');
				$tag = $(html).appendTo(this.$tagList);
			}
			return $tag;
		},

		/**
		 * Updates (add/remove) 'tagged' class of the tagbox.
		 */
		updateTaggedClass: function () {
			var add = this.$tagList.children().length ? true : false;
			this.$tagbox.toggleClass('tagged', add);
		},

		/**
		 * Updates (add/remove) 'suggested' class of the tagbox.
		 */
		updateSuggestedClass: function () {
			var add = (this.getInputValue() || this.$suggest.children().length) ? true : false;
			this.$tagbox.toggleClass('suggested', add);
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
				this.updateTaggedClass();
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
		setTags: function (tags) {
			this.tags = tags;
			return tags;
		},

		/**
		 * GET_TAGS_BY_URL
		 *   Response: [{
		 *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
		 *     "name":"hello"
		 *   }]
		 */
		rebuildTagsAdded: function (tags) {
			var tagsAdded = this.tagsAdded = {};
			for (var i = 0, l = tags.length; i < l; i++) {
				tagsAdded[tags[i].id] = true;
			}
			return tags;
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

			return this.addTagById(tag.id);
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
			var view = el.offsetParent,
				viewTop = view.scrollTop,
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
				return this.addTagById(id);
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
			this.addTagById($suggestion.data('id'));
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
			var $tag = $(e.target).closest('.kifi-tagbox-tag');
			this.removeTagById($tag.data('id'));
		},

		//
		// OTHER APIS
		//

		/**
		 * Shows a tag box.
		 */
		show: function ( /*$slider*/ ) {
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
			this.alert('Error: ' + err.message);
		},

		/**
		 * Alerts user for a message.
		 *
		 * @param {string} msg - A message to display
		 */
		alert: function (msg) {
			win.alert(msg);
		},

		/**
		 * Promises a failure and returns a deferred promise object.
		 *
		 * @param {*} data - A data
		 *
		 * @return {Object} A deferred promise object
		 */
		promiseSuccess: function (data) {
			var deferred = Q.defer();
			setTimeout(function () {
				deferred.resolve(data);
			}, 1);
			return deferred.promise;
		},

		/**
		 * Promises a failure and returns a deferred promise object.
		 *
		 * @param {string} msg - An error message
		 *
		 * @return {Object} A deferred promise object
		 */
		promiseFail: function (msg) {
			var deferred = Q.defer();
			setTimeout(function () {
				deferred.reject(new Error(msg));
			}, 1);
			return deferred.promise;
		}
	};

})(jQuery, this);
