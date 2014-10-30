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
 */

k.messageMuter = k.messageMuter || (function ($, win) {
	'use strict';

	var portHandlers = {
		muted: function (o) {
			if (o.threadId === (k.messageMuter.parent || {}).threadId) {
				k.messageMuter.updateMuted(o.muted);
			}
		}
	};

	api.onEnd.push(function () {
		k.messageMuter.destroy();
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
		 * Initializes a Message Muter.
		 */
		init: function () {
			this.initialized = true;

			api.port.on(portHandlers);

			this.initEvents();

			this.requestIsMuted()
				.then(this.updateMuted.bind(this));
		},

		/**
		 * Initializes event listeners.
		 */
		initEvents: function () {
			this.parent.$el
				.on('click', '.kifi-message-header-unmute-button', this.unmute.bind(this))
				.on('click', '.kifi-message-mute-option-mute', this.mute.bind(this))
				.on('click', '.kifi-message-mute-option-unmute', this.unmute.bind(this));
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

		updateMuted: function (muted) {
			this.parent.setStatus('muted', muted);
		},

		requestIsMuted: function () {
			return k.request('is_muted', this.parent.threadId, 'Could not get is_muted');
		},

		sendMuted: function (muted) {
			var threadId = this.parent.threadId;
			if (muted) {
				return k.request('mute_thread', threadId, 'Could not mute');
			}
			return k.request('unmute_thread', threadId, 'Could not unmute');
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
			return k.render('html/keeper/message_mute_option', this.getView());
		},

		/**
		 * Renders and returns html for muted status box.
		 *
		 * @return {string} muted status box html
		 */
		renderContent: function () {
			return k.render('html/keeper/message_muted', this.getView());
		},

		/**
		 * It removes all event listeners and caches to elements.
		 */
		destroy: function () {
			this.initialized = false;
			this.parent = null;
			api.port.off(portHandlers);
		}
	};

})(jQuery, this);
