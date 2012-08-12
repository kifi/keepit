// creates (if needed) bookmarks structure under bookmarks bar(if not exists) /keepIt/private /keepIt/public
// returns all important bookmarks objects (root, bar, keepit, private and public)
// since chrome.bookmarks api is asynchronious code is messi 
function getBookMarks() {

		var bookmarks={};
		function getRoot() {
			chrome.bookmarks.get("0", function(bm){ 
				bookmarks.root=bm[0]; 
				getBar();
			});
		}

		function getBar() {
			console.log("in getBar root.id is "+bookmarks.root.id);
			chrome.bookmarks.getChildren(bookmarks.root.id, function(children) {
				children.forEach(function(bm) { 
					if (bm.title=="Bookmarks Bar")	{
						bookmarks.bar=bm;
						getKeepIt();
						return;
					}
				});
			});
		}

		function getKeepIt() {
			console.log("in getKeepIt bar.id is "+bookmarks.bar.id);
			chrome.bookmarks.getChildren(bookmarks.bar.id, function(children) {
				children.forEach(function(bm) { 
					if (bm.title=="KeepIt")	{
						bookmarks.keepIt=bm;
						getPrivate();
						return;
					}
				});
				if (!bookmarks.keepIt) createKeepIt();
			});
		}

		function createKeepIt(){
			console.log("in create keepIt");
			chrome.bookmarks.create({'parentId': bookmarks.bar.id, 'title': 'KeepIt'}, function(bm) {
				bookmarks.keepIt=bm;
				createPrivate();
				createPublic();
			});
		}

		function createPrivate(){
			chrome.bookmarks.create({'parentId': bookmarks.keepIt.id, 'title': 'private'}, function(bm) {
				bookmarks.private=bm;
			});
		}

		function createPublic(){
			chrome.bookmarks.create({'parentId': bookmarks.keepIt.id, 'title': 'public'}, function(bm) {
				bookmarks.public=bm;
			});
		}


		function getPrivate() {
			chrome.bookmarks.getChildren(bookmarks.keepIt.id, function(children) {
				children.forEach(function(bm) { 
					if (bm.title=="private")	{
						bookmarks.private=bm;
						getPublic();
						return;
					}
				});
				if (!bookmarks.private){
					createPrivate();
					getPublic();
				}
			});
		}

	function getPublic() {
			chrome.bookmarks.getChildren(bookmarks.keepIt.id, function(children) {
				children.forEach(function(bm) { 
					if (bm.title=="public")	{
						bookmarks.public=bm;
						return;
					}
				});
				if (!bookmarks.public){
					createPublic();
				}
			});
		}

	getRoot();
	return  bookmarks;
}
