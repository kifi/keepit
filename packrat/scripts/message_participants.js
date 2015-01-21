// @require styles/keeper/message_participants.css
// @require styles/keeper/compose.css
// @require scripts/html/keeper/message_participants.js
// @require scripts/html/keeper/message_participant_email.js
// @require scripts/html/keeper/message_participant_user.js
// @require scripts/html/keeper/message_participant_icon.js
// @require scripts/html/keeper/message_avatar_email.js
// @require scripts/html/keeper/message_avatar_user.js
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/formatting.js
// @require scripts/friend_search.js
// @require scripts/render.js
// @require scripts/kifi_util.js
// @require scripts/prevent_ancestor_scroll.js

/**
 * --------------------------
 *	Message Participants
 * --------------------------
 *
 * Message Participants is an UI component that manages
 * participants of the current message conversation.
 */

k.messageParticipants = k.messageParticipants || (function ($, win) {
	'use strict';

	var OVERFLOW_LENGTH = 8;

	var portHandlers = {
		participants: function (participants) {
			k.messageParticipants.setParticipants(participants);
		},
		add_participants: function (users) {
			k.messageParticipants.addParticipant.apply(k.messageParticipants, users);
		}
	};

	api.onEnd.push(function () {
		k.messageParticipants.destroy();
	});

	return {

		/**
		 * Whether this component is initialized
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

		escHandler: null,

		onDocClick: null,

		/**
		 * Initializes a Message Participants.
		 */
		init: function () {
			this.initialized = true;

			api.port.on(portHandlers);

			this.initEvents();
			this.initScroll();
		},

		/**
		 * Initializes event listeners.
		 */
		initScroll: function () {
			var $list = this.get$('.kifi-message-participant-list');
			this.$list = $list;
			$list.antiscroll({
				x: false
			}).children().preventAncestorScroll();
		},

		/**
		 * Initializes event listeners.
		 */
		initEvents: (function () {
			function onClick(e) {
				var $target = $(e.target);
				if (this.isExpanded() && !$target.closest('.kifi-message-participants').length) {
					this.collapseParticipants();
					e.participantsClosed = true;
				}
				else if (this.isDialogOpened() && !$target.closest('.kifi-message-participant-dialog').length) {
					this.hideAddDialog();
					e.addDialogClosed = true;
				}
			}

			function addDocListeners() {
				if (this.initialized) {
					this.escHandler = this.handleEsc.bind(this);
					$(document).data('esc').add(this.escHandler);

					var onDocClick = this.onDocClick = onClick.bind(this);
					document.addEventListener('click', onDocClick, true);
				}
			}

			return function () {
				var submitAddParticipants = this.addParticipantTokens.bind(this);

				var $el = this.get$();
				$el.on('click', '.kifi-message-participant-avatar>a,.kifi-message-participant-a', this.openUserProfile.bind(this));
				$el.on('click', '.kifi-message-participants-avatars-expand', this.toggleParticipants.bind(this));
				$el.on('click', '.kifi-message-participant-list-hide', this.toggleParticipants.bind(this));
				$el.on('click', '.kifi-message-participant-dialog-button', submitAddParticipants);
				$el.on('keydown', '.kifi-message-participant-dialog', function (e) {
					if (e.which === 13 && e.originalEvent.isTrusted !== false) {
						submitAddParticipants();
					}
				});

				var $parent = this.getParent$();
				$parent.on('click', '.kifi-message-add-participant', this.toggleAddDialog.bind(this));

				win.setTimeout(addDocListeners.bind(this));
			};
		})(),

		/**
		 * Returns a jQuery wrapper object for dialog input
		 * after tokenInput construction.
		 */
		get$TokenInput: function () {
			return this.get$('.kifi-ti-token-for-input>input');
		},

		/**
		 * Constructs and initializes (if not already done) and then asynchronously focuses a tokenInput
		 * with autocomplete feature for searching and adding people to the conversation.
		 */
		initAndAsyncFocusInput: function () {
			var $input = this.$input;
			if (!$input) {
				$input = this.$input = this.get$('.kifi-message-participant-dialog-input');
				initFriendSearch($input, 'threadPane', this.getParticipants(), api.noop, {
					placeholder: 'Type a name...',
					onAdd: function () {
						this.getAddDialog().addClass('kifi-non-empty');
					}.bind(this),
					onDelete: function () {
						if (!$input.tokenInput('get').length) {
							this.getAddDialog().removeClass('kifi-non-empty');
						}
					}.bind(this)
				});
			}

			setTimeout(function () {
				this.get$TokenInput().focus();
			}.bind(this));
		},

		// socket.send(["add_participants_to_thread","e45841fb-b7de-498f-97af-9f1ab17ef9a9",["df7ba036-700c-4f5d-84d1-313b5bf312b6"]])

		/**
		 * A listener for adding
		 *
		 * participants: [{
		 *   firstName: "Jenny"
		 *   id: "6f21b520-87e7-4053-9676-85762e96970a"
		 *   lastName: "Batres"
		 *   pictureName: "0.jpg"
		 * }]
		 */
		setParticipants: function (participants) {
			var parent = this.parent;
			if (parent && parent.initialized) {
				parent.participants = participants;
				this.updateView();
			}
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
			case 'button':
				return this.renderButton();
				//case 'option':
			case 'content':
				return this.renderContent();
			}
		},

		getParticipants: function () {
			return this.parent.participants;
		},

		/**
		 * Returns a boolean value representing whether
		 * the conversation has more number of people than the threshold.
		 *
		 * true - n > threshold
		 * false - n <= threshold
		 *
		 * @return {boolean} Whether the conversation has more number of people than the threshold
		 */
		isOverflowed: function () {
			return this.getParticipants().length > OVERFLOW_LENGTH;
		},

		/**
		 * Given the user object,
		 * returns the full name of the user.
		 *
		 * @param {Object} user - A user object
		 *
		 * @return {string} Full name of the user
		 */
		getFullName: function (user) {
			return user.firstName + ' ' + user.lastName;
		},

		/**
		 * Returns the current state object excluding UI states.
		 *
		 * @return {Object} A state object
		 */
		getView: function () {
			var participants = this.getParticipants();
			var other = participants.length === 2 ? participants.filter(function (user) {
				return user.id !== k.me.id;
			})[0] : null;
			return {
				participantName: other ? this.getFullName(other) : null,
				isOverflowed: this.isOverflowed(),
				participantCount: participants.length,
				avatars: this.renderAvatars(),
				participants: this.renderParticipants()
			};
		},

		/**
		 * Renders and returns html for participants container.
		 *
		 * @return {string} participants html
		 */
		renderContent: function () {
			return k.render('html/keeper/message_participants', this.getView());
		},

		/**
		 * Renders and returns a 'Add Participants' button.
		 *
		 * @return {string} Add participant icon html
		 */
		renderButton: function () {
			return k.render('html/keeper/message_participant_icon', this.getView());
		},

		/**
		 * Renders and returns html for a list of avatars.
		 *
		 * @return {string} html for a list of avatars
		 */
		renderAvatars: function () {
			var participants = this.getParticipants();
			if (this.isOverflowed()) {
				participants = participants.slice(0, OVERFLOW_LENGTH);
			}
			return participants.map(this.renderAvatar).join('');
		},

		/**
		 * Renders and returns html for a single avatar.
		 *
		 * @return {string} html for a single avatar
		 */
		renderAvatar: function (user) {
			formatParticipant(user);
			if (user.kind === 'email') {
				return k.render('html/keeper/message_avatar_email', user);
			} else {
				return k.render('html/keeper/message_avatar_user', user);
			}
		},

		/**
		 * Renders and returns html for a participant list.
		 *
		 * @return {string} html for a participant list
		 */
		renderParticipants: function () {
			return this.getParticipants().map(this.renderParticipant).join('');
		},

		/**
		 * Renders and returns html for a participant list item.
		 *
		 * @return {string} html for a participant list item
		 */
		renderParticipant: function (user) {
			formatParticipant(user);
			if (user.kind === 'email') {
				return k.render('html/keeper/message_participant_email', user);
			} else {
				return k.render('html/keeper/message_participant_user', user);
			}
		},

		/**
		 * Finds and returns a jQuery wrapper object for the given selector.
		 *
		 * @param {string} [selector] a optional selector
		 *
		 * @return {jQuery} A jQuery wrapper object
		 */
		get$: function (selector) {
			return this.parent.find(selector || '.kifi-message-participants');
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
		 * Expands the participant list.
		 */
		expandParticipants: function () {
			var $wrapper = this.get$('.kifi-message-participant-list-root'),
				list = $wrapper.children()[0];

			this.parent.shadePane();

			this.get$().addClass('kifi-expanded');

			$wrapper.height(list.offsetHeight);
		},

		/**
		 * Collapses the participant list.
		 */
		collapseParticipants: function () {
			this.get$().removeClass('kifi-expanded');
			this.get$('.kifi-message-participant-list-root').height(0);

			this.parent.unshadePane();
		},

		updateParticipantsHeight: function () {
			if (this.isExpanded()) {
				this.expandParticipants();
			}
		},

		toggleParticipants: function (e) {
			if (e && e.originalEvent && e.originalEvent.participantsClosed) {
				return;
			}
			if (this.isExpanded()) {
				this.collapseParticipants();
			}
			else {
				this.expandParticipants();
			}
		},

		openUserProfile: function (e) {
			var a = e.currentTarget, url = a.href;
			if (url.indexOf('?') < 0) {
				a.href = url + '?o=xmp';
				setTimeout(function () {
					a.href = url;
				});
			}
		},

		/**
		 * Add/Remove classname(s) to/from the container
		 */
		toggleClass: function (name, val) {
			return this.get$().toggleClass(name, !! val);
		},

		hasClass: function (name) {
			return this.get$().hasClass(name);
		},

		isExpanded: function () {
			return this.hasClass('kifi-expanded');
		},

		isDialogOpened: function () {
			return this.hasClass('kifi-dialog-opened');
		},

		getAddDialog: function () {
			return this.get$('.kifi-message-participant-dialog');
		},

		addParticipantTokens: function () {
			var $input = this.$input,
				users = $input.tokenInput('get');
			this.addParticipant.apply(this, users);
			$input.tokenInput('clear');
			this.toggleAddDialog();
			this.sendAddParticipants(users);
		},

		sendAddParticipants: function (users) {
			return k.request('add_participants', {
				threadId: this.parent.threadId,
				ids: users.map(function (u) { return u.id; })
			}, 'Could not add participants.');
		},

		showAddDialog: function () {
			this.get$('.kifi-message-add-participant').toggleClass('kifi-active', true);
			this.toggleClass('kifi-dialog-opened', true);
			this.initAndAsyncFocusInput();
			this.parent.hideOptions();
		},

		hideAddDialog: function () {
			this.get$('.kifi-message-add-participant').toggleClass('kifi-active', false);
			this.toggleClass('kifi-dialog-opened', false);
		},

		toggleAddDialog: function (e) {
			if (e && e.originalEvent && e.originalEvent.addDialogClosed) {
				return;
			}
			if (this.isDialogOpened()) {
				this.hideAddDialog();
			}
			else {
				this.showAddDialog();
			}
		},

		addParticipant: function () {
			var participants = this.getParticipants(),
				count = 0;

			for (var i = 0, len = arguments.length, user, userId; i < len; i++) {
				user = arguments[i];
				userId = user && user.id;
				if (userId && !participants.some(function (p) { return p.id === userId; })) {
					participants.unshift(user);
					count++;
				}
			}

			if (count) {
				this.updateView();
				this.highlightFirstNParticipants(count);
				this.highlightCount();
			}
		},

		highlightCount: function () {
			this.get$('.kifi-participant-count').addClass('kifi-highlight');
			setTimeout(function () {
				var $el = this.get$('.kifi-participant-count');
				if ($el) {
					$el.removeClass('kifi-highlight');
				}
			}.bind(this), 5000);
		},

		highlightFirstNParticipants: function (count) {
			if (count) {
				this.get$('.kifi-message-participant-item').slice(0, count).addClass('kifi-highlight');
				this.get$('.kifi-message-participant-avatar').slice(0, count).addClass('kifi-highlight');
			}
		},

		updateView: function () {
			var view = this.getView();
			var $el = this.get$();
			$el.attr('data-kifi-participants', view.participantCount);
			$el.toggleClass('kifi-overflow', view.isOverflowed);
			$el.find('.kifi-message-participant-name').text(view.participantName);
			$el.find('.kifi-participant-count').text(view.participantCount);
			$el.find('.kifi-message-participants-avatars').html(view.avatars);
			$el.find('.kifi-message-participant-list-inner').html(view.participants);
			this.updateParticipantsHeight();
			this.$list.data('antiscroll').refresh();
		},

		updateCount: function (count) {
			return this.get$('.kifi-participant-count').text(count);
		},

		handleEsc: function (e) {
			if (this.isExpanded()) {
				this.collapseParticipants();
				return false;
			}
			else if (this.isDialogOpened()) {
				this.hideAddDialog();
				return false;
			}
		},

		/**
		 * It removes all event listeners and caches to elements.
		 */
		destroy: function () {
			this.initialized = false;
			this.parent = null;

			$(document).data('esc').remove(this.escHandler);
			this.escHandler = null;

			var onDocClick = this.onDocClick;
			if (onDocClick) {
				document.removeEventListener('click', onDocClick, true);
				this.onDocClick = null;
			}

			api.port.off(portHandlers);

			// .remove() not called on jQuery elements because it would disrupt a farewell transition
			// an ancestor should call it later (e.g. by being removed itself)

			this.$input = this.$list = this.$el = null;
		}
	};
})(jQuery, this);
