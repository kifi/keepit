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

    var defaultPath = svg.append("svg:path")
        .attr("class", "line default");
    var gHoverDef = svg.append("svg:g")
        .classed("hover", true)
        .style("display", "none");
    var countHoverDef = gHoverDef.append("svg:text")
        .attr("y", -14)
        .classed("count default", true);
    var samplesHoverDef = gHoverDef.append("svg:text")
        .attr("y", -4)
        .classed("samples default", true);

    var path = svg.append("svg:path")
        .attr("class", "line");
    var gHover = svg.append("svg:g")
        .classed("hover", true)
        .style("display", "none");
    var dateHover = gHover.append("svg:text")
        .attr("y", -28);
    var countHover = gHover.append("svg:text")
        .attr("y", -14)
        .classed("count", true);
    var samplesHover = gHover.append("svg:text")
        .attr("y", -4)
        .classed("samples", true);

    var descText = svg.append("svg:text")
        .attr("x", -20).attr("y", -12)
        .classed("desc-text", true)

    var x = d3.time.scale().range([0, w]);
    var y = d3.scale.linear().range([h, 0]);
    var xAxis = d3.svg.axis().scale(x).tickSize(-h).tickSubdivide(true);
    var yAxis = d3.svg.axis().scale(y).ticks(4).orient("right");

    var line = d3.svg.line()
      .x(function(d,i) { return x(date(i)); })
      .y(function(d) { return y(d); })
      .interpolate("monotone");

    var d0, d0d, counts, defaultCounts, samples, defaultSamples, max = 0;
    function date(i) {
      var d = new Date(d0);
      d.setDate(d0d + i);
      return d;
    };
    date.index = function(d) {
      return Math.round((d - d0) / (24 * 60 * 60 * 1000));
    };

    function formatPercent(n) {
      return Number((n*100).toFixed(2));
    }

    function draw(o) {
      d0 = d3.time.format("%Y-%m-%d").parse(o.day0);

      counts = o.counts.map(formatPercent);
      samples = o.samples

      descText.text("Average: " + formatPercent(o.avg) + "% / " + formatPercent(o.defaultAvg) + "% | " +
                    "Samples: " + d3.sum(o.samples))

      defaultSamples = o.defaultSamples
      defaultCounts = o.defaultCounts.map(formatPercent);
      d0d = d0.getUTCDate();

      max = d3.max(counts.concat(defaultCounts))*1.1; // make sure we aren't too close to the edge
      x.domain([d0, date(counts.length - 1)]);
      y.domain([0, max]).nice();

      gXAxis.call(xAxis);
      gYAxis.call(yAxis);
      path.attr("d", line(counts));
      defaultPath.attr("d", line(defaultCounts))
    }

    function onMouseMove() {
      var i = date.index(x.invert(d3.mouse(svg[0][0])[0]));
      if (i >= 0 && i < counts.length) {
        var d = date(i), n = counts[i], dn = defaultCounts[i], s = samples[i], ds = defaultSamples[i];
        if (Math.abs(dn - n)/max < .15) {
          // if values are close enough, only render the experiment label so the default label doesn't overlap
          gHoverDef.style("display", "none");
        } else {
          samplesHoverDef.text(ds + " samples")
          countHoverDef.text(dn + "%");
          gHoverDef.style("display", "").attr("transform", "translate(" + x(d) + "," + y(dn) + ")");
        }
        samplesHover.text(s + " samples")
        dateHover.text(d3.time.format("%a %b %d")(d).replace(" 0", " "));
        countHover.text(n + "%");
        gHover.style("display", "").attr("transform", "translate(" + x(d) + "," + y(n) + ")");
      } else {
        gHover.style("display", "none");
        gHoverDef.style("display", "none");
      }
    }
  }

  $c.each(drawGraph);

});
