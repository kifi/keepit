$(window).scroll(function () {
	$('html').toggleClass('scroll', $(this).scrollTop() > 0);
});
