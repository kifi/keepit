
var KifiNotification = {
	defaultParams: {
		wrapperClass: '',
		fadeInMs: 500,
		fadeOutMs: 200,
		showForMs: 7000,
		image: '',
		link: '',
		contentHtml: '',
		sticky: false,
		popupClass: '',
		clickAction: $.noop,
		closeOnClick: true
	},
	
	add: function (params){
		
		params = $.extend(this.defaultParams, params)

		if ($('#kifi-notify-notice-wrapper').length == 0){
			$('body').append('<div id="kifi-notify-notice-wrapper"></div>');
		}

		var imageHtml = (params.image != '') ? '<img src="' + params.image + '" class="kifi-notify-image" />' : '',
			innerClass = (params.image != '') ? 'kifi-notify-with-image' : 'kifi-notify-without-image';

		render("html/notify_box.html", {
      'title': params.title,
      'contentHtml': params.contentHtml,
      'image': imageHtml,
      'popupClass': params.popupClass,
      'innerClass': innerClass,
      'link': params.link
    }, function (html) {
			var $item = $(html);

			$('#kifi-notify-notice-wrapper').addClass(params.wrapperClass).append($item);

			$(['beforeOpen', 'afterOpen', 'beforeClose', 'afterClose']).each(function (i, val){
				$item.data(val, $.isFunction(params[val]) ? params[val] : $.noop);
			});
			
			$item.fadeIn(params.fadeInMs, function (){
				$item.data("afterOpen")();
			});
			
			if (!params.sticky){
				KifiNotification.startFadeTimer($item, params);
			}

			
			$item.bind('mouseenter', function (event){
				if (!params.sticky) {
					clearTimeout($item.data("fadeOutTimer"));
					$item.stop().css({ opacity: '', height: '' });
				}
			}).bind('mouseleave', function (event){
				if (!params.sticky){
					KifiNotification.startFadeTimer($item, params);
				}
			}).bind('click', function (event) {
				if (params.closeOnClick) {
					KifiNotification.removeSpecific($item, {}, true);
				}
				params.clickAction();
			});
			
			$item.find('.kifi-notify-close').click(function (){
				KifiNotification.removeSpecific($item, {}, true);
			});
    });
		
		return true;
	
	},
	
	fadeItem: function ($item, params, unbind_events){
		var params = params || {},
			fade = (typeof(params.fade) != 'undefined') ? params.fade : true,
			fadeOutMs = params.fadeOutMs || 300,
			manual_close = unbind_events;

		$item.data("beforeClose")($item, manual_close);

		if (unbind_events){
			$item.unbind('mouseenter mouseleave');
		}

		var removeItem = function ($item, manual_close){
			$item.data("afterClose")($item, manual_close);
			$item.remove();
			if ($('.kifi-notify-item-wrapper').length == 0){
				$('#kifi-notify-notice-wrapper').remove();
			}
		}

		if (fade){
			$item.animate({ opacity: 0 }, fadeOutMs)
			  .animate({ height: 0 }, 300, function (){
					removeItem($item, manual_close);
				})
		} else {
			removeItem($item, manual_close);
		}
					
	},
	
	removeSpecific: function ($item, params, unbind_events){
		this.fadeItem($item, params || {}, unbind_events);
	},
	
	startFadeTimer: function ($item, params){
		$item.data("fadeOutTimer", setTimeout(function (){ 
			KifiNotification.fadeItem($item, params);
		}, params.showForMs));
	},

	stop: function (params){
		params.beforeClose = params.beforeClose || $.noop;
		params.beforeClose = params.beforeClose || $.noop;
		
		var wrap = $('#kifi-notify-notice-wrapper');
		params.beforeClose(wrap);
		wrap.fadeOut(function (){
			$(this).remove();
			params.afterClose();
		});
	
	}
}

