// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/message_participants.js
// @require scripts/html/keeper/message_header.js
// @require styles/keeper/message_header.css

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
		 * Whether this is initialized
		 *
		 * @property {boolean}
		 */
		initialized: false,

		plugins: [win.messageParticipants /*, win.messageMute*/ ],

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
				this.constructPlugins();
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
		initMessageHeader: function () {
			log('asetnuha')();
			log('asetnuha')();
			log('asetnuha')();
			log('asetnuha')();
			log('asetnuha')();
			log('asetnuha')();
			var $el = $(this.render()).insertBefore($(document.body));
			this.$el = $el;
			log($el)();
			log($el)();
			log($el)();
			log($el)();
			return $el;
		},

		/**
		 * Destroys a message header.
		 * It removes all event listeners and caches to elements.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		destroy: function (trigger) {
			if (this.initialized) {
				this.initialized = false;
				this.destroyPlugins();

				$(win.tile).css('transform', '');

				'$el'.split(',').forEach(function (name) {
					var $el = this[name];
					if ($el) {
						$el.remove();
						this[name] = null;
					}
				}, this);

				this.status = {};

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

		find: function (sel) {
			var $el = this.$el;
			return $el ? $el.find(sel) : null;
		},

		on: function () {
			var $el = this.$el;
			return $el && $el.on.apply($el, arguments);
		},

		status: {},

		setStatus: function (name, isSet) {
			isSet = Boolean(isSet);
			this.$el.toggleClass('kifi-' + name, isSet);
			this.status[name] = isSet;
		},

		renderStatusClasses: function (status) {
			if (!status) {
				status = this.status;
			}
			return Object.keys(status).reduce(function (list, name) {
				if (status[name]) {
					list.push('kifi-' + name);
				}
				return list;
			}, []).join(' ');
		},

		/**
		 * Renders and returns a html.
		 *
		 * @return {string} A rendered html
		 */
		render: function () {
			return win.render('html/keeper/message_header', {
				status: this.renderStatusClasses(),
				buttons: this.renderPlugins('button'),
				options: this.renderPlugins('option'),
				content: this.renderPlugins('content')
			});
		},

		constructPlugins: function () {
			this.plugins.forEach(function (plugin) {
				plugin.parent = this;
				plugin.construct();
			}, this);
		},

		renderPlugins: function (compName) {
			return this.plugins.map(function (plugin) {
				var html = plugin.render(compName);
				return html == null ? '' : html;
			}).join('');
		},

		initPlugins: function () {
			return this.plugins.map(function (plugin) {
				plugin.init();
			});
		},

		destroyPlugins: function () {
			this.plugins.forEach(function (plugin) {
				return plugin.destroy();
			}, this);
		},

		/**
		 * Logs a user event to the server.
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
			log(name, obj)();
			win.logEvent('slider', 'message_header.' + name, obj || null);
		},

		/**
		 * Logs error.
		 *
		 * @param {Error} err - An error object
		 */
		logError: function (err) {
			log('Error', err, err.message, err.stack)();
		}

	};

})(jQuery, this);
