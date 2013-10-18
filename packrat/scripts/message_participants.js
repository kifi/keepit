// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/kifi_util.js
// @require scripts/html/keeper/message_participants.js
// @require scripts/html/keeper/message_participant.js
// @require scripts/html/keeper/message_avatar.js
// @require styles/keeper/message_participants.css

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

var messageParticipants = this.messageParticipants = (function ($, win) {
	'use strict';

	var kifiUtil = win.kifiUtil,
		OVERFLOW_LENGTH = 9;

	// receive
	api.port.on({
		friends: function (friends) {}
	});

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
		participants: [{
				"id": "6f21b520-87e7-4053-9676-85762e96970a",
				"firstName": "Jenny",
				"lastName": "Batres",
				"pictureName": "0.jpg"
			}, {
				"id": "772a9c0f-d083-44cb-87ce-de564cbbfa22",
				"firstName": "Yasuhiro",
				"lastName": "Matsuda",
				"pictureName": "0.jpg"
			}, {
				"id": "b80511f6-8248-4799-a17d-f86c1508c90d",
				"firstName": "Léo",
				"lastName": "Grimaldi",
				"pictureName": "0.jpg"
			}, {
				"id": "d3cdb758-27df-4683-a589-e3f3d99fa47b",
				"firstName": "Jared",
				"lastName": "Jacobs",
				"pictureName": "0.jpg"
			}, {
				"id": "2d18cd0b-ef30-4759-b6c5-f5f113a30f08",
				"firstName": "Effi",
				"lastName": "Fuks-Leichtag",
				"pictureName": "0.jpg"
			}, {
				"id": "6d8e337d-4199-49e1-a95c-e4aab582eeca",
				"firstName": "Yingjie",
				"lastName": "Miao",
				"pictureName": "0.jpg"
			}, {
				"id": "41d57d50-0c14-45ae-8348-2200d70f9eb8",
				"firstName": "Van",
				"lastName": "Mendoza",
				"pictureName": "0.jpg"
			}, {
				"id": "0471b558-75a0-41f3-90c5-febc9e95cef9",
				"firstName": "Greg",
				"lastName": "Methvin",
				"pictureName": "0.jpg"
			}, {
				"id": "1a316f42-13be-4d86-a4a2-8c7efb3010b8",
				"firstName": "Alexander",
				"lastName": "Willis Schultz",
				"pictureName": "0.jpg"
			}, {
				"id": "c82b0fa0-6438-4892-8738-7fa2d96f1365",
				"firstName": "Ketan",
				"lastName": "Patel",
				"pictureName": "0.jpg"
			}, {
				"id": "3ad31932-f3f9-4fe3-855c-3359051212e5",
				"firstName": "Danny",
				"lastName": "Blumenfeld",
				"pictureName": "0.jpg"
			}, {
				"id": "ae5d159c-5935-4ad5-b979-ea280cb6c7ba",
				"firstName": "Eishay",
				"lastName": "Smith",
				"pictureName": "0.jpg"
			}, {
				"id": "e890b13a-e33c-4110-bd11-ddd51ec4eceb",
				"firstName": "Tamila",
				"lastName": "Stavinsky",
				"pictureName": "0.jpg"
			}, {
				"id": "597e6c13-5093-4cba-8acc-93318987d8ee",
				"firstName": "Stephen",
				"lastName": "Kemmerling",
				"pictureName": "0.jpg"
			}, {
				"id": "147c5562-98b1-4fc1-946b-3873ac4a45b4",
				"firstName": "Eduardo",
				"lastName": "Fonseca",
				"pictureName": "0.jpg"
			}, {
				"id": "70927814-6a71-4eb4-85d4-a60164bae96c",
				"firstName": "Raymond",
				"lastName": "Ng",
				"pictureName": "0.jpg"
			}, {
				"id": "dc6cb121-2a69-47c7-898b-bc2b9356054c",
				"firstName": "Andrew",
				"lastName": "Conner",
				"pictureName": "0.jpg"
			}, {
				"id": "73b1134d-02d4-443f-b99b-e8bc571455e2",
				"firstName": "Chandler",
				"lastName": "Sinclair",
				"pictureName": "0.jpg"
			}],

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

		initEvents: function () {
			var $el = this.get$();
			$el.on('click', '.kifi-message-participants-avatars-expand', this.expandParticipants.bind(this));
			$el.on('click', '.kifi-message-participant-list-close', this.collapseParticipants.bind(this));
		},

		/**
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

		render: function (name) {
			switch (name) {
			case 'button':
				return this.renderButton();
				//case 'option':
			case 'content':
				return this.renderContent();
			}
		},

		isGroup: function () {
			return this.participants.length > 1;
		},

		isOverflowed: function () {
			return this.participants.length > OVERFLOW_LENGTH;
		},

		getRecipient: function () {
			return this.participants[0];
		},

		getFullName: function (user) {
			return user.firstName + ' ' + user.lastName;
		},

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
		 * Renders and returns a tag box html.
		 *
		 * @return {string} tag box html
		 */
		renderContent: function () {
			return win.render('html/keeper/message_participants', this.getView());
		},

		renderButton: function () {
			return '<span class="kifi-message-header-button kifi-message-add-participant"></span>';
		},

		renderAvatars: function () {
			var participants = this.participants,
				len = participants.length;
			if (this.isOverflowed()) {
				participants = participants.slice(0, OVERFLOW_LENGTH - 1);
			}
			return participants.map(this.renderAvatar).join('');
		},

		renderAvatar: function (user) {
			return win.render('html/keeper/message_avatar', user);
		},

		renderParticipants: function () {
			return this.participants.map(this.renderParticipant).join('');
		},

		renderParticipant: function (user) {
			return win.render('html/keeper/message_participant', user);
		},

		get$: function (sel) {
			return this.parent.find(sel || '.kifi-message-participants');
		},

		get$container: function () {
			return this.get$('.kifi-message-participants');
		},

		expandParticipants: function () {
			var $wrapper = this.get$('.kifi-message-participant-list-root'),
				list = $wrapper.children()[0];
			this.get$container().addClass('kifi-expanded');
			$wrapper.height(list.offsetHeight);
		},

		collapseParticipants: function () {
			this.get$container().removeClass('kifi-expanded');
			this.get$('.kifi-message-participant-list-root').height(0);
		},

		toggleParticipants: function () {
			var $wrapper = this.get$('.kifi-message-participant-list-root'),
				expanded = this.get$container().hasClass('kifi-expanded');
			log('toggleParticipants', $wrapper, expanded)();
			if (expanded) {
				this.collapseParticipants();
			}
			else {
				this.expandParticipants();
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
			util.addUnique(this.participants, arguments);
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
		},

	};

})(jQuery, this);
