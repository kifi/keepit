  // Selection libs

  jQuery.fn.getNodePath = function () {
    if (this.length != 1) throw 'Requires one element.';

    var path = [], node = this;
    while (node.length) {
      var realNode = node[0], name = realNode.localName;
      if (!name) break;
      name = name.toLowerCase();
      if(name === "body") break;
      var nodeDescriptor = { "tag": "", "classes": [], "id": "", "append": ""};

      nodeDescriptor["tag"] = name;

      if(realNode.className && realNode.className.indexOf('.') === -1) {
        $.each(realNode.className.split(/ /g),function(i,c) {
          nodeDescriptor["classes"].push(c);
        });
        //name += "." + realNode.className.replace(/ /g,'.');
      }
      if(realNode.id) {
        nodeDescriptor["id"] = realNode.id;
        //name += "#" + realNode.id.replace(/ /g, '#');
      }

      var parent = node.parent();
      var currentSelector = name + (realNode.id ? '#'+realNode.id : '') + (realNode.className ? "."+realNode.className.replace(/ /g,".") : '')
      var siblings = parent.children(currentSelector);
      if (siblings.length > 1) {
        nodeDescriptor["append"] = ':eq(' + siblings.index(node) + ')';
        //name += ':eq(' + siblings.index(realNode) + ')';
      }
      path.push(nodeDescriptor);
      //path = name + (path ? '>' + path : '');
      node = parent;
    }
    return path.reverse();
  };

  var nodePathToSelector = function(nodePath) {
    var path = [];
    $(nodePath).each(function(i,e) {
      var classes="",id=e.id,append=e.append;
      if(e.classes.length > 0) {
        classes = "." + e.classes.join(".");
      }
      if(id.length > 0) {
        id = "#" + id;
      }
      var selector = $.trim(e.tag + classes + id + append);
      if(selector.length > 0)
        path.push(selector);
    });
    return path.join(" ");
  }

  var leastCommonSelector = function(nodePath) {
    var $originalNode = $(nodePathToSelector(nodePath));
    if($originalNode.length === 0) {
      throw 'Node path did not return any elements! Oops?';
    }
    // The approach:
    //  - Starting with the least specific (html), remove whole selectors
    console.log($originalNode);
    for(i=0;i<nodePath.length;i++) {
      if(nodePath[i]["classes"].length > 0 || nodePath[i]["id"] !== '')
        nodePath[i]["tag"] = "";
      else {
        nodePath[i]["tag"] = "";
        nodePath[i]["append"] = "";
      }
      $newNode = $(nodePathToSelector(nodePath));
      if($originalNode.is($newNode)) {
        console.log($newNode.selector,"is the same!!!");
      }
      else {
        console.log($newNode.selector,"is NOT the same!!!");
        break;
      }
    }
  }
/*
  $(document).bind("ready",function() {
    $("body").delegate("*","mouseup",function() {
      console.log(this);
      var range = $.Range.current();
      var selection = {};
      var start = range.start();
      var end = range.end();
      if(start.container == end.container && start.offset === end.offset ) {
        console.log("click!",start, this, $(this))
        selection['node'] = $(start.container.parentNode).getNodePath();
        selection['parent'] = $(range.parent().parentNode).getNodePath();
        selection['nodeSelector'] = nodePathToSelector(selection['node']);
      }
      else {
        console.log("drag!",$(start.container.parentNode),$(end.container.parentNode))
        selection['startNode'] = $(start.container.parentNode).getNodePath();
        selection['endNode'] = $(end.container.parentNode).getNodePath();
        selection['startOffset'] = start.offset;
        selection['endOffset'] = end.offset;
      }
      console.log(selection);
      console.log(nodePathToSelector(selection.parent));

      if(selection.node) {
        $(nodePathToSelector(selection.parent)).addClass('kifi_selection');
      }
      else if(selection.startNode && selection.endNode) {
        var $start = $(nodePathToSelector(selection.startNode)).addClass('kifi_selection');
        var $end = $(nodePathToSelector(selection.endNode)).addClass('kifi_selection');
        var commonParent = $start.parents().has($end).first().addClass('kifi_selection');
        console.log(commonParent)
      }
      //var path = $(element).getNodePath();

      //console.log(range, element, path, nodePathToSelector(path));
      //leastCommonSelector(path);
      //$(nodePathToSelector(path)).css({"background-color":"#ff0000"})
      return false;
    });
  });


  $(document).ready(function(){
     $("body *").mouseover(function(){
        $(this).addClass('kifi_selection');
        $(this).find("*").addClass('kifi_selection');
        return false;
     });
          
     $('body *').mouseout(function(){
        $(this).removeClass('kifi_selection');
        $(this).find("*").removeClass('kifi_selection');
        return false;
     });
  });
*/