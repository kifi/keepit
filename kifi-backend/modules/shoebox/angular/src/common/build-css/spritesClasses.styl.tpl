{
  // Default options
  'functions': false
}

.sprite
  display: inline-block
  vertical-align: middle
  font-size: 0

{{#items}}
.sprite-{{name}}
  sprite2x(${{name}})

{{/items}}
