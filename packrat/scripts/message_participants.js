// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/util.js
// @require scripts/kifi_util.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/html/keeper/message_participants.js
// @require scripts/html/keeper/message_participant.js
// @require scripts/html/keeper/message_participant_icon.js
// @require scripts/html/keeper/message_avatar.js
// @require scripts/lib/jquery-tokeninput.js
// @require styles/keeper/message_participants.css
// @require styles/keeper/compose.css

/**
 * --------------------------
 *    Message Participants
 * --------------------------
 *
 * Message Participants is an UI component that manages
 * participants of the current message conversation.
 */

/*global cdnBase, Mustache */

var messageParticipants = this.messageParticipants = (function ($, win) {
	'use strict';

	var util = win.util,
		kifiUtil = win.kifiUtil,
		OVERFLOW_LENGTH = 9;

	api.port.on({
		participants: function (participants) {
			if (messageParticipants.initialized) {
				messageParticipants.setParticipants(participants);
			}
		},
		'add_participants': function (users) {
			if (messageParticipants.initialized) {
				messageParticipants.addParticipant.apply(messageParticipants, users);
			}
		}
	});

	api.onEnd.push(function () {
		if (messageParticipants.initialized) {
			messageParticipants.destroy('api:onEnd');
			messageParticipants = win.messageParticipants = null;
		}
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

		prevEscHandler: null,

		onDocClick: null,

		/**
		 * A constructor of Message Participants
		 *
		 * @constructor
		 *
		 * @param {string} trigger - A triggering user action
		 */
		construct: function (trigger) {},

		/**
		 * Initializes a Message Participants.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		init: function (trigger) {
			this.initialized = true;

			/*
			this.requestParticipants()
				.then(this.setParticipants.bind(this));
        */

			this.initEvents();
			this.initScroll();
			this.initInput();

			this.logEvent('init', {
				trigger: trigger
			});
		},

		getThreadId: function () {
			return this.parent.getThreadId();
		},

		/**
		 * Request a list of all of participants.
		 *
		 * @return {Object} A deferred promise object
		 */
		requestParticipants: function () {
			var id = this.getThreadId();
			if (id) {
				return kifiUtil.request('participants', id, 'Could not load participants.');
			}
			return null;
		},

		/**
		 * Initializes event listeners.
		 */
		initScroll: function () {
			var $list = this.get$('.kifi-message-participant-list');
			this.$list = $list;
			$list.antiscroll({
				x: false
			});
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
					var $doc = $(document);
					this.prevEscHandler = $doc.data('esc');
					$doc.data('esc', this.handleEsc.bind(this));

					var onDocClick = this.onDocClick = onClick.bind(this);
					document.addEventListener('click', onDocClick, true);
				}
			}

			return function () {
				var $el = this.get$();
				$el.on('click', '.kifi-message-participants-avatars-expand', this.toggleParticipants.bind(this));
				$el.on('click', '.kifi-message-participant-list-hide', this.toggleParticipants.bind(this));

				var $parent = this.getParent$();
				$parent.on('click', '.kifi-message-add-participant', this.toggleAddDialog.bind(this));
				$parent.on('click', '.kifi-message-participant-dialog-button', this.addParticipantTokens.bind(this));

				win.setTimeout(addDocListeners.bind(this));
			};
		})(),

		/**
		 * Returns a jQuery wrapper object for dialog input
		 * after tokenInput construction.
		 */
		get$TokenInput: function () {
			return this.get$('.kifi-ti-token-input input');
		},

		/**
		 * Focuses dialog input for adding new people to the conversation.
		 */
		focusInput: function () {
			setTimeout(function () {
				this.get$TokenInput().focus();
			}.bind(this));
		},

		/**
		 * Constructs and initializes a tokenInput with autocomplete feature
		 * for searching and adding people to the conversation.
		 */
		initInput: function () {
			var $input = this.$input = this.get$('.kifi-message-participant-dialog-input').tokenInput({}, {
				// The delay, in milliseconds, between the user finishing typing and the search being performed. default: 300
				searchDelay: 0,

				// The minimum number of characters the user must enter before a search is performed.
				minChars: 1,

				placeholder: 'Type a name...',
				hintText: '',
				noResultsText: '',
				searchingText: '',
				animateDropdown: false,
				resultsLimit: 4,
				preventDuplicates: true,
				allowTabOut: true,
				tokenValue: 'id',
				theme: 'Kifi',
				classes: {
					tokenList: 'kifi-ti-list',
					token: 'kifi-ti-token',
					tokenReadOnly: 'kifi-ti-token-readonly',
					tokenDelete: 'kifi-ti-token-delete',
					selectedToken: 'kifi-ti-token-selected',
					highlightedToken: 'kifi-ti-token-highlighted',
					dropdown: 'kifi-root kifi-ti-dropdown',
					dropdownItem: 'kifi-ti-dropdown-item',
					dropdownItem2: 'kifi-ti-dropdown-item',
					selectedDropdownItem: 'kifi-ti-dropdown-item-selected',
					inputToken: 'kifi-ti-token-input',
					focused: 'kifi-ti-focused',
					disabled: 'kifi-ti-disabled'
				},
				zindex: 999999999992,
				resultsFormatter: function (f) {
					return '<li style="background-image:url(//' + cdnBase + '/users/' + f.id + '/pics/100/0.jpg)">' +
						Mustache.escape(f.name) + '</li>';
				},
				onAdd: function () {
					this.getAddDialog().addClass('kifi-non-empty');
				}.bind(this),
				onDelete: function () {
					if (!$input.tokenInput('get').length) {
						this.getAddDialog().removeClass('kifi-non-empty');
					}
				}.bind(this)
			});

			api.port.emit('get_friends', function (friends) {
				friends.forEach(function (f) {
					f.name = f.firstName + ' ' + f.lastName;
				});
				$input.data('settings').local_data = friends;
				$input.data('friends', friends);
			});
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
		 * the conversation is a group conversation.
		 *
		 * true - group conversation
		 * false - 1:1 conversation
		 *
		 * @return {boolean} Whether the conversation is a group conversation
		 */
		isGroup: function () {
			return this.getParticipants().length > 1;
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
		 * Returns a recipient (the first participant) of the 1:1 conversation.
		 *
		 * @return {Object} a recipient of the 1:1 conversation
		 */
		getRecipient: function () {
			return this.getParticipants()[0];
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
			return {
				isGroup: this.isGroup(),
				recipientName: this.getFullName(this.getRecipient()),
				isOverflowed: this.isOverflowed(),
				participantCount: this.getParticipants().length,
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
			return win.render('html/keeper/message_participants', this.getView());
		},

		/**
		 * Renders and returns a 'Add Participants' button.
		 *
		 * @return {string} Add participant icon html
		 */
		renderButton: function () {
			return win.render('html/keeper/message_participant_icon', this.getView());
		},

		/**
		 * Renders and returns html for a list of avatars.
		 *
		 * @return {string} html for a list of avatars
		 */
		renderAvatars: function () {
			var participants = this.getParticipants();
			if (this.isOverflowed()) {
				participants = participants.slice(0, OVERFLOW_LENGTH - 1);
			}
			return participants.map(this.renderAvatar).join('');
		},

		/**
		 * Renders and returns html for a single avatar.
		 *
		 * @return {string} html for a single avatar
		 */
		renderAvatar: function (user) {
			return win.render('html/keeper/message_avatar', user);
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
			return win.render('html/keeper/message_participant', user);
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

			if (win.slider2) {
				win.slider2.shadePane();
			}

			this.get$().addClass('kifi-expanded');

			$wrapper.height(list.offsetHeight);
		},

		/**
		 * Collapses the participant list.
		 */
		collapseParticipants: function () {
			this.get$().removeClass('kifi-expanded');
			this.get$('.kifi-message-participant-list-root').height(0);

			if (win.slider2) {
				win.slider2.unshadePane();
			}
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
			return kifiUtil.request('add_participants', {
				threadId: this.getThreadId(),
				userIds: util.pluck(users, 'id')
			}, 'Could not add participants.');
		},

		showAddDialog: function () {
			this.get$('.kifi-message-add-participant').toggleClass('kifi-active', true);
			this.toggleClass('kifi-dialog-opened', true);
			this.focusInput();
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

		indexOfUser: function (user) {
			return this.indexOfUserId(user && user.id);
		},

		indexOfUserId: function (userId) {
			return userId ? util.keyOf(this.getParticipants(), function (user) {
				return user.id === userId;
			}) : -1;
		},

		addParticipant: function () {
			var participants = this.getParticipants(),
				count = 0;

			for (var i = 0, len = arguments.length, user, userId; i < len; i++) {
				user = arguments[i];
				userId = user && user.id;
				if (userId && this.indexOfUser(user) === -1) {
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

		removeParticipant: function () {
			var indices = [];
			for (var i = 0, len = arguments.length, user, index; i < len; i++) {
				user = arguments[i];
				if (user) {
					index = this.indexOfUser(user);
					if (index !== -1) {
						indices.push(index);
					}
				}
			}

			var removed = util.removeIndices(this.getParticipants(), indices);
			if (removed.length) {
				this.updateView();
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
			var view = this.getView(),
				$el = this.get$();
			$el.toggleClass('kifi-group-conversation', view.isGroup);
			$el.toggleClass('kifi-overflow', view.isOverflowed);
			$el.find('.kifi-message-recipient-name').text(view.recipientName);
			$el.find('.kifi-participant-count').text(view.participantCount);
			$el.find('.kifi-message-participants-avatars').html(view.avatars);
			$el.find('.kifi-message-participant-list-inner').html(view.participants);
			this.updateParticipantsHeight();
		},

		updateCount: function (count) {
			return this.get$('.kifi-participant-count').text(count);
		},

		handleEsc: function (e) {
			var handled = false;
			if (this.isExpanded()) {
				handled = true;
				this.collapseParticipants();
			}
			else if (this.isDialogOpened()) {
				handled = true;
				this.hideAddDialog();
			}
			else if (this.prevEscHandler) {
				this.prevEscHandler.call(e.target, e);
			}

			if (handled) {
				e.preventDefault();
				e.stopPropagation();
				e.stopImmediatePropagation();
			}
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

				$(document).data('esc', this.prevEscHandler);
				this.prevEscHandler = null;

				var onDocClick = this.onDocClick;
				if (onDocClick) {
					document.removeEventListener('click', onDocClick, true);
					this.onDocClick = null;
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
			win.logEvent('slider', 'message_participants.' + name, obj || null);
		}

	};

})(jQuery, this);
