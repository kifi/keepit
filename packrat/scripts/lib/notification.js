
var KifiNotification = {
	position: 'top-right',
	fadeInMs: 300,
	fadeOutMs: 100,
	showForMs: 3000,
	
	hideTimer: 0,
	notificationsCount: 0,
	
	add: function (params){
		if (typeof(params) == 'string') {
			params = {text:params};
		}
		if (params.click && typeof(params.click) == 'string') {
			params.click = function () { document.location = params.click };
		}
		
		var title = params.title, 
			text = params.text,
			link = params.link,
			image = params.image || '',
			sticky = params.sticky || false,
			popupClass = params.className || '',
			position = params.position || '',
			closeTime = params.time || this.showForMs,
			clickAction = params.click || $.noop,
			closeOnClick = params.closeOnClick || true;

		if ($('#kifi-notify-notice-wrapper').length == 0){
			$('body').append('<div id="kifi-notify-notice-wrapper"></div>');
		}
		
		this.notificationsCount++;
		var notifyId = this.notificationsCount;
		
		$(['before_open', 'after_open', 'before_close', 'after_close']).each(function (i, val){
			KifiNotification['_' + val + '_' + notifyId] = ($.isFunction(params[val])) ? params[val] : function (){}
		});

		this.hideTimer = closeTime;

		var image_str = (image != '') ? '<img src="' + image + '" class="kifi-notify-image" />' : '',
			className = (image != '') ? 'kifi-notify-with-image' : 'kifi-notify-without-image';

		render("html/notify_box.html", {
      'title': title,
      'text': text,
      'image': image_str,
      'notifyId': notifyId,
      'className': className,
      'popupClass': popupClass,
      'link': link
    }, function (html) {
			$('#kifi-notify-notice-wrapper').addClass(position).append(html);
			
			var item = $('#kifi-notify-item-' + notifyId);
			
			item.fadeIn(KifiNotification.fadeInMs, function (){
				KifiNotification['_after_open_' + notifyId](item);
			});
			
			if (!sticky){
				KifiNotification.startFadeTimer(item, notifyId);
			}
			
			item.bind('mouseenter', function (event){
				if (!sticky) { 
					clearTimeout(KifiNotification['_int_id_' + notifyId]);
					item.stop().css({ opacity: '', height: '' });
				}
				item.addClass('hover');
			}).bind('mouseleave', function (event){
				if (!sticky){
					KifiNotification.startFadeTimer(item, notifyId);
				}
				item.removeClass('hover');
			}).bind('click', function (event) {
				if (closeOnClick) {
					KifiNotification.removeSpecific(notifyId, {}, null, true);
				}
				clickAction();
			});
			
			$(item).find('.kifi-notify-close').click(function (){
				KifiNotification.removeSpecific(notifyId, {}, null, true);
			});
    });
		
		return notifyId;
	
	},
	
	fadeItem: function (e, unique_id, params, unbind_events){
		var params = params || {},
			fade = (typeof(params.fade) != 'undefined') ? params.fade : true,
			fadeOutMs = params.speed || this.fadeOutMs,
			manual_close = unbind_events;

		this['_before_close_' + unique_id](e, manual_close);

		if (unbind_events){
			e.unbind('mouseenter mouseleave');
		}

		var removeItem = function (unique_id, e, manual_close){
			e.remove();
			this['_after_close_' + unique_id](e, manual_close);
			if ($('.kifi-notify-item-wrapper').length == 0){
				$('#kifi-notify-notice-wrapper').remove();
			}
		}

		if (fade){
			e.animate({ opacity: 0 }, fadeOutMs, function (){
				e.animate({ height: 0 }, 300, function (){
					removeItem(unique_id, e, manual_close);
				})
			})
		}
		else {
			removeItem(unique_id, e);
		}
					
	},
	
	removeSpecific: function (unique_id, params, e, unbind_events){
		if (!e){
			var e = $('#kifi-notify-item-' + unique_id);
		}
		this.fadeItem(e, unique_id, params || {}, unbind_events);
		
	},
	
	startFadeTimer: function (e, unique_id){
		this['_int_id_' + unique_id] = setTimeout(function (){ 
			KifiNotification.fadeItem(e, unique_id);
		}, this.hideTimer);
	
	},

	stop: function (params){
		var before_close = ($.isFunction(params.before_close)) ? params.before_close : function (){};
		var after_close = ($.isFunction(params.after_close)) ? params.after_close : function (){};
		
		var wrap = $('#kifi-notify-notice-wrapper');
		before_close(wrap);
		wrap.fadeOut(function (){
			$(this).remove();
			after_close();
		});
	
	}
}

