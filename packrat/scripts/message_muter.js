// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/kifi_util.js
// @require scripts/html/keeper/message_mute_option.js
// @require scripts/html/keeper/message_muted.js
// @require styles/keeper/message_mute.css

/**
 * -------------------
 *    Message Muter
 * -------------------
 *
 * Message Muter is an UI component that helps
 * a user to mute or unmute the current conversation.
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-17-2013
 */

var messageMuter = this.messageMuter = (function ($, win) {
	'use strict';

	var kifiUtil = win.kifiUtil;

	api.port.on({
		muted: function (muted) {
			if (messageMuter.initialized) {
				messageMuter.updateMuted(muted);
			}
		}
	});

	api.onEnd.push(function () {
		messageMuter.destroy('api:onEnd');
		messageMuter = win.messageMuter = null;
	});

	return {

		/**
		 * Whether this component is initialized.
		 *
		 * @property {boolean}
		 */
		initialized: false,

		/**
		 * Parent UI Component. e.g. Message Header
		 *
		 * @property {Object}
		 */
		parent: null,

		/**
		 * A constructor of Message Muter
		 *
		 * @constructor
		 *
		 * @param {string} trigger - A triggering user action
		 */
		construct: function (trigger) {},

		/**
		 * Initializes a Message Muter.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		init: function (trigger) {
			this.initialized = true;

			this.initEvents();

			this.requestIsMuted()
				.then(this.updateMuted.bind(this));

			this.logEvent('init', {
				trigger: trigger
			});
		},

		/**
		 * Initializes event listeners.
		 */
		initEvents: function () {
			var $parent = this.getParent$();
			$parent.on('click', '.kifi-message-header-unmute-button', this.unmute.bind(this));

			$parent.on('click', '.kifi-message-mute-option-mute', this.mute.bind(this));
			$parent.on('click', '.kifi-message-mute-option-unmute', this.unmute.bind(this));
		},

		/**
		 * Renders a UI component given the component name.
		 *
		 * @param {string} name - UI component name
		 *
		 * @return {string} A rendered html
		 */
		render: function (name) {
			switch (name) {
				//case 'button':
			case 'option':
				return this.renderOption();
			case 'content':
				return this.renderContent();
			}
		},

		/**
		 * Whether the conversation is muted or not.
		 * 
		 * @return {boolean} Whether the conversation is muted or not
		 */
		isMuted: function () {
			return Boolean(this.parent.getStatus('muted'));
		},

		mute: function () {
			if (!this.isMuted()) {
				this.updateMuted(true);
				this.sendMuted(true);
				this.parent.hideOptions();
				return true;
			}
			return false;
		},

		unmute: function () {
			if (this.isMuted()) {
				this.updateMuted(false);
				this.sendMuted(false);
				this.parent.hideOptions();
				return true;
			}
			return false;
		},

		setMuted: function (muted) {
			if (muted) {
				return this.unmute();
			}
			return this.mute();
		},

		updateMuted: function (muted) {
			this.parent.setStatus('muted', muted);
		},

		getThreadId: function () {
			return this.parent.getThreadId();
		},

		requestIsMuted: function () {
			return kifiUtil.request('is_muted', this.getThreadId(), 'Could get is_muted');
		},

		sendMuted: function (muted) {
			var threadId = this.getThreadId();
			if (muted) {
				return kifiUtil.request('mute_thread', threadId, 'Could not mute');
			}
			return kifiUtil.request('unmute_thread', threadId, 'Could not unmute');
		},

		/**
		 * Returns the current state object excluding UI states.
		 *
		 * @return {Object} A state object
		 */
		getView: function () {
			return {
				muted: this.isMuted()
			};
		},

		/**
		 * Renders and returns a Mute option.
		 *
		 * @return {string} Mute option html
		 */
		renderOption: function () {
			return win.render('html/keeper/message_mute_option', this.getView());
		},

		/**
		 * Renders and returns html for muted status box.
		 *
		 * @return {string} muted status box html
		 */
		renderContent: function () {
			return win.render('html/keeper/message_muted', this.getView());
		},

		/**
		 * Returns a jQuery wrapper object for the parent module.
		 *
		 * @return {jQuery} A jQuery wrapper object
		 */
		getParent$: function () {
			return this.parent.$el;
		},

		/**
		 * Destroys a tag box.
		 * It removes all event listeners and caches to elements.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		destroy: function (trigger) {
			if (this.initialized) {
				this.initialized = false;
				this.parent = null;

				if (win.slider2) {
					win.slider2.unshadePane();
				}

				['$input', '$list', '$el'].forEach(function (name) {
					var $el = this[name];
					if ($el) {
						$el.remove();
						this[name] = null;
					}
				}, this);

				this.logEvent('destroy', {
					trigger: trigger
				});
			}
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
			win.logEvent('slider', 'message_mute.' + name, obj || null);
		}

	};

})(jQuery, this);
