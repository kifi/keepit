$(function() {
  $(".simple-plot").each(function drawGraph() {
    var $c = $(this), o = $c.data();

    var padding = 50;
    var dim = this.getBoundingClientRect();
    var w = dim.width - padding - padding;
    var h = dim.height - padding - padding;
    var svg = d3.select(this).append("svg:svg")
      .attr("width", dim.width)
      .attr("height", dim.height)
      .append("svg:g")
      .attr("transform", "translate(" + padding + "," + padding + ")");

    var xDomain = [o.xstart, o.xend];
    var yDomain = [o.ystart, o.yend];
    var xScale = scale("x").domain(xDomain).range([0, w]);
    var yScale = scale("y").domain(yDomain).range([h, 0]);

    var gHover = svg.append("svg:g").classed("hover", true).style("display", "none");
    var label = gHover.append("svg:text").attr("y", -4);

    function scale(a) { return d3.scale[o[a+"scale"] == "log" ? "log" : "linear"](); }
    function domain(a) { return [o[a+"start"], o[a+"end"]]; }

    !function drawAxes() {
      var xAxis = d3.svg.axis().scale(xScale).ticks(8).orient(o.xaxis == "top" ? "top" : "bottom");
      var yAxis = d3.svg.axis().scale(yScale).ticks(8).orient(o.yaxis == "right" ? "right" : "left");
      var xpos = (o.xaxis == "zero" ? 0 : yDomain[o.xaxis == "top" ? 1 : 0]);
      var ypos = (o.yaxis == "zero" ? 0 : xDomain[o.yaxis == "right" ? 1 : 0]);
      svg.append("svg:g").attr("class", "axis").attr("transform", "translate(0,"+ yScale(xpos) +")").call(xAxis);
      svg.append("svg:g").attr("class", "axis").attr("transform", "translate("+ xScale(ypos) +",0)").call(yAxis);
    }();

    $.getJSON(o.uri, function draw(obj) {
      svg.selectAll("circle")
        .data(obj.data)
        .enter()
        .append("circle")
        .attr("cx", function(d){ return xScale(d[0]); })
        .attr("cy", function(d){ return yScale(d[1]); })
        .attr("r", 4)
        .on("mouseover", function(d){
          label.text(d[2]);
          gHover.style("display", "").attr("transform", "translate(" + xScale(d[0]) + "," + yScale(d[1]) + ")");
        })
        .on("mouseout", function(d){ gHover.style("display", "none"); } )
      }
    );
  });
});
