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
      |function clear(el) {
      |  while (el.firstChild) {
      |    el.removeChild(el.firstChild);
      |  }
      |}
      |
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
      |function downloadNetscape(keepIds, filename) {
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
      |  tmp.href = window.URL.createObjectURL(new Blob([download.innerHTML], {type: "text/html"}));
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
      |  } else if (h === "allkeeps") {
      |    viewAllKeeps();
      |  } else {
      |    viewIndex();
      |  }
      |}
      |
      |window.onhashchange = function () { dispatch(); };
      |window.onload = function () { dispatch(); };
      |
      |function viewIndex() {
      |  var head = document.getElementById("head");
      |  var body = document.getElementById("body");
      |  clear(head);
      |  head.appendChild(drawMeHeader(index.me));
      |
      |  clear(body);
      |
      |  var libraries = document.createElement("h3");
      |  libraries.innerText = "Your libraries:";
      |  body.appendChild(libraries);
      |  var spacesList = drawSpaces(index.spaces)
      |  body.appendChild(spacesList);
      |
      |  var allKeeps = document.createElement("a");
      |  allKeeps.href = "#allkeeps";
      |  allKeeps.innerText = "All Your Keeps";
      |  body.appendChild(allKeeps);
      |  body.appendChild(document.createElement("br"));
      |}
      |
      |function viewAllKeeps() {
      |  var head = document.getElementById("head");
      |  var body = document.getElementById("body");
      |
      |  clear(body);
      |  var keeplist = document.createElement("ol");
      |  var sortedKeepIds = Object.keys(keeps).sort(function (k1, k2) {
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
      |  downloadButton.innerText = "Download all your keeps";
      |  downloadButton.onclick = function () { downloadNetscape(sortedKeepIds, "Everything.netscape.html"); };
      |  head.appendChild(downloadButton);
      |}
      |
      |function viewUser(userId) {
      |  var head = document.getElementById("head");
      |  var body = document.getElementById("body");
      |  var u = users[userId];
      |
      |  clear(head);
      |  head.appendChild(drawUserHeader(u));
      |  var downloadButton = document.createElement("button");
      |  downloadButton.innerText = "Download all these keeps";
      |  downloadButton.onclick = function () {
      |    var keepsToDownload = new Set();
      |    u.libraries.forEach(function (libId) {
      |      libraries[libId].keeps.forEach(function (keepId) {
      |        keepsToDownload.add(keepId);
      |      });
      |    });
      |    var username = (u.firstName.toLowerCase() + " " + u.lastName.toLowerCase()).replace(/\s+/g, "-");
      |    downloadNetscape(Array.from(keepsToDownload), username + ".netscape.html");
      |  };
      |  head.appendChild(downloadButton);
      |
      |  clear(body);
      |  body.appendChild(drawLibraries(u.libraries));
      |}
      |
      |function viewOrg(orgId) {
      |  var head = document.getElementById("head");
      |  var body = document.getElementById("body");
      |  var o = orgs[orgId];
      |
      |  clear(head);
      |  head.appendChild(drawOrgHeader(o));
      |  var downloadButton = document.createElement("button");
      |  downloadButton.innerText = "Download all these keeps";
      |  downloadButton.onclick = function () {
      |    var keepsToDownload = new Set();
      |    o.libraries.forEach(function (libId) {
      |      libraries[libId].keeps.forEach(function (keepId) {
      |        keepsToDownload.add(keepId);
      |      });
      |    });
      |    var orgname = o.name.toLowerCase().replace(/\s+/g, "-");
      |    downloadNetscape(Array.from(keepsToDownload), orgname + ".netscape.html");
      |  };
      |  head.appendChild(downloadButton);
      |
      |  clear(body);
      |  body.appendChild(drawLibraries(o.libraries));
      |}
      |
      |function viewLibrary(libId) {
      |  var head = document.getElementById("head");
      |  var body = document.getElementById("body");
      |  var l = libraries[libId];
      |  clear(head);
      |  head.appendChild(drawLibHeader(l));
      |  var downloadButton = document.createElement("button");
      |  downloadButton.innerText = "Download all these keeps";
      |  downloadButton.onclick = function () {
      |    var libname = l.name.toLowerCase().replace(/\s+/g, "-");
      |    downloadNetscape(Array.from(l.keeps), libname + ".netscape.html");
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
      |  var head = document.getElementById("head");
      |  var body = document.getElementById("body");
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
      |  messages.forEach(function (msg) {
      |    var el = document.createElement("li");
      |    el.innerText = msg.sentBy.firstName + ": " + msg.text.replace(lookHereRegex, "$1");
      |    big.appendChild(el);
      |  });
      |  return big;
      |}
      |</script>
    """.stripMargin
}
