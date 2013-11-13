// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/message_participants.js
// @require scripts/message_muter.js
// @require scripts/html/keeper/message_header.js
// @require styles/keeper/message_header.css

/**
 * ---------------------
 *    Message Header
 * ---------------------
 *
 * Message Header is an UI component that is a hub for plugins
 * related to current message context.
 */

var messageHeader = this.messageHeader = (function ($, win) {
	'use strict';

	// receive
	/*
	api.port.on({
		friends: function (friends) {}
	});
  */

	api.onEnd.push(function () {
		messageHeader.destroy();
		messageHeader = win.messageHeader = null;
	});

	return {

		/**
		 * Whether this is initialized
		 *
		 * @property {boolean}
		 */
		initialized: false,

		plugins: [win.messageParticipants, win.messageMuter],

		status: null,

		threadId: null,

		participants: null,

		prevEscHandler: null,

		onDocClick: null,

		/**
		 * Renders and initializes a message header box if not already.
		 */
		construct: function ($parent, threadId, participants) {
			if (!this.initialized) {
				this.threadId = threadId;
				this.participants = participants;
				this.constructPlugins();
				this.init($parent);
			}
		},

		/**
		 * Renders and initializes a Message Header.
		 */
		init: function ($parent) {
			this.initialized = true;
			this.status = {};
			this.$el = $(this.render()).appendTo($parent);
			this.initPlugins();
			this.initEvents();
		},

		/**
		 * Initializes event listeners.
		 */
		initEvents: (function () {
			function onClick(e) {
				if (this.isOptionExpanded() && !$(e.target).closest('.kifi-message-header-options').length) {
					this.hideOptions();
					e.optionsClosed = true;
				}
			}

			function addDocListeners() {
				if (this.initialized) {
					var $doc = $(document);
					this.prevEscHandler = $doc.data('esc');
					$doc.data('esc', this.handleEsc.bind(this));

					var onDocClick = this.onDocClick = onClick.bind(this);
					document.addEventListener('click', onDocClick, true);
				}
			}

			return function () {
				this.on('click', '.kifi-message-header-option-button', this.toggleOptions.bind(this));

				win.setTimeout(addDocListeners.bind(this));
			};
		})(),

		isOptionExpanded: function () {
			return Boolean(this.getStatus('option-expanded'));
		},

		showOptions: function () {
			this.setStatus('option-expanded', true);
			win.messageParticipants.hideAddDialog();
		},

		hideOptions: function () {
			this.setStatus('option-expanded', false);
		},

		shadePane: function () {
			if (win.slider2) {
				this.$el.closest('.kifi-thread-who').addClass('kifi-active');
				win.slider2.shadePane();
			}
		},

		unshadePane: function () {
			if (win.slider2) {
				this.$el.closest('.kifi-thread-who').removeClass('kifi-active');
				win.slider2.unshadePane();
			}
		},

		toggleOptions: function (e) {
			if (e && e.originalEvent && e.originalEvent.optionsClosed) {
				return;
			}
			if (this.isOptionExpanded()) {
				this.hideOptions();
			}
			else {
				this.showOptions();
			}
		},

		handleEsc: function (e) {
			if (this.isOptionExpanded()) {
				e.preventDefault();
				e.stopPropagation();
				e.stopImmediatePropagation();
				this.hideOptions();
			}
			else if (this.prevEscHandler) {
				this.prevEscHandler.call(e.target, e);
			}
		},

		/**
		 * Destroys a message header.
		 * It removes all event listeners and caches to elements.
		 */
		destroy: function () {
			if (this.initialized) {
				this.initialized = false;

				this.unshadePane();

				this.destroyPlugins();

				$(win.tile).css('transform', '');

				$(document).data('esc', this.prevEscHandler);
				this.prevEscHandler = null;

				var onDocClick = this.onDocClick;
				if (onDocClick) {
					document.removeEventListener('click', onDocClick, true);
					this.onDocClick = null;
				}

				'$el'.split(',').forEach(function (name) {
					var $el = this[name];
					if ($el) {
						$el.remove();
						this[name] = null;
					}
				}, this);

				this.status = null;
				this.$pane = null;
				this.participants = null;
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

		setStatus: function (name, isSet) {
			isSet = Boolean(isSet);
			this.$el.toggleClass('kifi-' + name, isSet);
			this.status[name] = isSet;
		},

		getStatus: function (name) {
			return Boolean(this.status[name]);
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
		}
	};

})(jQuery, this);
