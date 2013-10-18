// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/util.js
// @require scripts/kifi_util.js
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
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-17-2013
 */

/*global cdnBase, Mustache */

var messageParticipants = this.messageParticipants = (function ($, win) {
	'use strict';

	var util = win.util,
		kifiUtil = win.kifiUtil,
		OVERFLOW_LENGTH = 9;

	// receive
	/*
	api.port.on({
		friends: function (friends) {}
	});
  */

	api.onEnd.push(function () {
		messageParticipants.destroy('api:onEnd');
		messageParticipants = win.messageParticipants = null;
	});

	return {

		/**
		 * Whether this is initialized
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

		//participants: [],
		participants: [
			{
				'id': '6f21b520-87e7-4053-9676-85762e96970a',
				'firstName': 'Jenny',
				'lastName': 'Batres',
				'pictureName': '0.jpg'
      },
			{
				'id': '772a9c0f-d083-44cb-87ce-de564cbbfa22',
				'firstName': 'Yasuhiro',
				'lastName': 'Matsuda',
				'pictureName': '0.jpg'
      }, {
				'id': 'b80511f6-8248-4799-a17d-f86c1508c90d',
				'firstName': 'Léo',
				'lastName': 'Grimaldi',
				'pictureName': '0.jpg'
      }, {
				'id': 'd3cdb758-27df-4683-a589-e3f3d99fa47b',
				'firstName': 'Jared',
				'lastName': 'Jacobs',
				'pictureName': '0.jpg'
      }, {
				'id': '2d18cd0b-ef30-4759-b6c5-f5f113a30f08',
				'firstName': 'Effi',
				'lastName': 'Fuks-Leichtag',
				'pictureName': '0.jpg'
      }, {
				'id': '6d8e337d-4199-49e1-a95c-e4aab582eeca',
				'firstName': 'Yingjie',
				'lastName': 'Miao',
				'pictureName': '0.jpg'
      }, {
				'id': '41d57d50-0c14-45ae-8348-2200d70f9eb8',
				'firstName': 'Van',
				'lastName': 'Mendoza',
				'pictureName': '0.jpg'
      }, {
				'id': '0471b558-75a0-41f3-90c5-febc9e95cef9',
				'firstName': 'Greg',
				'lastName': 'Methvin',
				'pictureName': '0.jpg'
      }, {
				'id': '1a316f42-13be-4d86-a4a2-8c7efb3010b8',
				'firstName': 'Alexander',
				'lastName': 'Willis Schultz',
				'pictureName': '0.jpg'
      }, {
				'id': 'c82b0fa0-6438-4892-8738-7fa2d96f1365',
				'firstName': 'Ketan',
				'lastName': 'Patel',
				'pictureName': '0.jpg'
      }, {
				'id': '3ad31932-f3f9-4fe3-855c-3359051212e5',
				'firstName': 'Danny',
				'lastName': 'Blumenfeld',
				'pictureName': '0.jpg'
      }, {
				'id': 'ae5d159c-5935-4ad5-b979-ea280cb6c7ba',
				'firstName': 'Eishay',
				'lastName': 'Smith',
				'pictureName': '0.jpg'
      }, {
				'id': 'e890b13a-e33c-4110-bd11-ddd51ec4eceb',
				'firstName': 'Tamila',
				'lastName': 'Stavinsky',
				'pictureName': '0.jpg'
      }, {
				'id': '597e6c13-5093-4cba-8acc-93318987d8ee',
				'firstName': 'Stephen',
				'lastName': 'Kemmerling',
				'pictureName': '0.jpg'
      }, {
				'id': '147c5562-98b1-4fc1-946b-3873ac4a45b4',
				'firstName': 'Eduardo',
				'lastName': 'Fonseca',
				'pictureName': '0.jpg'
      }, {
				'id': '70927814-6a71-4eb4-85d4-a60164bae96c',
				'firstName': 'Raymond',
				'lastName': 'Ng',
				'pictureName': '0.jpg'
      }, {
				'id': 'dc6cb121-2a69-47c7-898b-bc2b9356054c',
				'firstName': 'Andrew',
				'lastName': 'Conner',
				'pictureName': '0.jpg'
      }, {
				'id': '73b1134d-02d4-443f-b99b-e8bc571455e2',
				'firstName': 'Chandler',
				'lastName': 'Sinclair',
				'pictureName': '0.jpg'
      }
		],

		/**
		 * A constructor of Message Header
		 *
		 * Renders and initializes a message header box if not already.
		 *
		 * @constructor
		 *
		 * @param {string} trigger - A triggering user action
		 */
		construct: function (trigger) {},

		/**
		 * Renders and initializes a Message Header.
		 *
		 * @param {string} trigger - A triggering user action
		 */
		init: function (trigger) {
			this.initialized = true;

			/*
			this.requestParticipants()
				.then(this.onResponseParticipants.bind(this));
        */

			this.initEvents();
			this.initInput();

			this.logEvent('init', {
				trigger: trigger
			});
		},

		/**
		 * Request a list of all of participants.
		 *
		 * @return {Object} A deferred promise object
		 */
		requestParticipants: function () {
			return kifiUtil.request('participants', win.$slider && win.$slider.getThreadId(), 'Could not load participants.');
		},

		/**
		 * Initializes event listeners.
		 */
		initEvents: function () {
			var $el = this.get$();
			$el.on('click', '.kifi-message-participants-avatars-expand', this.toggleParticipants.bind(this));
			$el.on('click', '.kifi-message-participant-list-hide', this.toggleParticipants.bind(this));

			var $parent = this.getParent$();
			$parent.on('click', '.kifi-message-add-participant', this.toggleAddDialog.bind(this));
			$parent.on('click', '.kifi-message-participant-dialog-button', this.addParticipantTokens.bind(this));
		},

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
		onResponseParticipants: function (participants) {
			//this.participants = participants;
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
			return this.participants.length > 1;
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
			return this.participants.length > OVERFLOW_LENGTH;
		},

		/**
		 * Returns a recipient (the first participant) of the 1:1 conversation.
		 *
		 * @return {Object} a recipient of the 1:1 conversation
		 */
		getRecipient: function () {
			return this.participants[0];
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
				participantCount: this.participants.length,
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
			var participants = this.participants;
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
			return this.participants.map(this.renderParticipant).join('');
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
			this.get$().addClass('kifi-expanded');
			$wrapper.height(list.offsetHeight);
		},

		updateParticipantsHeight: function () {
			if (this.isExpanded()) {
				this.expandParticipants();
			}
		},

		/**
		 * Collapses the participant list.
		 */
		collapseParticipants: function () {
			this.get$().removeClass('kifi-expanded');
			this.get$('.kifi-message-participant-list-root').height(0);
		},

		toggleParticipants: function () {
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
		},

		toggleAddDialog: function () {
			var active = !this.isDialogOpened();
			this.get$('.kifi-message-add-participant').toggleClass('kifi-active', active);
			this.toggleClass('kifi-dialog-opened', active);
			if (active) {
				this.focusInput();
			}
		},

		indexOfUser: (function () {
			function hasSameId(u) {
				return u.id === this.id;
			}

			return function (user) {
				util.keyOf(this.participants, hasSameId, user);
			};
		})(),

		addParticipant: function () {
			util.prependUnique(this.participants, arguments);
			this.updateView();
		},

		removeParticipant: function () {
			util.removeList(this.participants, arguments);
			this.updateView();
		},

		updateView: function () {
			var view = this.getView(),
				$el = this.get$();
			$el.toggleClass('kifi-group-conversation', view.isGroup);
			$el.toggleClass('kifi-overflow', view.isOverflowed);
			$el.find('.kifi-message-recipient-name').text(view.recipientName);
			$el.find('.kifi-participant-count').text(view.participantCount);
			$el.find('.kifi-message-participants-avatars').html(view.avatars);
			$el.find('.kifi-message-participant-list').html(view.participants);
			this.updateParticipantsHeight();
		},

		updateCount: function (count) {
			return this.get$('.kifi-participant-count').text(count);
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
				this.participants = [];

				if (this.$input) {
					this.$input.remove();
					this.$input = null;
				}

				if (this.$el) {
					this.$el.remove();
					this.$el = null;
				}

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
