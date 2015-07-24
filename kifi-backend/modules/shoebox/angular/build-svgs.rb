#!/usr/bin/env ruby

require 'pathname'
require 'json'
require 'fileutils'

@sprite_name = "symbol-sprite"
@base_dir = "./img/symbol-sprites/"
@svg_lib = @base_dir + "lib/"
@export_dir = @base_dir + "dist/"

def build
  @svg_names = Dir.glob(["#{@svg_lib}**/*.svg"]).reject{ |entry| entry =~ /^\.{1,2}$/ }

  # Edit array to only contain root name
  @svg_names.map! {|item| File.basename(item, '.svg')}

  puts "Compiling #{@svg_names.count} SVGs"

  createSVG()
end

def createSVG()
  symbol_links = ""
  FileUtils.mkdir_p(@export_dir)
  File.open("#{@export_dir + @sprite_name}.svg", "w+") do |f|
    f.truncate 0
    f.puts "<svg xmlns=\"http://www.w3.org/2000/svg\" style=\"display: none\">\n"
    @svg_names.each do |svg_name|
      f.puts "  <symbol id=\"#{svg_name}\" preserveAspectRatio=\"none\" viewBox=\"0 0 512 512\">"
      addPathsToFile(f, nil, svg_name, false)
      f.puts "  </symbol>"

      symbol_links += "<svg class=\"icon\"><use xlink:href=\"##{svg_name}\" /></svg>"
      symbol_links += "<svg class=\"icon-thin\"><use xlink:href=\"##{svg_name}\" /></svg>"
    end
    f.puts "</svg>\n"
  end

  symbol_svg = File.read("#{@export_dir + @sprite_name}.svg")
  File.open("#{@base_dir}/test.html", "w+") do |test|
    test.puts "<h1>SVG Icon Test</h1>"
    test.puts symbol_svg
    test.puts "<style>.icon { width: 25px; height: 25px; fill: black; stroke: none }</style>"
    test.puts "<style>.icon-thin { width: 25px; height: 25px; stroke: red; stroke-width: 10; fill: none; margin-right: 10px; }</style>"
    test.puts symbol_links
  end
end

def addPathsToFile(f, dir = nil, className = nil, responsive = false)
  path = dir ? "./img/svg/#{dir}/#{className}.svg" : "#{@svg_lib + className}.svg"
  File.open(path, 'r') do |d|
    while line = d.gets
      line = line.gsub %r{\<svg([^<]+)>}, ''
      line = line.gsub %r{\<\?xml([^<]+)>}, ''
      line = line.gsub %r{\<\!DOCTYPE([^<]+)>}, ''
      line = line.gsub '</svg>', ''
      line = line.gsub /fill=\"#.{6}\"/, ""
      line = line.gsub "fill=\"none\"", ""
      line = line.gsub /stroke=\"#.{6}\"/, ""
      line = line.gsub "stroke=\"none\"", ""
      line = line.gsub /stroke\-width=\".{1,3}\"/, ""
      line = line.gsub /\n/, ""
      if responsive
        line = line.gsub '<path ', "<path class=\"#{dir}\" "
        line = line.gsub '<g ', "<g class=\"#{dir}\" "
        line = line.gsub '<rect ', "<rect class=\"#{dir}\" "
        line = line.gsub '<polygon ', "<polygon class=\"#{dir}\" "
      end
      f.puts "    #{line}"
    end
  end
end

build
