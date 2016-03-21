(function(){
  var getById = document.getElementById.bind(document);
  var boxShadow = ';box-shadow:green 0px 0px 20px;';
  var data = {
    'fields': {
      'company_name': 'FortyTwo, Inc.',
      'company_website': 'https://www.kifi.com',
      'extension_display_name': 'Kifi - Connecting People with Knowledge',
      'download_url': 'https://www.kifi.com/extensions/safari/kifi.safariextz',
      'long_description': 'The best knowledge management tool for small teams. Collect, organize, and share knowledge with the Kifi Service. Keep (or bookmark) web pages in libraries, stream research into one pool, and collaborate over it. Make research accessible to your team.',
      'category[]': [
        'Bookmarking',
        'Productivity',
        'Search Tools'
      ],
      'extension[]': [
        'Toolbar button',
        'Adds page content',
        'Injects controls into webpages',
        'Changes appearance of the web content',
        'Runs invisibly in the background'
      ]
    },
    'highlights': [
      'whats_new',
      'extension_icon',
      'extension_image'
    ]
  };
  var fields = data.fields;
  var highlights = data.highlights;

  setFieldValues(fields);
  setHighlights(highlights);

  function setFieldValues(fields) { Object.keys(fields).forEach(setFieldValue.bind(fields)); }
  function setFieldValue(field) { (field.indexOf('[]') > -1 ? setBooleanFieldValue : setTextFieldValue)(field, this[field]); }
  function setTextFieldValue(id, value) { getById(id).value = value; }
  function setBooleanFieldValue(id, values) { values.map(getCheckbox.bind(null, id)).forEach(setCheckbox); }
  function getCheckbox(id, value) { return document.querySelector('[name="' + id + '"][value="' + value + '"]'); }
  function setCheckbox(box) { box.checked = true; }
  function setHighlights(h) { h.map(getById).forEach(function ($h) { $h.setAttribute('style', $h.getAttribute('style') + boxShadow); }); }
}());
