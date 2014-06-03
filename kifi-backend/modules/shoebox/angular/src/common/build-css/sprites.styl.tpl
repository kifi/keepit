{
  // Default options
  'functions': true
}

{{#items}}
${{name}}_x = {{px.x}};
${{name}}_y = {{px.y}};
${{name}}_offset_x = {{px.offset_x}};
${{name}}_offset_y = {{px.offset_y}};
${{name}}_width = {{px.width}};
${{name}}_height = {{px.height}};
${{name}}_total_width = {{px.total_width}};
${{name}}_total_height = {{px.total_height}};
${{name}}_image = '{{{escaped_image}}}';
${{name}} = {{px.x}} {{px.y}} {{px.offset_x}} {{px.offset_y}} {{px.width}} {{px.height}} {{px.total_width}} {{px.total_height}} '{{{escaped_image}}}';

{{/items}}

{{#options.functions}}


spriteWidth($sprite) {
  width: $sprite[4];
}

spriteHeight($sprite) {
  height: $sprite[5];
}

spritePosition($sprite) {
  background-position: $sprite[2] $sprite[3];
}

spriteImage($sprite) {
  background-image: url($sprite[8]);
}

spriteBackgroundSize($sprite) {
  background-size: $sprite[6] $sprite[7];
}

sprite($sprite) {
  spriteImage($sprite)
  spritePosition($sprite)
  spriteWidth($sprite)
  spriteHeight($sprite)
  spriteBackgroundSize($sprite)
}

spriteWidth2x($sprite) {
  width: ceil((($sprite[4] + 1)/2));
}

spriteHeight2x($sprite) {
  height: ceil((($sprite[5] + 1)/2));
}

spritePosition2x($sprite) {
  background-position: ceil(($sprite[2]/2)) ceil(($sprite[3]/2));
}

spriteBackgroundSize2x($sprite) {
  background-size: ceil(($sprite[6]/2)) ceil(($sprite[7]/2));
}

sprite2x($sprite) {
  spriteImage($sprite)
  spritePosition2x($sprite)
  spriteWidth2x($sprite)
  spriteHeight2x($sprite)
  spriteBackgroundSize2x($sprite)
}
{{/options.functions}}