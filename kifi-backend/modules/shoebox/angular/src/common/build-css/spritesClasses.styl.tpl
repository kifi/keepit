{
  // Default options
  'functions': false
}

{{#items}}
.sprite-{{name}} {
  display: inline-block
  vertical-align: middle
  sprite2x(${{name}})
}
{{/items}}
