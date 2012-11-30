// Generates a detailed CSS 2.1 selector for an element including, at a minimum, the tag name,
// id (if present), and any classes on the element and all ancestors between it and the body.
// e.g. "body>section#content>div.wrap>div#content-main.full>article.article>div#wikiArticle.page-content.boxed>table.standard-table>tbody>tr:nth-child(1)>td:nth-child(2)"
function generateSelector(el) {
  var delim = /[#\.:>'"]/;
  for (var parts = []; el; el = el.parentNode) {
    var sel = el.localName.toLowerCase();
    if (sel === "body") break;

    var id = el.id;
    if (id && !delim.test(id)) sel += "#" + id;

    sel += toArray(el.classList).map(function(c) {return delim.test(c) ? "" : "." + c}).join("");

    var children = toArray(el.parentNode.children);
    if (children.some(function(ch) {return ch !== el && ch.webkitMatchesSelector(sel)})) {
      sel += ":nth-child(" + (1 + children.indexOf(el)) + ")";
    }

    parts.unshift(sel);
  }
  parts.unshift("body");
  return parts.join(">");

  function toArray(o) {
    return Array.prototype.slice.apply(o);
  }
}
