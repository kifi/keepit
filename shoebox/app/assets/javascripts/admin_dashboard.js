$(function() {
  var $c = $(".growth-chart");

  var drawGraph = function(i,c) {
    var $c = $(c);
    $.getJSON($c.data("uri"), draw);

    var m = [30, 30, 30, 30], dim = $c[0].getBoundingClientRect();
    var w = dim.width - m[1] - m[3];
    var h = dim.height - m[0] - m[2];
    var svg = d3.select($c[0]).append("svg:svg")
        .attr("width", w + m[1] + m[3])
        .attr("height", h + m[0] + m[2])
        .on("mousemove", onMouseMove)
      .append("svg:g")
        .attr("transform", "translate(" + m[3] + "," + m[0] + ")");
    var gXAxis = svg.append("svg:g")
        .attr("class", "x axis")
        .attr("transform", "translate(0," + h + ")");
    var gYAxis = svg.append("svg:g")
        .attr("class", "y axis")
        .attr("transform", "translate(" + w + ",0)");
    var path = svg.append("svg:path")
        .attr("class", "line");
    var gHover = svg.append("svg:g")
        .classed("hover", true)
        .style("display", "none");
    var dateHover = gHover.append("svg:text")
        .attr("y", -24);
    var countHover = gHover.append("svg:text")
        .attr("y", -7)
        .classed("count", true);
    var dotHover = gHover.append("svg:circle")
        .attr("r", 3);

    var x = d3.time.scale().range([0, w]);
    var y = d3.scale.linear().range([h, 0]);
    var xAxis = d3.svg.axis().scale(x).tickSize(-h).tickSubdivide(true);
    var yAxis = d3.svg.axis().scale(y).ticks(4).orient("right");

    var line = d3.svg.line()
      .x(function(d,i) { return x(date(i)); })
      .y(function(d) { return y(d); })
      .interpolate("monotone");

    var d0, d0d, counts;
    function date(i) {
      var d = new Date(d0);
      d.setDate(d0d + i);
      return d;
    };
    date.index = function(d) {
      return Math.round((d - d0) / (24 * 60 * 60 * 1000));
    };

    function draw(o) {
      d0 = d3.time.format("%Y-%m-%d").parse(o.day0);
      d0d = d0.getUTCDate();

      counts = new Array(o.counts.length);
      o.counts.forEach(function(c, i) {
        counts[i] = c + (counts[i-1] || 0);
      });

      x.domain([d0, date(counts.length - 1)]);
      y.domain([0, d3.max(counts)]).nice();

      gXAxis.call(xAxis);
      gYAxis.call(yAxis);
      path.attr("d", line(counts));
    }

    function onMouseMove() {
      var i = date.index(x.invert(d3.mouse(svg[0][0])[0]));
      if (i >= 0 && i < counts.length) {
        var d = date(i), n = counts[i];
        dateHover.text(d3.time.format("%a %b %d")(d).replace(" 0", " "));
        countHover.text(n);
        gHover.style("display", "").attr("transform", "translate(" + x(d) + "," + y(n) + ")");
      } else {
        gHover.style("display", "none");
      }
    }
  }

  $c.each(drawGraph);

});
