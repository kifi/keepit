package com.keepit.export

object HackyExportAssets {
  val index =
    """
      |<!doctype html>
      |<head>
      |<meta charset="UTF-8">
      |</head>
      |<img
      |  src="data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20viewBox%3D%220%200%2072.7%2028.1%22%20width%3D%2272.7%22%20height%3D%2228.1%22%3E%3Cg%20fill%3D%22%23333%22%3E%3Cpath%20d%3D%22M38.9%2014.5h.7l2.4-3.7h5.2L43%2016.4l4.2%206.3h-5.1l-2.6-4.5h-.6v4.5h-4.3V5.4h4.3v9.1zM50.9%205.1c.3%200%20.7.1%201%20.2.3.1.6.3.8.5.2.2.4.5.5.8.1.3.2.6.2%201%200%20.3-.1.7-.2%201-.1.3-.3.6-.5.8-.2.2-.5.4-.8.5-.3.1-.6.2-1%20.2-.3%200-.7-.1-1-.2-.3-.1-.6-.3-.8-.5-.2-.2-.4-.5-.5-.8-.1-.3-.2-.6-.2-1%200-.3.1-.7.2-1%20.1-.3.3-.6.5-.8.2-.2.5-.4.8-.5.3-.1.7-.2%201-.2zM53.1%2023h-4.3V11h4.3v12zM64.4%2011h-2.7v-.4c0-.5.1-.8.3-1.1.2-.2.5-.3.9-.3s.7%200%20.9.1l.6.3V5.5c-.2-.1-.5-.1-.8-.2-.4-.1-.9-.1-1.4-.1-.7%200-1.3.1-1.9.3-.6.2-1.1.5-1.5%201-.4.4-.8%201-1%201.6-.2.7-.4%201.4-.4%202.3v.6h-1.9v3.8h1.9V23h4.3v-8.2h2.7V11zM68.9%205.1c.3%200%20.7.1%201%20.2.3.1.6.3.8.5.2.2.4.5.5.8.1.3.2.6.2%201%200%20.3-.1.7-.2%201-.1.3-.3.6-.5.8-.2.2-.5.4-.8.5-.3.1-.6.2-1%20.2-.3%200-.7-.1-1-.2-.3-.1-.6-.3-.8-.5-.2-.2-.4-.5-.5-.8-.1-.3-.2-.6-.2-1%200-.3.1-.7.2-1%20.1-.3.3-.6.5-.8.2-.2.5-.4.8-.5.3-.1.7-.2%201-.2zM71.1%2023h-4.3V11h4.3v12z%22%2F%3E%3C%2Fg%3E%3Cpath%20fill%3D%22%2371C885%22%20d%3D%22M27%206c0-3.3-2.7-6-6-6H6C2.7%200%200%202.7%200%206v16c0%203.3%202.7%206%206%206h15c3.3%200%206-2.7%206-6V6z%22%2F%3E%3Cg%20fill%3D%22%23FFF%22%3E%3Cpath%20d%3D%22M6.3%2014.1c.1.1.1.1%200%200%20.4.3.8.5.9.6.1%200%20.1.1.2.1%201.8.8%204.7.3%206%20.1%200-.4-.1-.8-.1-1.2-.4.1-1.1.2-1.8.3-1.6.1-2.9%200-3.7-.3-.8-.3-1.3-.9-1.6-1.4-.2-.5.1-1.2.1-1.2.8-1.8%203.8-.6%203.8-.6s-1-3.2.5-4c1.5-.8%202.1%201%202.1%201s.1-.6.6-1.3c0%200-.8-1.8-3.2-.8-.1.1-.3.3-.6.5-.5.6-.8%201.5-.7%202.6V9h-.5c-1%200-1.9.3-2.5.9-.2.2-.4.5-.5.7-.3.7-.3%201.4-.1%202.1.2.5.6%201%201.1%201.4zM22.8%2015.6c-.2-.6-.7-1.2-1.3-1.6-.2-.1-.5-.3-1-.5-1.9-.7-4.4-.3-5.6%200%200%20.2.1.8.2%201.2.4-.1.8-.2%201.5-.3%201.6-.1%202.9%200%203.7.3.8.3%201.3.9%201.6%201.4.2.5-.1%201.2-.1%201.2-.8%201.8-3.8.6-3.8.6s1.1%203.5-.5%204c-1.8.6-2.4-1-2.4-1.2%200%200-.2.6-.6%201.1%200%200%20.9%202%203.4%201.3%200%200%20.5-.2.8-.5.5-.6.8-1.5.8-2.6v-.5h.5c1%200%201.9-.3%202.5-.9.2-.2.4-.5.5-.7%200-.9.1-1.6-.2-2.3z%22%2F%3E%3Cpath%20d%3D%22M21.4%2011c.6%201.2-.5%202-.5%202%20.7.3%201.1.6%201.1.6.8-.8%201.2-2.4%200-3.9-.6-.6-1.4-.9-2.5-.8H19v-.5c0-1.1-.3-2-.8-2.6-.2-.2-.4-.4-.7-.5-.7-.4-1.4-.4-2.1-.1-5.6%202.5%201%2014-3.5%2016.4-.5.3-1.2-.1-1.2-.1-1.7-.9-.6-4-.6-4s-3%201-3.8-.5c-.6-1.2.4-2%20.4-2-.1-.1-.6-.3-1-.6-1.1.9-1.1%202.6.1%203.9.6.6%201.4.9%202.5.8h.5v.5c0%201.1.3%202%20.8%202.6.2.2.4.4.7.5.7.4%201.4.4%202.1.1.8-.3%201.5-1.1%202-2.3%201.6-3.8-2.5-12.3%201.5-14.1.4-.2%201.1.1%201.1.1%201.7.9.6%204%20.6%204s3.1-1.1%203.8.5z%22%2F%3E%3C%2Fg%3E%3C%2Fsvg%3E"
      |  alt="Kifi Logo"
      |/>
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
      |  var dl = document.createElement("dl");
      |  var bookmarkDocument = (
      |    '<!DOCTYPE NETSCAPE-Bookmark-file-1>\n' +
      |    '<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">\n' +
      |    '<!--This is an automatically generated file.\n' +
      |    'It will be read and overwritten.\n' +
      |    'Do Not Edit! -->\n' +
      |    '<Title>Kifi Bookmarks Export</Title>\n' +
      |    '<H1>Bookmarks</H1>\n'
      |  );
      |
      |  var keepLibraries = [];
      |  var keepsWithoutLibraries = [];
      |  var seenLibraryIds = {};
      |
      |  keepIds.forEach(function (keepId) {
      |    var keep = keeps[keepId];
      |
      |    if (keep.libraries.length === 0) {
      |      keepsWithoutLibraries.push(keep);
      |    } else {
      |      keep.libraries.forEach(function (libraryId) {
      |        var wasSeen = seenLibraryIds[libraryId];
      |        seenLibraryIds[libraryId] = true;
      |        if (!wasSeen) {
      |          var library = libraries[libraryId];
      |          if (library) {
      |            keepLibraries.push(library);
      |          } else {
      |            keepsWithoutLibraries.push(keep);
      |          }
      |        }
      |      });
      |    }
      |  });
      |
      |  keepLibraries.forEach(function (library) {
      |    var libraryKeeps = library.keeps.filter(isMemberOf(keepIds)).map(getPropertyOf(keeps));
      |    bookmarkDocument += '<DT><H3 FOLDED ADD_DATE="' + libraryKeeps[0].keptAt + '">' + library.name + '</H3>\n';
      |    bookmarkDocument += '<DL><p>\n';
      |    bookmarkDocument += libraryKeeps.map(renderKeep).join('');
      |    bookmarkDocument += '</DL><p>\n'
      |  });
      |
      |  if (keepsWithoutLibraries.length) {
      |    bookmarkDocument += '<DT><H3 FOLDED ADD_DATE="' + keepsWithoutLibraries[0].keptAt + '">Keeps without libraries</H3>\n';
      |    bookmarkDocument += '<DL><p>\n';
      |    bookmarkDocument += keepsWithoutLibraries.map(renderKeep).join('');
      |    bookmarkDocument += '</DL><p>\n'
      |  }
      |
      |  var tmp = document.createElement('A');
      |  tmp.href = 'data:text/html;base64,' + btoa(unescape(encodeURIComponent(bookmarkDocument)));
      |  tmp.download = filename;
      |  tmp.click();
      |  tmp.remove();
      |}
      |
      |function getPropertyOf(o) {
      |  return function (p) { return o[p]; };
      |}
      |
      |function isMemberOf(set) {
      |  return function (o) { return set.indexOf(o) !== -1; };
      |}
      |
      |function renderKeep(keep) {
      |  var keepElement = document.createElement("a");
      |  var tags = getTags(keep);
      |  keepElement.innerText = keep.title ? keep.title : keep.url;
      |  keepElement.href = keep.url;
      |  keepElement.setAttribute("add_date", keep.keptAt / 1000);
      |  if (tags.length) {
      |    keepElement.setAttribute("tags", tags.join(","));
      |  }
      |  return "<DT>" + keepElement.outerHTML + "\n";
      |}
      |
      |function getTags(keep) {
      |  var libraryNames = keep.libraries.map(function (id) { return libraries[id] && libraries[id].name; });
      |  return keep.tags.concat(libraryNames);
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
