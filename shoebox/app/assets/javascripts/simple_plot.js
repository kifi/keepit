$(function() {
  var $c = $(".simple-plot");

  var drawGraph = function(i,c) {
    var $c = $(c);

    var padding = 50
    var dim = $c[0].getBoundingClientRect();
    var w = dim.width - padding - padding;
    var h = dim.height - padding - padding;
    var svg = d3.select($c[0]).append("svg:svg")
      .attr("width", dim.width)
      .attr("height", dim.height)
      .append("svg:g")
      .attr("transform", "translate(" + padding + "," + padding + ")");

    var xDomain = [$c.data("xstart"), $c.data("xend")];
    var yDomain = [$c.data("ystart"), $c.data("yend")];
    var xScale = scale("x").domain(xDomain).range([0, w]);
    var yScale = scale("y").domain(yDomain).range([h, 0]);

    var gHover = svg.append("svg:g").classed("hover", true).style("display", "none");
    var label = gHover.append("svg:text").attr("y", -4);

    function scale(a) { if ($c.data(a+"scale") == "log") return d3.scale.log(); else return d3.scale.linear(); }
    function domain(a) { return [$c.data(a+"start"), $c.data(a+"end")]; }
    function drawAxes() {
      var xplace = $c.data("xaxis");
      var xorient = "bottom";
      var yplace = $c.data("yaxis");
      var yorient = "left";
      if (xplace == "top") { xorient = "top"; }
      if (yplace == "right") { yorient = "right" }
      var xAxis = d3.svg.axis().scale(xScale).ticks(8).orient(xorient);
      var yAxis = d3.svg.axis().scale(yScale).ticks(8).orient(yorient);
      var xpos = 0;
      var ypos = 0;
      if (xplace == "bottom") { xpos = yDomain[0]; } else if (xplace == "top") { xpos = yDomain[1]; }
      if (yplace == "left") { ypos = xDomain[0]; } else if (yplace == "right") { ypos = xDomain[1]; }
      svg.append("svg:g").attr("class", "axis").attr("transform", "translate(0,"+ yScale(xpos) +")").call(xAxis);
      svg.append("svg:g").attr("class", "axis").attr("transform", "translate("+ xScale(ypos) +",0)").call(yAxis);
    }

    drawAxes()

    function draw(o) {
      var dataset = o.data
      svg.selectAll("circle")
        .data(dataset)
        .enter()
        .append("circle")
        .attr("cx", function(d){ return xScale(d[0]); })
        .attr("cy", function(d){ return yScale(d[1]); })
        .attr("r", 4)
        .on('mouseover', function(d){
          label.text(d[2]);
          gHover.style("display", "").attr("transform", "translate(" + xScale(d[0]) + "," + yScale(d[1]) + ")");
        })
        .on('mouseout', function(d){ gHover.style("display", "none"); } )
    }

    $.getJSON($c.data("uri"), draw);
  }

  $c.each(drawGraph);

});
