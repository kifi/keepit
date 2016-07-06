package com.keepit.export

object HackyExportAssets {
  val index =
    """
      |<!doctype html>
      |<head>
      |<meta charset="UTF-8">
      |</head>
      |
      |<div id="head"></div>
      |<div id="body"></div>
      |
      |<script src="export.js" charset="UTF-8"></script>
      |<script>
      |var head = document.getElementById("head");
      |var body = document.getElementById("body");
      |function clear(el) {
      |  while (el.firstChild) {
      |    el.removeChild(el.firstChild);
      |  }
      |}
      |function unique(array) {
      |  var seen = {};
      |  return array.filter(function(item) {
      |    return seen.hasOwnProperty(item) ? false : (seen[item] = true);
      |  });
      |}
      |// Function to asynchronously add HTML elements to a particular node
      |function incrementallyFillElement(el, items, fill) {
      |  if (items.length > 0) {
      |    window.setTimeout(function() {
      |      for (var i=0; i < 100 && i < items.length; i++) {
      |        el.appendChild(fill(items[i]));
      |      }
      |      incrementallyFillElement(el, items.slice(100), fill);
      |    }, 0);
      |  }
      |}
      |
      |function triggerNetscapeDownload(keepIds, filename) {
      |  var download = document.createElement("div");
      |  keepIds.forEach(function (keepId) {
      |    var keep = keeps[keepId];
      |    var keepElement = document.createElement("a");
      |    keepElement.innerText = keep.title ? keep.title : keep.url;
      |    keepElement.href = keep.url;
      |    download.appendChild(keepElement);
      |    download.appendChild(document.createElement("br"));
      |  });
      |
      |  var tmp = document.createElement("a");
      |  tmp.href = "data:text/html;utf8," + download.innerHTML;
      |  tmp.download = filename;
      |  tmp.click();
      |  tmp.remove();
      |}
      |
      |// Main "router". Inspects the hash and loads the appropriate view
      |function dispatch() {
      |  var h = window.location.hash.substr(1);
      |  if (users[h]) {
      |    viewUser(h);
      |  } else if (orgs[h]) {
      |    viewOrg(h);
      |  } else if (libraries[h]) {
      |    viewLibrary(h);
      |  } else if (keeps[h]) {
      |    viewKeep(h);
      |  } else if (h === "mykeeps") {
      |    viewAllKeeps();
      |  } else if (h === "mydiscussions") {
      |    viewDiscussions();
      |  } else if (h === "readme") {
      |    viewReadme();
      |  } else {
      |    viewIndex();
      |  }
      |}
      |
      |window.onhashchange = function () { dispatch(); };
      |window.onload = function () { dispatch(); };
      |
      |function viewIndex() {
      |  clear(head);
      |  head.appendChild(drawMeHeader(index.me));
      |
      |  clear(body);
      |
      |  var allKeeps = document.createElement("a");
      |  allKeeps.href = "#mykeeps";
      |  allKeeps.innerText = "All Your Keeps";
      |  body.appendChild(allKeeps);
      |  body.appendChild(document.createElement("br"));
      |
      |  var discussions = document.createElement("a");
      |  discussions.href = "#mydiscussions";
      |  discussions.innerText = "Your Discussions";
      |  body.appendChild(discussions);
      |  body.appendChild(document.createElement("br"));
      |
      |  var readme = document.createElement("a");
      |  readme.href = "#readme";
      |  readme.innerText = "Technical information about this export";
      |  body.appendChild(readme);
      |  body.appendChild(document.createElement("br"));
      |
      |  var libraries = document.createElement("h3");
      |  libraries.innerText = "Your libraries:";
      |  body.appendChild(libraries);
      |  var spacesList = drawSpaces(index.spaces)
      |  body.appendChild(spacesList);
      |}
      |
      |function viewAllKeeps() {
      |  clear(body);
      |  var keeplist = document.createElement("ol");
      |  var sortedKeepIds = Object.keys(keeps).sort(function (k1, k2) {
      |    return keeps[k2].lastActivityAt - keeps[k1].lastActivityAt; // most recent first
      |  });
      |  incrementallyFillElement(keeplist, sortedKeepIds, function (keepId) {
      |    var k = drawKeep(keeps[keepId]);
      |    var el = document.createElement("li")
      |    el.appendChild(k);
      |    return el;
      |  });
      |  body.appendChild(keeplist);
      |
      |  clear(head);
      |  var downloadButton = document.createElement("button");
      |  downloadButton.innerText = "Download these keeps";
      |  downloadButton.onclick = function () { triggerNetscapeDownload(sortedKeepIds, "Everything.netscape.html"); };
      |  head.appendChild(downloadButton);
      |}
      |
      |function viewDiscussions() {
      |  clear(body);
      |  var keeplist = document.createElement("ol");
      |  var sortedKeepIds = Object.keys(keeps).filter(function (k) {
      |    return keeps[k].messages.length > 0;
      |  }).sort(function (k1, k2) {
      |    // Sorted reverse chronologically (most recent first)
      |    return -( keeps[k1].lastActivityAt - keeps[k2].lastActivityAt );
      |  });
      |  incrementallyFillElement(keeplist, sortedKeepIds, function (keepId) {
      |    var k = drawKeep(keeps[keepId]);
      |    var el = document.createElement("li")
      |    el.appendChild(k);
      |    return el;
      |  });
      |  body.appendChild(keeplist);
      |
      |  clear(head);
      |  var downloadButton = document.createElement("button");
      |  downloadButton.innerText = "Download these keeps";
      |  downloadButton.onclick = function () { triggerNetscapeDownload(sortedKeepIds, "Discussions.netscape.html"); };
      |  head.appendChild(downloadButton);
      |}
      |
      |function viewReadme() {
      |  clear(head);
      |  var readmeHeader = document.createElement("h2");
      |  readmeHeader.innerText = "Technical Documentation";
      |  head.appendChild(readmeHeader);
      |
      |  clear(body);
      |  body.appendChild(document.createElement("p"));
      |  body.lastChild.innerText = "\
      |    All of your export data is located in \"export.js\", a javascript file that\
      |    constructs five objects: [index, users, orgs, libraries, keeps].";
      |
      |  body.appendChild(document.createElement("p"));
      |  body.lastChild.innerText = "\
      |    You are currently browsing through your export data using a standalone javascript\
      |    \"export explorer\". This script performs two main tasks: data presentation (allowing you to interactively\
      |    browse your export data from your browser), and data formatting (allowing you to choose a subset\
      |    of your data and download it in a format suitable for import into another service)."
      |
      |  body.appendChild(document.createElement("p"));
      |  body.lastChild.innerText = "\
      |    It is our hope that the capabilities of this explorer will suffice for most purposes, but\
      |    if not we hope that the provided explorer will serve as a useful starting point for any\
      |    modifications you may wish to explore."
      |
      |  body.appendChild(document.createElement("p"));
      |  body.lastChild.innerText = "\
      |    The most useful code snippet is likely to be the function triggerNetscapeDownload, which\
      |    formats a selection of keeps into the Netscape Bookmark File Format, then triggers a download.\
      |    If you want to export your data in any HTML-based format, it should be fairly straightforward\
      |    to modify that method."
      |
      |  body.appendChild(document.createElement("p"));
      |  body.lastChild.innerText = "\
      |    As a last resort, you may want to directly investigate \"export.js\" and directly access the data.\
      |    Do note that the file is UTF-8 encoded."
      |}
      |
      |function viewUser(userId) {
      |  var u = users[userId];
      |  clear(head);
      |  head.appendChild(drawUserHeader(u));
      |  var downloadButton = document.createElement("button");
      |  downloadButton.innerText = "Download these keeps";
      |  downloadButton.onclick = function () {
      |    var keepsToDownload = unique(u.libraries.reduce(function (acc, libId) {
      |      return acc.concat(libraries[libId].keeps);
      |    }, []));
      |    var username = (u.firstName.toLowerCase() + " " + u.lastName.toLowerCase()).replace(/\s+/g, "-");
      |    triggerNetscapeDownload(keepsToDownload, username + ".netscape.html");
      |  };
      |  head.appendChild(downloadButton);
      |
      |  clear(body);
      |  body.appendChild(drawLibraries(u.libraries));
      |}
      |
      |function viewOrg(orgId) {
      |  var o = orgs[orgId];
      |
      |  clear(head);
      |  head.appendChild(drawOrgHeader(o));
      |  var downloadButton = document.createElement("button");
      |  downloadButton.innerText = "Download these keeps";
      |  downloadButton.onclick = function () {
      |    var keepsToDownload = unique(o.libraries.reduce(function (acc, libId) {
      |      return acc.concat(libraries[libId].keeps);
      |    }, []));
      |    var orgname = o.name.toLowerCase().replace(/\s+/g, "-");
      |    triggerNetscapeDownload(keepsToDownload, orgname + ".netscape.html");
      |  };
      |  head.appendChild(downloadButton);
      |
      |  clear(body);
      |  body.appendChild(drawLibraries(o.libraries));
      |}
      |
      |function viewLibrary(libId) {
      |  var l = libraries[libId];
      |  clear(head);
      |  head.appendChild(drawLibHeader(l));
      |  var downloadButton = document.createElement("button");
      |  downloadButton.innerText = "Download these keeps";
      |  downloadButton.onclick = function () {
      |    var libname = l.name.toLowerCase().replace(/\s+/g, "-");
      |    triggerNetscapeDownload(l.keeps, libname + ".netscape.html");
      |  };
      |  head.appendChild(downloadButton);
      |
      |  clear(body);
      |  var keepsList = document.createElement("ol");
      |  var sortedKeepIds = l.keeps.sort(function (keep1, keep2) {
      |    return keeps[keep2].lastActivityAt - keeps[keep1].lastActivityAt;
      |  });
      |  incrementallyFillElement(keepsList, sortedKeepIds, function (keepId) {
      |    var el = document.createElement("li");
      |    el.appendChild(drawKeep(keeps[keepId]));
      |    return el;
      |  });
      |  body.appendChild(keepsList);
      |}
      |
      |function viewKeep(keepId) {
      |  var keep = keeps[keepId];
      |  clear(head);
      |  if (keep.title) {
      |    var title = document.createElement("h2");
      |    title.innerText = keep.title;
      |    head.appendChild(title);
      |  }
      |  var url = document.createElement("a");
      |  url.innerText = keep.url;
      |  url.href = keep.url;
      |  url.target = "_blank";
      |  head.appendChild(url);
      |
      |  clear(body);
      |  if (keep.note) {
      |    body.appendChild(drawNoteElement(keep.note));
      |  }
      |  if (keep.messages.length > 0) {
      |    body.appendChild(drawMessages(keep.messages));
      |  }
      |}
      |
      |function drawMeHeader(me) {
      |  var e = document.createElement("h1");
      |  e.innerText = me.firstName + " " + me.lastName + "'s Kifi export";
      |  return e;
      |}
      |
      |function drawUserHeader(user) {
      |  var e = document.createElement("h2");
      |  e.innerText = user.firstName + " " + user.lastName;
      |  return e;
      |}
      |function drawOrgHeader(org) {
      |  var e = document.createElement("h2");
      |  e.innerText = org.name;
      |  return e;
      |}
      |
      |function drawLibHeader(lib) {
      |  var e = document.createElement("h2");
      |  e.innerText = lib.name + " (" + lib.numKeeps + " keeps)";
      |  return e;
      |}
      |
      |function drawSpace(space) {
      |  var el = document.createElement("a");
      |  if (space.org) {
      |    el.href = "#" + space.org.id;
      |    el.innerText = space.org.name;
      |  } else if (space.user) {
      |    el.innerText = space.user.firstName + " " + space.user.lastName;
      |    el.href = "#" + space.user.id;
      |  }
      |  return el;
      |}
      |
      |function drawSpaces(spaces) {
      |  var big = document.createElement("ol");
      |  spaces.forEach(function (sp) {
      |    var el = document.createElement("li");
      |    el.appendChild(drawSpace(sp));
      |    big.appendChild(el);
      |  });
      |  return big;
      |}
      |
      |function drawLibrary(lib) {
      |  var el = document.createElement("a");
      |  el.href = "#" + lib.id;
      |  el.innerText = lib.name;
      |  return el;
      |}
      |
      |function drawKeep(keep) {
      |  var el = document.createElement("a");
      |  el.href = "#" + keep.id;
      |  el.innerText = keep.title ? keep.title : keep.url;
      |  return el;
      |}
      |
      |function drawLibraries(libIds) {
      |  var sortedLibIds = libIds.sort(function (lib1, lib2) {
      |    return libraries[lib1].name.localeCompare(libraries[lib2].name);
      |  });
      |  var big = document.createElement("ol");
      |  sortedLibIds.forEach(function (libId) {
      |    var el = document.createElement("li");
      |    el.appendChild(drawLibrary(libraries[libId]));
      |    big.appendChild(el);
      |  });
      |  return big;
      |}
      |
      |function drawNoteElement(note) {
      |  var el = document.createElement("p");
      |  el.innerText = note;
      |  return el;
      |}
      |
      |
      |var lookHereRegex = /\[([^\]\\]*(?:\\[\]\\][^\]\\]*)*)\]\(x-kifi-sel:([^\)\\]*(?:\\[\)\\][^\)\\]*)*)\)/;
      |function drawMessages(messages) {
      |  var big = document.createElement("ol");
      |  messages.sort(function (m1, m2) {
      |    return m1.sentAt - m2.sentAt; // oldest first
      |  }).forEach(function (msg) {
      |    var el = document.createElement("li");
      |    el.innerText = msg.sentBy.firstName + ": " + msg.text.replace(lookHereRegex, "$1");
      |    big.appendChild(el);
      |  });
      |  return big;
      |}
      |</script>
    """.stripMargin
}
