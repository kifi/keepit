// creates (if needed) bookmarks structure under bookmarks bar(if not exists) /keepIt/private /keepIt/public
// returns all important bookmarks objects (root, bar, keepit, private and public)
// since chrome.bookmarks api is asynchronious code is messi 

function getBookMarks(callback) {
  $ = jQuery.noConflict()

	function findRoot() {
		chrome.bookmarks.get("0", function(bm){ 
			bookmarks.root=bm[0]; 
			findBar();
		});
	}

	function findBar() {
		chrome.bookmarks.getChildren(bookmarks.root.id, function(children) {
		  var bar = $.grep(children, function (bm) { 
		  	var foundIt = (bm.title.toLowerCase() == "bookmarks bar");
		  	console.log(bm.title + " == bookmarks bar : " + foundIt); 
		  	return foundIt; 
		  });
			if (bar.length === 0) {
				console.error("Could not find bookmarks.bar named 'bookmarks bar' at " + children);
				bar = children[0];
			}
			bookmarks.bar = bar[0];
  		internKeepIt();
		});
	}

	function internKeepIt() {

		chrome.bookmarks.getChildren(bookmarks.bar.id, function(children) {
			var res = $.grep(children, function (bm) { return bm.title == "KeepIt"; });
			if (res.length > 0) {
				bookmarks.keepIt = res.shift();
				internPrivate();
			} else 
				createKeepIt();
		});
	}



	function internPrivate() {
		chrome.bookmarks.getChildren(bookmarks.keepIt.id, function(children) {
			var res = $.grep(children, function (bm) { return bm.title=="private"; })
			if (res.length > 0) {
				bookmarks.private=res[0];
				internPublic();
			} else createPrivate();
		});
	}

	function internPublic() {
		chrome.bookmarks.getChildren(bookmarks.keepIt.id, function(children) {
			var res = $.grep(children, function (bm) { return bm.title=="public"; })
			if (res.length > 0) {
				bookmarks.public=res[0];
				done(bookmarks);
			} else createPublic();
		});
	}

	function createKeepIt(){
			if (!bookmarks.bar) {
				throw Error("Could not find bookmarks.bar at " + bookmarks);
			}
		chrome.bookmarks.create({'parentId': bookmarks.bar.id, 'title': 'KeepIt'}, function(bm) {
			bookmarks.keepIt=bm;
			internPrivate();
		});
	}

	function createPrivate(){
		chrome.bookmarks.create({'parentId': bookmarks.keepIt.id, 'title': 'private'}, function(bm) {
			bookmarks.private=bm;
			internPublic();
		});
	}

	function createPublic(){
		chrome.bookmarks.create({'parentId': bookmarks.keepIt.id, 'title': 'public'}, function(bm) {
			bookmarks.public=bm;
			done(bookmarks);
		});
	}


	function done(bookmarks){
		log("finish bokmars traversing");
		getBookMarks.prototype.cachedBookmarks = bookmarks; 
		if (callback)
			callback(bookmarks);
	}

	var bookmarks={};
	log("looking for bookmarks");

	if(getBookMarks.prototype.cachedBookmarks){
//		console.log("using cache: "+JSON.stringify(getBookMarks.prototype.cachedBookmarks));
		callback(getBookMarks.prototype.cachedBookmarks);
	} else
	findRoot();
}
