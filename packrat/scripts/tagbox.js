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
			this.$tagbox = $(this.renderTagBoxHtml()).appendTo($('body'));
			this.initSuggest();
			this.initTagList();
			this.initInput();
			this.initCloseIcon();
			this.initTags();
			this.initPageTags();
			activateScroll('.kifi-tagbox-suggest');
		},

		/**
		 * Initializes a input box inside a tag box.
		 * Finds and caches input elements.
		 * Add event listeners to the input element.
		 */
		initInput: function () {
			var $inputbox = this.$inputbox = this.$tagbox.find('.kifi-tagbox-input-box');
			this.$input = $inputbox.find('input.kifi-tagbox-input');

			this.addInputEvents();
		},

		/**
		 * Add event listeners to the input element.
		 * This is called inside {@link initInput}
		 *
		 * @see initInput
		 */
		addInputEvents: (function () {
			function onLiveChange(e) {
				var text = e.value;
				text = text.trim();

				this.$inputbox.toggleClass('empty', !text);

        this.suggest(text);
			}

			function onFocus() {
				$(this).closest('.kifi-tagbox-input-box').addClass('focus');
			}

			function onBlur() {
				$(this).closest('.kifi-tagbox-input-box').removeClass('focus');
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
				.then(this.onGetTagsResponse.bind(this))
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
				.then(this.onGetTagsByUrlResponse.bind(this))
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

				this.$tagbox = null;
				this.$inputbox = null;
				this.$input = null;
				this.$suggest = null;
				this.$tagList = null;
				this.tagsAdded = null;
			}
		},

		/**
		 * Returns an index of a tag with the given id.
		 * Returns -1 if not found.
		 *
		 * @param {string} id - An tag id to search for
		 *
		 * @return {number} An index of a tag. -1 if not found.
		 */
		indexOfTagById: function (id) {
			if (id) {
				var tags = this.tags;
				for (var i = 0, l = tags.length; i < l; i++) {
					if (id === tags[i].id) {
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
					if (name === this.normalizeTagNameForSearch(tags[i].name)) {
						return i;
					}
				}
			}
			return -1;
		},

		/**
		 * Returns a tag item with the given tag id.
		 * Returns null if not found.
		 *
		 * @param {string} id - An tag id to search for
		 *
		 * @return {Object} A tag item. null if not found.
		 */
		getTagById: function (id) {
			var index = this.indexOfTagById(id);
			return index === -1 ? null : this.tags[index];
		},

		/**
		 * Returns a tag item with the given tag name.
		 * Returns null if not found.
		 *
		 * @param {string} name - An tag name to search for
		 *
		 * @return {Object} A tag item. null if not found.
		 */
		getTagByName: function (name) {
			var index = this.indexOfTagByName(name);
			return index === -1 ? null : this.tags[index];
		},

		/**
		 * Returns an id of a tag with the given tag name.
		 * Returns null if not found.
		 *
		 * @param {string} name - An tag name to search for
		 *
		 * @return {string} A tag id. null if not found.
		 */
		getTagIdByName: function (name) {
			var item = this.getTagByName(name);
			return item && item.id;
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
				extract: function (item) {
					return item.name;
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
			return !this.isTagAdded(tag.name);
		},

		/**
		 * Returns current input value (trimmed).
		 *
		 * @return {string} current input value (trimmed).
		 */
		getInputValue: function () {
			var text = this.$input.val();
			return text && text.trim() || '';
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
			this.emptySuggestions();

			var tags = this.tags;
			tags = this.filterOutAddedTags(tags);
			tags = this.filterTags(text, tags);

			var hasMatch = tags.length ? true : false;
			if (hasMatch) {
				this.renderSuggestions(tags);
			}

			if (this.indexOfTagByName(text) === -1) {
				this.suggestNew(text);
			}

			this.updateSuggestedClass();
		},

		/**
		 * Empties suggestion list.
		 */
		emptySuggestions: function () {
			this.$suggest.empty();
		},

		/**
		 * Renders and appends suggestions for given tags.
		 *
		 * @param {Object[]} tags - An array of tag items to suggest
		 */
		renderSuggestions: function (tags) {
			tags.forEach(this.renderSuggestion, this);
		},

		/**
		 * Renders and appends a suggestion for a given tag item.
		 *
		 * @param {Object} item - A tag object
		 * @param {string} item.id - A tag id
		 * @param {string} item.name - A tag name
		 *
		 * @return {jQuery} A jQuery object for the suggestion
		 */
		renderSuggestion: function (item) {
			return this.appendSuggestion(this.renderTagSuggestionHtml(item));
		},

		/**
		 * Renders and appends a new tag suggestion for a given name.
		 *
		 * @param {string} name - A new tag name
		 *
		 * @return {jQuery} A jQuery object for the new suggestion
		 */
		suggestNew: function (name) {
			return this.appendSuggestion(this.renderNewTagSuggestionHtml(name));
		},

		/**
		 * Removes a suggestion entry from a suggestion box with the given id.
		 *
		 * @param {string} id - A tag id to remove
		 *
		 * @return {boolean} Whether an element has been removed
		 */
		removeSuggestionById: function (id) {
			if (id) {
				var $suggetion = this.$suggest.find('.kifi-tagbox-suggestion[data-id=' + id + ']');
				if ($suggetion.length) {
					$suggetion.remove();
					return true;
				}
			}
			return false;
		},

		/**
		 * Removes a suggestion entry from a suggestion box with the given name.
		 *
		 * @param {string} name - A tag name to remove
		 *
		 * @return {boolean} Whether an element has been removed
		 */
		removeSuggestionByName: function (name) {
			return this.removeSuggestionById(this.getTagIdByName(name));
		},

		/**
		 * Removes a suggestion entry from a suggestion box with the given name.
		 *
		 * @param {string} name - A tag name to remove
		 *
		 * @return {boolean} Whether an element has been removed
		 */
		removeNewSuggestionByName: function (name) {
			if (name) {
				var $suggetion = this.$suggest.find('.kifi-tagbox-new[data-name=' + name + ']');
				if ($suggetion.length) {
					$suggetion.remove();
					return true;
				}
			}
			return false;
		},

		/**
		 * Appends a html to suggestion box.
		 *
		 * @param {string} html - A html string to append to suggest list
		 *
		 * @return {jQuery} A jQuery object for the appended html
		 */
		appendSuggestion: function (html) {
			return $(html).appendTo(this.$suggest);
		},

		/**
		 * Tests whether a tag is added to a keep.
		 *
		 * @param {string} name - A tag name
		 *
		 * @return {boolean} Whether a tag is already added to a keep
		 */
		isTagAdded: function (name) {
			name = this.normalizeTagNameForSearch(name);
			var tagsAdded = this.tagsAdded;
			return tagsAdded.hasOwnProperty(name) && (tagsAdded[name] ? true : false);
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
		 * @param {string} name - A tag name to add
		 *
		 * @return {Object} A deferred promise object
		 */
		addTag: function (name) {
			if (this.isTagAdded(name)) {
				log('addTag: already added', name);
				return this.promiseFail('tag already added');
			}

			var tag = this.getTagByName(name);
			if (!tag) {
				log('addTag: tag not found. create new tag', name);
				return this.createTag(name);
			}

			log('addTag: tag found. add it to a keep', name);
			var tagId = tag.id;
			return this.requestAddTagById(tagId)
				.then(this.onAddResponse.bind(this, tagId))
				.fail(this.alertError.bind(this));
		},

		/**
		 * Removes a tag from current keep's tag list.
		 * It sends a request to server to remove a tag and returns a deferred object.
		 *
		 * @param {string} name - A tag name to remove
		 *
		 * @return {Object} A deferred promise object
		 */
		removeTag: function (name) {
			if (!this.isTagAdded(name)) {
				log('removeTag: tag is not added.', name);
				return this.promiseFail('tag is not added.');
			}

			var tag = this.getTagByName(name);
			if (!tag) {
				log('removeTag: tag not found.', name);
				return this.promiseFail('tag not found.');
			}

			log('removeTag: tag found. remove it from a keep', name);
			var tagId = tag.id;
			return this.requestRemoveTagById(tagId)
				.then(this.onRemoveResponse.bind(this, tagId))
				.fail(this.alertError.bind(this));
		},

		/**
		 * Finds and returns jQuery object for a tag with the given id
		 *
		 * @param {string} id - A tag id
		 *
		 * @return {jQuery} jQuery object for a tag with the given id
		 */
		findTag$ById: function (id) {
			if (id) {
				var $tag = this.$tagList.find('.kifi-tagbox-tag[data-id=' + id + ']');
				if ($tag.length) {
					return $tag;
				}
			}
			return null;
		},

		/**
		 * Renders and adds a tag element if not already added.
		 *
		 * @param {Object} tag - A tag item
		 *
		 * @return {jQuery} A jQuery object for tag element
		 */
		addTag$: function (tag) {
			var $tag = this.findTag$ById(tag.id);
			if (!$tag) {
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
		 * @param {string} id - A tag id
		 *
		 * @return {boolean} Whether a tag was found and removed
		 */
		removeTag$ById: function (id) {
			var $tag = this.findTag$ById(id);
			if ($tag.length) {
				$tag.remove();
				this.updateTaggedClass();
				return true;
			}
			return false;
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
		 * @param {string} id - A tag id
		 *
		 * @return {Object} A deferred promise object
		 */
		requestAddTagById: function (id) {
			return this.request('add_tag', id, 'Could not add tag, "' + id + '"');
		},

		/**
		 * Makes a request to the server to remove a tag from a keep.
		 * Returns a promise object.
		 *
		 * @param {string} id - A tag id
		 *
		 * @return {Object} A deferred promise object
		 */
		requestRemoveTagById: function (id) {
			return this.request('remove_tag', id, 'Could not remove tag, "' + id + '"');
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
		onGetTagsResponse: function (tags) {
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
		onGetTagsByUrlResponse: function (tags) {
			//this.tags = tags;
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

			var name = tag.name;

			this.removeNewSuggestionByName(name);

			return this.addTag(name);
		},

		/**
		 * A listener for server response from adding a tag to a keep.
		 * 
		 * ADD
		 *   Request Payload: {
		 *     "collectionId":"f033afe4-bbb9-4609-ab8b-3e8aa968af21",
		 *     "keeps":[{
		 *       "title":"Use JSDoc: Index",
		 *       "url":"http://usejsdoc.org/index.html"
		 *     }]
		 *   }
		 *   Response: {
		 *     "keeps": [{
		 *       "id":"220c1ac7-6644-477f-872b-4088988d7810",
		 *       "title":"Use JSDoc: Index",
		 *       "url":"http://usejsdoc.org/index.html",
		 *       "isPrivate":false
		 *     }],
		 *     "addedToCollection":1
		 *   }
		 */
		onAddResponse: function (tagId, response) {
			log('onAddResponse', response);

			if (!response.addedToCollection) {
				throw new Error('Tag could not be added.');
			}

			var tag = this.getTagById(tagId);
			if (!tag) {
				throw new Error('Tag not found.');
			}

			var name = this.normalizeTagNameForSearch(tag.name);
			this.tagsAdded[name] = tag;

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

			var tag = this.getTagById(tagId);
			if (!tag) {
				throw new Error('Tag not found.');
			}

			var name = this.normalizeTagNameForSearch(tag.name);
			delete this.tagsAdded[name];

			//this.addSuggestionById(tagId);

			return this.removeTag$ById(tagId);
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
			log('onClickSuggestion');
			var $suggestion = $(e.target).closest('.kifi-tagbox-suggestion');
			this.addTag($suggestion.data('name'));
		},

		/**
		 * On click listener for a new tag suggestion.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickNewSuggestion: function (e) {
			log('onClickNewSuggestion');
			var $suggestion = $(e.target).closest('.kifi-tagbox-new');
			this.createTag($suggestion.data('name'));
		},

		/**
		 * On click listener for removing tag.
		 *
		 * @param {Object} event - A click event object
		 */
		onClickRemoveTag: function (e) {
			log('onClickRemoveTag');
			var $tag = $(e.target).closest('.kifi-tagbox-tag');
			this.removeTag($tag.data('name'));
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
		 * @param {string} msg - An error message
		 *
		 * @return {Object} A deferred promise object
		 */
		promiseFail: function (msg) {
			var deferred = Q.defer();
			setTimeout(function () {
				throw new Error(msg);
			}, 1);
			return deferred.promise;
		}
	};

})(jQuery, this);
