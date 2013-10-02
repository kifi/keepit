// @require scripts/lib/jquery.js
// @require scripts/lib/fuzzy-min.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/render.js
// @require styles/keeper/tagbox.css
// @require html/keeper/tagbox.html
// @require html/keeper/tag-suggestion.html

/*
 * End Points
create
  Request URL:https://api.kifi.com/site/collections/create
  Request Method: POST
  Request Payload: {"name":"hello"}
  Response: {"id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04","name":"hello"}

Search\n- simple tuba ii brr tobacco brown",
      "url":"http://www.6pm.com/simple-tuba-ii-brr-tobacco-brown"
    }]
  }
  Response: {
    "keeps":[{
      "id":"88ed8dc9-a20d-49c6-98ef-1b554533b106",
      "title":"Search\n- simple tuba ii brr tobacco brown",
      "url":"http://www.6pm.com/simple-tuba-ii-brr-tobacco-brown",
      "isPrivate":false
    }],
    "addedToCollection":1
  }

remove
  Request URL: https://api.kifi.com/site/collections/dc76ee74-a141-4e96-a65f-e5ca58ddfe04/removeKeeps
  Request Method: POST
  Request Payload: ["88ed8dc9-a20d-49c6-98ef-1b554533b106"]
  Response: {"removed":1}

get
  Request URL: https://api.kifi.com/site/collections/all?sort=user&_=hm9pbqo7
  Request Method: GET
  Response: {
    "keeps":15,
    "collections":[{
      "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
      "name":"hello",
      "keeps":0
    },{"id":"59db576a-5774-4850-a244-70fc54ea8b5c","name":"test","keeps":0}
    ]
  }
 *   
 */

var list = [
	{
		id: 'f033afe4-bbb9-4609-ab8b-3e8aa968af21',
		name: 'academic'
  },
	{
		id: 'f033afe4-bbb9-4609-ab8b-3e8aa968af21',
		name: '안녕'
  },
	{
		id: 'f033afe4-bbb9-4609-ab8b-3e8aa968af21',
		name: '안녕하세요'
  },
	{
		id: '65d0686f-c7cc-418c-ae21-f648e519960e',
		name: 'school'
  },
	{
		id: '6cd1c3b9-8ebc-4caa-98ce-289db1d39034',
		name: 'programming'
  },
	{
		id: '3be352c7-b6b1-4650-a36a-8ea9e7aa7ab4',
		name: 'network programming'
  },
	{
		id: '0bc6b719-9af9-45d0-bcb4-422b7486e5d5',
		name: 'socialism'
  },
	{
		id: 'f610ffd4-3e34-4b0b-a514-4b873a6c0fba',
		name: 'social'
  },
	{
		id: '08ef0c45-6441-4996-92df-e8802f4a81a4',
		name: 'social network'
  },
	{
		id: '4c26572c-b875-4c22-b46d-9e88497425b2',
		name: 'web'
  },
	{
		id: 'f31c5415-8e59-4697-a664-05f3f08cabea',
		name: 'computer'
  },
	{
		id: '1a3b0f76-d70e-44c7-a8a2-82e5425838d0',
		name: 'tour'
  },
	{
		id: '476e1320-b67c-408a-a349-8829a0d96141',
		name: 'city'
  },
	{
		id: '184d0b32-64c7-4023-be60-5194d471ae2e',
		name: 'night life'
  },
	{
		id: '88b34aa0-20b5-435d-afd9-a7c81ca516b8',
		name: 'life style'
  },
	{
		id: 'edf34fc7-9eb1-447d-ba22-3dd42adde4f8',
		name: 'lifestyle'
  },
	{
		id: '64f8746a-df8f-466c-8b4f-c25971b74738',
		name: 'nightlife'
  },
	{
		id: '0dd9bed9-8996-4014-b1a3-983d2c8b08fc',
		name: 'keep'
  },
	{
		id: 'da8f435b-3e89-4886-bcf8-d19f3a974c56',
		name: 'shopping'
  },
	{
		id: 'c673476a-35e7-4fe2-961f-c26a74ce41ed',
		name: 'books'
  },
	{
		id: '88e78bc4-2c28-4872-9047-b5fa17961a16',
		name: 'reviews'
  },
	{
		id: 'b3eeec76-51b7-4f64-9f94-cdfdd22205a2',
		name: 'movies'
  },
	{
		id: 'e06e18e7-b7c2-4421-ab31-a66bd1bef1d3',
		name: 'fun'
  },
	{
		id: 'a6515789-9cdf-45e4-9185-6471bce63a6d',
		name: 'things'
  },
	{
		id: '443362cd-e6c2-4229-9598-eeaf6ed4c5e2',
		name: 'many'
  },
	{
		id: '45eaae49-17a9-449b-bdbe-69642a44b7b6',
		name: 'collection'
  },
	{
		id: 'a9fbd403-3b81-4049-a9ef-02be5e4d59e1',
		name: 'marvin'
  },
	{
		id: '59db576a-5774-4850-a244-70fc54ea8b5c',
		name: 'test'
  },
	{
		id: '1e99ca1f-1a2c-4e69-9c4a-109ab4da8bf2',
		name: 'personal'
  },
	{
		id: '488ca2b7-415b-4952-a22c-3a2da55c1efa',
		name: 'engis'
  },
	{
		id: '310df722-eee8-4e88-acad-280445ff80ba',
		name: 'mykeeps'
  },
	{
		id: 'c97dae66-ea63-4596-8513-589f040eae4b',
		name: 'this is test'
  },
	{
		id: 'dc76ee74-a141-4e96-a65f-e5ca58ddfe04',
		name: 'hello'
  }
];

