// @require scripts/lib/jquery.js
// @require scripts/lib/underscore.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/lib/q.min.js
// @require scripts/render.js
// @require scripts/util.js
// @require scripts/scorefilter.js
// @require scripts/html/keeper/message_header.js
// @require styles/keeper/message_header.css
// @require styles/animate-custom.css

/**
 * ---------------------
 *    Message Header
 * ---------------------
 *
 * Message Header is an UI component that is a hub for plugins
 * related to current message context.
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-16-2013
 */

var messageHeader = this.messageHeader = (function ($, win) {
	'use strict';

	var util = win.util,
		Q = win.Q,
		_ = win._;

	// receive
	api.port.on({
		friends: function (friends) {}
	});

	api.onEnd.push(function () {
		messageHeader.destroy('api:onEnd');
		messageHeader = win.messageHeader = null;
	});

	return {

		/**
		 * Whether a tag box is initialized
		 *
		 * @property {boolean}
		 */
		initialized: false,

		/**
		 * A constructor of Message Header
		 *
		 * Renders and initializes a message header box if not already.
		 *
		 * @constructor
		 *
		 * @param {string} trigger - A triggering user action
		 */
		construct: function (trigger) {
			if (!this.initialized) {
				this.init(trigger);
			}
		},

		/**
		 * Renders and initializes a Message Header.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		init: function (trigger) {
			this.initialized = true;
			this.initMessageHeader();
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
		initMessageHeader: (function () {

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
				if (this.initialized) {
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

		initPlugins: function () {},

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
		 * Add a clear event listener to clear button.
		 */
		initOptionButton: function () {
			this.$tagbox.on('click', '.kifi-message-header-option-button', this.onClickOptions.bind(this));
		},

		onClickOptions: function () {
			this.toggleOptions(this.getClickInfo('clear'));
		},

		toggleOptions: function () {},

		/**
		 * Destroys a tag box.
		 * It removes all event listeners and caches to elements.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		destroy: function (trigger) {
			if (this.initialized) {
				this.initialized = false;

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

		/**
		 * Renders and returns a tag box html.
		 *
		 * @return {string} tag box html
		 */
		renderContainer: function () {
			return win.render('html/keeper/message_header');
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