this.tagbox = (function (win) {
	'use strict';

	var $ = jQuery;

	function createValueTimeout($input) {
		var lastVal = '',
			tid;

		function onTimeout() {
			tid = null;
			var val = $input.val() || '';
			if (val !== lastVal) {
				$input.trigger({
					type: 'livechange',
					value: val,
					prevValue: lastVal
				});
				lastVal = val;
			}
		}

		return function () {
			if (tid) {
				win.clearTimeout(tid);
			}
			tid = win.setTimeout(onTimeout, 1);
		};
	}

	function activateScroll(selector) {
		var $container = $(selector);
		win.log('here0', $container.length)();
		$container.antiscroll({
			x: false
		});
		win.log('here1')();
		$(win).on('resize' + selector, $container, function (e) {
			var $container = e.data;
			win.log('here2')();
			$container.data('antiscroll').refresh();
			win.log('here3')();
		});
	}

	function deactivateScroll(selector) {
		$(win).off('resize' + selector);
	}

	return {
		initInputEvents: (function () {
			function onLiveChange(e) {
				var that = e.data;
				that.$suggest.empty();
				that.$inputbox.toggleClass('empty', !e.value);
				filterAndRender(that, e.value);
			}

			var filterTags = (function () {
				function extractName(item) {
					return item.name;
				}

				function extractData(match) {
					return {
						id: match.original.id,
						name: match.string
					};
				}

				var options = {
					pre: '<b>',
					post: '</b>',
					extract: extractName
				};

				return function (list, val) {
					return win.fuzzy.filter(val, list, options).map(extractData);
				};
			})();

			function filterAndRender(that, val) {
				var matches = val ? filterTags(list, val) : list,
					hasMatch = matches.length ? true : false;
				if (hasMatch) {
					matches.forEach(renderSuggestion, that);
				}

				that.$tagbox.toggleClass('suggested', hasMatch);
			}

			function renderSuggestion(item) {
				var that = this;
				win.render('html/keeper/tag-suggestion', item, function (html) {
					that.$suggest.append(html);
				})
			}

			function onFocus() {
				$(this).closest('.kifi-tagbox-input-box').addClass('focus');
			}

			function onBlur() {
				$(this).closest('.kifi-tagbox-input-box').removeClass('focus');
			}

			return function ($input) {
				$input.on('focus', onFocus);
				$input.on('blur', onBlur);
				$input.on('keydown change input paste', createValueTimeout($input));
				$input.on('livechange', this, onLiveChange);
			};
		})(),
		initInput: function () {
			var $inputbox = this.$tagbox.find('.kifi-tagbox-input-box');
			this.$inputbox = $inputbox;

			var $input = $inputbox.find('input.kifi-tagbox-input');
			this.$input = $input;

			this.initInputEvents($input);
		},
		initCloseIcon: function () {
			var that = this;
			this.$tagbox.find('.kifi-tagbox-close').click(that.remove.bind(that));
		},
		initSuggest: function () {
			this.$suggest = this.$tagbox.find('.kifi-tagbox-suggest-inner');
		},
		init: function () {
			if (!this.$tagbox) {
				var that = this;
				win.render('html/keeper/tagbox', null, function (html) {
					that.$tagbox = $(html).appendTo($('body'));
					that.initInput();
					that.initCloseIcon();
          that.initSuggest();
					activateScroll('.kifi-tagbox-suggest');
				});
			}
		},
		show: function ( /*$slider*/ ) {
			this.init();
		},
		toggle: function ($slider) {
			if (this.$tagbox) {
				this.hide();
			}
			else {
				this.show($slider);
			}
		},
		hide: function () {
			this.remove();
		},
		remove: function () {
			if (this.$tagbox) {
				deactivateScroll('.kifi-tagbox-suggest');
				this.$input.remove();
				this.$suggest.remove();
				this.$tagbox.remove();
				this.$tagbox = this.$inputbox = this.$input = this.$suggest = null;
			}
		}
	};

})(this);
